package io.github.devasenan134.jukebox.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GatewayTest {
    private fun dbFile() = File.createTempFile("gateway_test", ".db").apply { delete(); deleteOnExit() }.path
    private val dbPath = dbFile()

    @Test
    fun `device connects, reports playback, and controls remotely`() = testApplication {
        application { jukeboxServer(Config(0, dbPath)) }
        val client = createClient {
            install(ContentNegotiation) { json(eventJson) }
            install(WebSockets)
        }

        // Alice logs in
        val alice = client.login("alice")

        // Alice connects her phone
        val phoneWs = client.webSocketSession(
            "/ws?token=${alice.sessionToken}&device_id=phone-1&device_name=Pixel+8&client_type=android"
        )
        val dev1 = phoneWs.nextEvent() as DevicesEvent
        assertEquals("phone-1", dev1.activeDeviceId)
        assertEquals(1, dev1.devices.size)
        assertEquals("Pixel 8", dev1.devices.first().name)
        assertTrue(dev1.devices.first().isCurrent)

        // Alice connects her laptop browser
        val webWs = client.webSocketSession(
            "/ws?token=${alice.sessionToken}&device_id=web-1&device_name=Chrome+Mac&client_type=web"
        )
        val devWeb = webWs.nextEvent() as DevicesEvent
        assertEquals(2, devWeb.devices.size)

        // The phone receives updated devices event too
        val phoneUpdate = phoneWs.nextEvent() as DevicesEvent
        assertEquals(2, phoneUpdate.devices.size)

        // Phone starts playing a song
        val song = SongRef("song-1", "Nenjukkul Peidhidum", "Harris Jayaraj", "Vaaranam Aayiram")
        val playback = DevicePlaybackState(
            song = song,
            playing = true,
            positionMs = 12000,
            volume = 0.9f,
        )
        phoneWs.send(Frame.Text(eventJson.encodeToString(ClientEvent.serializer(), DevicePlaybackUpdate(playback))))

        // Both devices receive the updated devices event showing phone is playing
        val webEventAfterPlay = webWs.nextEvent() as DevicesEvent
        assertEquals("phone-1", webEventAfterPlay.activeDeviceId)
        val phoneDev = webEventAfterPlay.devices.find { it.id == "phone-1" }
        assertNotNull(phoneDev)
        assertTrue(phoneDev.playing)
        assertEquals(song, phoneDev.song)

        // Phone receives device update as well
        val phoneEventAfterPlay = phoneWs.nextEvent() as DevicesEvent
        assertEquals("phone-1", phoneEventAfterPlay.activeDeviceId)

        // Laptop sends remote pause command to phone
        val remoteCmd = RemoteCommand(
            targetDeviceId = "phone-1",
            action = "pause",
        )
        webWs.send(Frame.Text(eventJson.encodeToString(ClientEvent.serializer(), remoteCmd)))

        // Phone receives the remote command
        val cmdReceived = phoneWs.nextEvent() as RemoteCommandEvent
        assertEquals("pause", cmdReceived.action)
        assertEquals("web-1", cmdReceived.byDeviceId)

        // Laptop requests transfer playback to laptop
        val transferCmd = TransferPlayback(toDeviceId = "web-1")
        webWs.send(Frame.Text(eventJson.encodeToString(ClientEvent.serializer(), transferCmd)))

        // Web receives the transfer playback event
        val transferReceived = webWs.nextEvent() as TransferPlaybackEvent
        assertEquals(song, transferReceived.state.song)
        assertEquals("phone-1", transferReceived.fromDeviceId)
    }

    private suspend fun HttpClient.login(username: String): SessionResponse {
        addAccount(dbPath, username)
        return postJson("/auth/login", loginRequest(username)).body<SessionResponse>()
    }

    private suspend inline fun <reified B> HttpClient.postJson(path: String, body: B, token: String? = null): HttpResponse =
        post(path) {
            token?.let { bearerAuth(it) }
            if (body !is Unit) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend fun DefaultWebSocketSession.nextEvent(): Event = withTimeout(5_000) {
        val frame = incoming.receive() as Frame.Text
        eventJson.decodeFromString(Event.serializer(), frame.readText())
    }
}
