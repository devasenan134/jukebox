package io.github.devasenan134.jukebox.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import java.io.File
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import io.github.devasenan134.jukebox.server.library.AudioTools
import io.github.devasenan134.jukebox.server.library.JukeboxLibrary
import io.github.devasenan134.jukebox.server.library.Listening
import io.github.devasenan134.jukebox.server.library.MusicLibrary
import io.github.devasenan134.jukebox.server.subsonic.SubsonicApi
import io.github.devasenan134.jukebox.server.subsonic.SubsonicLibrary
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = LoggerFactory.getLogger("jukebox")
private const val MAX_BODY_BYTES = 1024 * 1024L

fun main(args: Array<String>) {
    val config = Config.fromEnv()
    // Accounts from the command line: jukebox user add|password|admin|remove|list (see UserCommand).
    if (args.firstOrNull() == "user") {
        kotlin.system.exitProcess(UserCommand.forConfig(config).run(args.drop(1)))
    }
    // Moving over from Navidrome and the Isaipetti companion server, once (docs/milestone-2.md):
    // jukebox import --navidrome <navidrome.db> --social <isaipetti-social.db> [--social-data <folder>] [--old-navidrome <db>]...
    //                [--skip-user <navidrome username>]...
    if (args.firstOrNull() == "import") {
        fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val navidromeDb = arg("--navidrome") ?: error("--navidrome <navidrome.db> is needed")
        val socialDb = arg("--social") ?: error("--social <isaipetti-social.db> is needed")
        val dataDir = File(config.dbPath).absoluteFile.parentFile
        val report = kotlinx.coroutines.runBlocking {
            fun all(name: String) = args.withIndex().filter { it.value == name }.mapNotNull { args.getOrNull(it.index + 1) }
            Importer(Db(config.dbPath), Passwords(File(dataDir, "secret.key"))).run(
                navidromeDb, socialDb, arg("--social-data")?.let(::File), dataDir, all("--old-navidrome"),
                File(config.artworkDir ?: File(dataDir, "artwork").path), skipUsers = all("--skip-user").toSet(),
            )
        }
        println(eventJson.encodeToString(ImportReport.serializer(), report))
        return
    }
    embeddedServer(Netty, port = config.port) { jukeboxServer(config) }.start(wait = true)
}

/** Everything the server does, as one Ktor module (tests start it the same way). */
fun Application.jukeboxServer(
    config: Config,
    pushSender: PushSender = config.firebaseKeyFile?.let { FcmSender(it) } ?: NoPush,
    issueTracker: IssueTracker? = config.githubToken?.let { token -> config.githubRepo?.let { GitHubIssues(it, token) } },
    pushConfig: PushConfig? = config.firebaseAppConfigFile?.let { PushConfig.fromGoogleServices(it) },
    /** Where mixes, search, requests and stats get their music; by default the library in [Config.libraries]. */
    music: MusicSource? = null,
    catalog: Catalog = ITunesCatalog(),
    playlistReader: PlaylistReader = WebPlaylistReader(spotifyClientId = config.spotifyClientId, spotifyClientSecret = config.spotifyClientSecret),
) {
    val db = Db(config.dbPath)
    val friends = Friends(db)
    val hub = Hub(friends::friendIds)
    friends.hub = hub
    // Passwords are kept encrypted with a key that lives next to the database (docs/milestone-2.md).
    val signIn = SignIn(db, Passwords(File(File(config.dbPath).absoluteFile.parentFile, "secret.key")))
    val accounts = Accounts(db, signIn, onFriendsAdded = friends::announceFriendship)
    val chat = Chat(db, friends, hub, PictureFolder(config.dbPath, "group-pictures"), config.dbPath)
    val listen = ListenTogether(hub, chat::members, this, config.listenOwnerGraceMs, config.songRequestCooldownMs)
    chat.listenersOf = listen::listeners
    chat.listenOwnerOf = listen::owner
    listen.onEnded = chat::expireRequests
    chat.onLeft = listen::leftChat
    chat.onRemoved = listen::ended
    val push = Push(db, pushSender)
    friends.push = push
    chat.onUnseen = push::newMessage
    listen.onStarted = { userId, conversationId ->
        val recipients = chat.members(conversationId).filter { it != userId && !hub.isVisible(it) }
        push.listenStarted(userId, conversationId, recipients)
    }
    val bugReports = BugReports(issueTracker)
    val playlistLikes = PlaylistLikes(db)
    val music = music ?: config.libraries?.let { JukeboxLibrary(db, config.featuresDb) }
    val stats = Stats(db, music, defaultZone = config.timeZone)
    val mixes = music?.let { MixService(db, it, java.time.ZoneId.of(config.timeZone)) }?.also { it.warm() }
    // Without a cast file next to the database, search just has no actors.
    val castFile = File(config.castFile ?: File(File(config.dbPath).absoluteFile.parentFile, "movie-cast.jsonl").path)
    val search = music?.let { LibrarySearch(it, castFile) }
    val catalogService = CatalogService(db, castFile)
    // Asking for music the library doesn't have (it needs the library, to know what's missing).
    val requests = music?.let { MusicRequests(db, it, catalog, push, stats::isAdmin, stats::adminIds) }
    // Jukebox's own library (docs/milestone-1.md): scanned in the background, served through the Subsonic API.
    val musicLibrary = MusicLibrary.parse(config.libraries).takeIf { it.isNotEmpty() }?.let { defs ->
        MusicLibrary(
            db, defs, AudioTools(lowPriority = true),
            File(config.artworkDir ?: File(File(config.dbPath).absoluteFile.parentFile, "artwork").path),
            config.saavnIdMap?.let(::File), config.fingerprints, config.rescanEveryMinutes,
        ).also { it.start(this) }
    }
    val listening = Listening(db)
    // Playlists from Spotify, Apple Music, YouTube or a file, matched to the library.
    val imports = music?.let { PlaylistImports(db, it, listening, requests, playlistReader) }
    val pictures = Pictures(db, config.dbPath, listening.takeIf { musicLibrary != null }, musicLibrary?.artworkDir)
    val subsonic = musicLibrary?.let { lib -> SubsonicApi(SubsonicLibrary(db, lib.covers, lib::roots), signIn::checkToken, signIn::userId, listening) }
    val limiter = RateLimiter(maxPerMinute = 10)
    // Every 10 minutes, forget chat pictures nobody can see any more.
    launch {
        delay(30.seconds)
        while (isActive) {
            runCatching { chat.sweepImages() }.onFailure { log.warn("Sweeping chat pictures failed", it) }
            delay(10.minutes)
        }
    }

    install(ContentNegotiation) { json(eventJson) }
    install(CallLogging)
    // A few page addresses are also API addresses (/search). A browser opening the page asks for HTML and sends
    // no session token; the apps ask for JSON with one. So a browser gets the web app there, the apps the API.
    config.webDir?.let { dir ->
        val index = File(dir, "index.html")
        install(createApplicationPlugin("WebPagesSharedWithApi") {
            onCall { call ->
                // The web app's built files have their content's hash in the name: browsers can keep them for good.
                val path = call.request.path()
                if (path.startsWith("/assets/")) call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                else if (path.startsWith("/fonts/")) call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=604800")
                if (call.request.httpMethod == HttpMethod.Get && call.request.path() in WEB_PAGES_SHARED_WITH_API && index.isFile &&
                    call.request.headers[HttpHeaders.Accept].orEmpty().contains("text/html") && call.request.headers[HttpHeaders.Authorization] == null
                ) call.respondFile(index)
            }
        })
    }
    // Lists of albums and people are large JSON; squeezed, they reach the tunnel (and you) several times sooner.
    // Audio and pictures are already compressed and go as they are.
    install(Compression) {
        gzip {
            matchContentType(ContentType.Application.Json, ContentType.Text.Any, ContentType.Application.JavaScript)
            minimumSize(1024)
        }
    }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 45.seconds
        maxFrameSize = 4L * 1024 * 1024 // a listen-together queue of a few thousand songs fits
        contentConverter = KotlinxWebsocketSerializationConverter(eventJson)
    }
    install(StatusPages) {
        exception<ApiError> { call, e -> call.respond(e.status, ErrorResponse(e.message)) }
        exception<SerializationException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request")) }
        // A body that doesn't fit the request (a field missing, the wrong type) is the caller's mistake.
        exception<io.ktor.server.plugins.BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request")) }
        exception<Throwable> { call, e ->
            log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Something went wrong on the server"))
        }
    }
    // Refuse oversized requests before reading them (nothing legitimate comes close to 1 MB).
    install(createApplicationPlugin("BodySizeLimit") {
        onCall { call ->
            val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            // Chat pictures can be bigger (a GIF from the keyboard); ChatImage checks them.
            val path = call.request.local.uri.substringBefore('?')
            // An exported playlist file (an Apple Music library export can be several MB) is sent as text to preview.
            val limit = when {
                path.endsWith("/images") || path.endsWith("/voice") -> ChatImage.MAX_BYTES + 1024L
                path == "/imports/preview" -> 12 * 1024 * 1024L
                else -> MAX_BODY_BYTES
            }
            if (length != null && length > limit) call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("Request is too large"))
        }
    })
    install(Authentication) {
        bearer("session") {
            authenticate { credential -> accounts.userForToken(credential.token) }
        }
    }

    fun requestsOn() = requests ?: throw ApiError(HttpStatusCode.NotFound, "Requests are off on this server")

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        // The Subsonic API signs in with its own parameters (u, t, s), so it sits outside the session check.
        subsonic?.routes(this)
        // The web app: its files, and index.html for any page address it handles itself (/album/…). Every API
        // route above and below wins over it.
        config.webDir?.let { dir -> singlePageApplication { filesPath = dir; defaultPage = "index.html"; useResources = false } }

        route("/auth") {
            post("/login") {
                limiter.check(call)
                call.respond(accounts.login(call.receive()))
            }
            post("/signup") {
                limiter.check(call)
                call.respond(accounts.signup(call.receive()))
            }
        }

        // The WebSocket authenticates with ?token=, since not every client can set headers on it.
        webSocket("/ws") {
            val user = call.request.queryParameters["token"]?.let { accounts.userForToken(it) }
            if (user == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not logged in"))
                return@webSocket
            }
            val explicitDeviceId = call.request.queryParameters["device_id"]?.takeIf { it.isNotBlank() }
            val deviceId = explicitDeviceId ?: "legacy-${System.identityHashCode(this)}"
            val deviceName = call.request.queryParameters["device_name"]?.takeIf { it.isNotBlank() } ?: "Web Browser"
            val clientType = call.request.queryParameters["client_type"]?.takeIf { it.isNotBlank() } ?: "web"

            hub.connected(user.id, this, deviceId, deviceName, clientType, isExplicitDevice = explicitDeviceId != null)
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val event = runCatching { eventJson.decodeFromString(ClientEvent.serializer(), frame.readText()) }.getOrNull()
                    when (event) {
                        null -> Unit
                        is ListenStart, is ListenJoin, is ListenLeave, is ListenUpdate -> listen.handle(user.id, event)
                        is TypingUpdate -> runCatching { chat.typing(user.id, event.conversationId) }
                        else -> hub.handle(user.id, this, event, deviceId)
                    }
                }
            } finally {
                hub.disconnected(user.id, this, deviceId)
                // Offline on every device: leave any listen-together session.
                if (!hub.isOnline(user.id)) listen.wentOffline(user.id)
            }
        }

        authenticate("session") {
            get("/me") { call.respond(call.me()) }
            patch("/me") { call.respond(accounts.rename(call.me().id, call.receive<RenameRequest>().displayName)) }
            // A new password (Jukebox accounts); your other devices are signed out.
            post("/me/password") {
                val body = call.receive<ChangePasswordRequest>()
                val token = call.request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("Bearer ").trim()
                accounts.changePassword(call.me(), body.current, body.new, token)
                call.respond(HttpStatusCode.NoContent)
            }
            // Profile pictures: the body is the image itself.
            put("/me/avatar") { call.respond(pictures.setAvatar(call.me().id, Picture(call.receive<ByteArray>()))) }
            delete("/me/avatar") { call.respond(pictures.removeAvatar(call.me().id)) }
            get("/users/{id}/avatar") {
                val file = pictures.avatar(call.longParam("id")) ?: throw ApiError(HttpStatusCode.NotFound, "No picture")
                // The app asks with ?v=<when it was set>, so a new picture has a new address and this can be cached.
                call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=2592000")
                call.respondFile(file)
            }
            // A cover for a playlist you made (kept with the rest of the artwork).
            put("/playlists/{id}/cover") {
                pictures.setPlaylistCover(call.me(), call.parameters["id"].orEmpty(), Picture(call.receive<ByteArray>()))
                call.respond(HttpStatusCode.NoContent)
            }
            delete("/playlists/{id}/cover") {
                pictures.setPlaylistCover(call.me(), call.parameters["id"].orEmpty(), null)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/auth/logout-others") {
                accounts.logoutOthers(call.me().id, call.bearerToken())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/auth/logout") {
                accounts.logout(call.bearerToken())
                call.respond(HttpStatusCode.NoContent)
            }

            // Firebase settings for the app, so that nothing about this server's project is built into it.
            get("/push/config") {
                if (pushConfig == null || pushSender is NoPush) call.respond(HttpStatusCode.NotFound, ErrorResponse("Push notifications are off"))
                else call.respond(pushConfig)
            }

            route("/devices") {
                post {
                    push.register(call.me().id, call.receive<DeviceRequest>().token)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/remove") {
                    push.unregister(call.me().id, call.receive<DeviceRequest>().token)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/likes/playlists") {
                get { call.respond(playlistLikes.list(call.me().id)) }
                // ?ids=a,b,c -> {"a": 3, "b": 0, ...}: how many others liked each (shown on your own playlists).
                get("/counts") {
                    val ids = call.request.queryParameters["ids"].orEmpty().split(',')
                    call.respond(playlistLikes.counts(call.me().id, ids))
                }
                put {
                    playlistLikes.like(call.me().id, call.receive())
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/{id}") {
                    playlistLikes.unlike(call.me().id, call.parameters["id"].orEmpty())
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            // Spelling-tolerant search over the library, including lyricists and actors.
            route("/search") {
                fun searchOn() = search ?: throw ApiError(HttpStatusCode.NotFound, "Search is off on this server")
                get { call.respond(searchOn().search(call.request.queryParameters["q"].orEmpty().take(100))) }
                get("/people/{id}") { call.respond(searchOn().person(call.parameters["id"].orEmpty())) }
                // Songs and movies from the music catalog that aren't in the library, to request.
                get("/catalog") { call.respond(requestsOn().search(call.me(), call.request.queryParameters["q"].orEmpty().take(100))) }
            }

            // Importing a playlist from another service or a file: preview the matches, then save it.
            route("/imports") {
                fun importsOn() = imports ?: throw ApiError(HttpStatusCode.NotFound, "Importing is off on this server")
                post("/preview") { call.respond(importsOn().preview(call.receive())) }
                post { call.respond(importsOn().create(call.me(), call.receive())) }
                post("/request") { call.respond(importsOn().request(call.me(), call.receive())) }
            }

            // Requests for music that isn't in the library; admins answer them.
            route("/requests") {
                get { call.respond(requestsOn().mine(call.me())) }
                post { call.respond(requestsOn().request(call.me(), call.receive<NewMusicRequest>().id)) }
                delete("/{id}") {
                    requestsOn().cancel(call.me(), call.longParam("id"))
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            // Mixes, playlists and stations by Jukebox.
            route("/mixes") {
                fun mixesOn() = mixes ?: throw ApiError(HttpStatusCode.NotFound, "Mixes are off on this server")
                get { call.respond(mixesOn().home(call.me())) }
                get("/followed") { call.respond(mixesOn().followed(call.me())) }
                post("/radio") { call.respond(mixesOn().radio(call.me(), call.receive())) }
                post("/recommend") { call.respond(mixesOn().recommend(call.me(), call.receive())) }
                get("/{id}") { call.respond(mixesOn().mix(call.me(), call.parameters["id"].orEmpty())) }
                put("/{id}/follow") {
                    mixesOn().follow(call.me(), call.parameters["id"].orEmpty())
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/{id}/follow") {
                    mixesOn().unfollow(call.me(), call.parameters["id"].orEmpty())
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            // What the app played and skipped; mixes learn from it. Kept even when mixes are off.
            post("/plays") {
                mixes?.recordPlays(call.me(), call.receive<PlaysRequest>().events)
                call.respond(HttpStatusCode.NoContent)
            }

            // The event log (docs/milestone-2.md), a page at a time after a sequence number (admins only; the
            // recommendation engine reads it this way).
            get("/events") {
                if (!accounts.isAdmin(call.me().id) && !stats.isAdmin(call.me())) throw ApiError(HttpStatusCode.Forbidden, "Only admins can read the event log")
                val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 500).coerceIn(1, 5000)
                call.respond(db.tx {
                    query("SELECT seq, user_id, type, at, payload FROM events WHERE seq > ? ORDER BY seq LIMIT ?", after, limit) {
                        EventDto(it.getLong(1), (it.getObject(2) as Number?)?.toLong(), it.getString(3), it.getLong(4), eventJson.parseToJsonElement(it.getString(5)))
                    }
                })
            }

            // The library: what's been scanned, and a rescan on demand (admins only).
            route("/library") {
                fun libraryOn() = musicLibrary ?: throw ApiError(HttpStatusCode.NotFound, "This server has no library of its own")
                suspend fun ApplicationCall.admin() { if (!accounts.isAdmin(me().id) && !stats.isAdmin(me())) throw ApiError(HttpStatusCode.Forbidden, "Only admins can do this") }
                get("/status") { call.admin(); call.respond(libraryOn().status()) }
                post("/scan") {
                    call.admin()
                    val lib = libraryOn()
                    call.application.launch { lib.scanAll() }
                    call.respond(HttpStatusCode.Accepted, mapOf("status" to "scanning"))
                }
            }

            // Listening stats, only for admins.
            route("/admin") {
                get("/access") { call.respond(AdminAccessDto(stats.isAdmin(call.me()))) }
                get("/stats") { call.respond(stats.report(call.me(), call.request.queryParameters["tz"])) }
                get("/requests") { call.respond(requestsOn().all(call.me())) }
                post("/requests/{id}/done") { call.respond(requestsOn().complete(call.me(), call.longParam("id"))) }
                post("/requests/{id}/decline") {
                    call.respond(requestsOn().decline(call.me(), call.longParam("id"), call.receive<DeclineMusicRequest>().note))
                }
            }

            post("/bug-reports") { call.respond(bugReports.report(call.me(), call.receive())) }

            route("/invites") {
                get { call.respond(accounts.invites(call.me().id)) }
                post { call.respond(accounts.createInvite(call.me().id)) }
                delete("/{code}") {
                    accounts.deleteInvite(call.me().id, call.parameters["code"].orEmpty())
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/friends") {
                get { call.respond(friends.list(call.me().id)) }
                get("/requests") { call.respond(friends.requests(call.me().id)) }
                post("/requests") { call.respond(friends.request(call.me(), call.receive<AddFriendRequest>().username)) }
                post("/requests/{userId}/accept") {
                    friends.accept(call.me(), call.longParam("userId"))
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/requests/{userId}/decline") {
                    friends.decline(call.me(), call.longParam("userId"))
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/{userId}") {
                    friends.remove(call.me(), call.longParam("userId"))
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/conversations") {
                get { call.respond(chat.conversations(call.me().id)) }
                post("/dm") { call.respond(chat.openDm(call.me(), call.receive<NewDmRequest>().userId)) }
                post("/group") { call.respond(chat.createGroup(call.me(), call.receive())) }
                get("/{id}/messages") {
                    val before = call.request.queryParameters["before"]?.toLongOrNull()
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    call.respond(chat.messages(call.me().id, call.longParam("id"), before, limit))
                }
                post("/{id}/messages") { call.respond(chat.send(call.me(), call.longParam("id"), call.receive())) }
                // A photo, GIF or sticker: the picture is the body; what it is, its size, caption and reply go in the address.
                post("/{id}/images") {
                    val q = call.request.queryParameters
                    val image = ChatImage(
                        call.receive<ByteArray>(), q["kind"].orEmpty(), q["width"]?.toIntOrNull() ?: 0, q["height"]?.toIntOrNull() ?: 0,
                    )
                    val request = SendMessageRequest(q["caption"].orEmpty(), replyTo = q["replyTo"]?.toLongOrNull())
                    call.respond(chat.send(call.me(), call.longParam("id"), request, image = image))
                }
                // A voice message: the recording is the body; its length and reply go in the address.
                post("/{id}/voice") {
                    val q = call.request.queryParameters
                    val voice = VoiceNote(call.receive<ByteArray>(), q["durationMs"]?.toLongOrNull() ?: 0)
                    call.respond(chat.send(call.me(), call.longParam("id"), SendMessageRequest(replyTo = q["replyTo"]?.toLongOrNull()), voice = voice))
                }
                get("/{id}/messages/{messageId}/voice") {
                    val file = chat.voice(call.me().id, call.longParam("id"), call.longParam("messageId"))
                        ?: throw ApiError(HttpStatusCode.NotFound, "No recording")
                    call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
                    call.respondFile(file)
                }
                // Forward a message to other chats of yours (it's sent there by you, marked "Forwarded").
                post("/{id}/messages/{messageId}/forward") {
                    val to = call.receive<ForwardRequest>().conversationIds
                    call.respond(chat.forward(call.me(), call.longParam("id"), call.longParam("messageId"), to))
                }
                // Search a chat's messages (text, and shared songs' titles and artists).
                get("/{id}/search") {
                    call.respond(chat.search(call.me().id, call.longParam("id"), call.request.queryParameters["q"].orEmpty()))
                }
                get("/{id}/messages/{messageId}/image") {
                    val file = chat.image(call.me().id, call.longParam("id"), call.longParam("messageId"))
                        ?: throw ApiError(HttpStatusCode.NotFound, "No picture")
                    // A message's picture never changes.
                    call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
                    call.respondFile(file)
                }
                // Pinned messages: anyone in the chat pins one for 24 hours, 7 days or 30 days, or unpins it.
                post("/{id}/pins") {
                    val request = call.receive<PinRequest>()
                    call.respond(chat.pin(call.me(), call.longParam("id"), request.messageId, request.hours))
                }
                // Your own messages: change their text, or delete them for everyone. Anyone reacts with an emoji.
                patch("/{id}/messages/{messageId}") {
                    val request = call.receive<EditMessageRequest>()
                    call.respond(chat.edit(call.me(), call.longParam("id"), call.longParam("messageId"), request.body, request.mentions))
                }
                delete("/{id}/messages/{messageId}") { call.respond(chat.deleteMessage(call.me(), call.longParam("id"), call.longParam("messageId"))) }
                put("/{id}/messages/{messageId}/reaction") {
                    call.respond(chat.react(call.me(), call.longParam("id"), call.longParam("messageId"), call.receive<ReactRequest>().emoji))
                }
                delete("/{id}/messages/{messageId}/reaction") {
                    call.respond(chat.react(call.me(), call.longParam("id"), call.longParam("messageId"), null))
                }
                delete("/{id}/pins/{messageId}") { call.respond(chat.unpin(call.me(), call.longParam("id"), call.longParam("messageId"))) }
                delete("/{id}") {
                    chat.delete(call.me().id, call.longParam("id"))
                    call.respond(HttpStatusCode.NoContent)
                }
                put("/{id}/picture") { call.respond(chat.setGroupPicture(call.me(), call.longParam("id"), Picture(call.receive<ByteArray>()))) }
                delete("/{id}/picture") { call.respond(chat.setGroupPicture(call.me(), call.longParam("id"), null)) }
                get("/{id}/picture") {
                    val file = chat.groupPicture(call.me().id, call.longParam("id")) ?: throw ApiError(HttpStatusCode.NotFound, "No photo")
                    call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=2592000")
                    call.respondFile(file)
                }
                // The group's owner renames it, and adds and removes members; everyone can see who's online.
                put("/{id}/name") { call.respond(chat.renameGroup(call.me(), call.longParam("id"), call.receive<RenameGroupRequest>().name)) }
                post("/{id}/members") { call.respond(chat.addMembers(call.me(), call.longParam("id"), call.receive<AddMembersRequest>().userIds)) }
                delete("/{id}/members/{userId}") { call.respond(chat.removeMember(call.me(), call.longParam("id"), call.longParam("userId"))) }
                get("/{id}/online") { call.respond(chat.onlineMembers(call.me().id, call.longParam("id"))) }
                post("/{id}/leave") {
                    chat.leave(call.me(), call.longParam("id"))
                    call.respond(HttpStatusCode.NoContent)
                }
                delete("/{id}/everyone") {
                    chat.deleteForEveryone(call.me(), call.longParam("id"))
                    call.respond(HttpStatusCode.NoContent)
                }
                // Song requests while listening together: a listener asks, the session's owner answers.
                post("/{id}/listen/requests") {
                    val id = call.longParam("id")
                    val body = call.receive<SongRequestBody>()
                    listen.requireRequester(call.me().id, id)
                    call.respond(chat.requestSong(call.me(), id, body.song, body.mode))
                }
                post("/{id}/listen/requests/{messageId}") {
                    val id = call.longParam("id")
                    listen.requireOwner(call.me().id, id)
                    call.respond(chat.answerRequest(id, call.longParam("messageId"), call.receive<SongRequestAnswer>().accept))
                }
                post("/{id}/read") {
                    chat.markRead(call.me().id, call.longParam("id"), call.receive<MarkReadRequest>().messageId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            // Milestone 4: Jukebox API v2 (albums with releases & cast, people, songs, lyrics search)
            catalogService.routes(this, search)
        }
    }
    log.info("jukebox ready on port ${config.port}, library ${if (musicLibrary == null) "off" else "on"}, push ${if (pushSender is NoPush || pushConfig == null) "off" else "on"}, feedback ${if (issueTracker == null) "off" else "on"}, mixes ${if (mixes == null) "off" else "on"}")
    launch {
        if (db.read { queryOne("SELECT 1 FROM users WHERE deleted_at IS NULL LIMIT 1") { 1 } } == null) {
            log.warn("No accounts yet. Make the first one with: jukebox user add <username> --admin")
        }
    }
}

private fun ApplicationCall.me(): UserDto = principal<UserDto>() ?: throw ApiError(HttpStatusCode.Unauthorized, "Not logged in")

private fun ApplicationCall.bearerToken(): String =
    request.headers["Authorization"]?.removePrefix("Bearer ")?.trim() ?: throw ApiError(HttpStatusCode.Unauthorized, "Not logged in")

private fun ApplicationCall.longParam(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw ApiError(HttpStatusCode.BadRequest, "Bad $name")

/** Slows down password guessing: at most [maxPerMinute] login/sign-up attempts per IP address per minute. */
class RateLimiter(private val maxPerMinute: Int) {
    private val hits = ConcurrentHashMap<String, MutableList<Long>>()

    fun check(call: ApplicationCall) {
        // Behind Cloudflare, the real client address is in this header.
        val ip = call.request.headers["CF-Connecting-IP"] ?: call.request.local.remoteAddress
        val t = now()
        val recent = hits.compute(ip) { _, list -> (list ?: mutableListOf()).apply { removeAll { it < t - 60_000 }; add(t) } }!!
        if (recent.size > maxPerMinute) throw ApiError(HttpStatusCode.TooManyRequests, "Too many attempts, try again in a minute")
    }
}

/** Pages of the web app whose address is also an API route (the API answers when a session token is sent). */
private val WEB_PAGES_SHARED_WITH_API = listOf("/search", "/friends", "/requests")
