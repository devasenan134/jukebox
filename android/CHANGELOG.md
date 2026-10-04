# App patch notes

Every release of the Android app, newest first. (Up to 0.11.1 it lived in the isaipetti repository, with its
companion server; the server is Jukebox now, with its own notes in the repository's history.)

Each release has an APK named `isaipetti-<version>.apk` and a git commit titled `<Summary> (<version>)`.
Notes are grouped into **New**, **Improved**, **Fixed** and **Server**. Plans are in the repository's [ROADMAP.md](../ROADMAP.md).

---

## 0.12.1 (2026-10-04): One server address

App only.

### Improved
- **One server address.** Jukebox serves music and friends at the same address, so **Additional settings** on the login screen has just one field now (no separate friends server), and Settings shows one **Server** line.
- **Moved to Jukebox by itself.** If you logged in before Jukebox, the app saved the old music and friends addresses (musicnote and gamertags). It now switches them to jukebox.craftingtable.cc once when it starts. You stay logged in, with everything as it was.

---

## 0.12.0 (2026-10-04): Jukebox, no server address to type

App only. The server is now Jukebox, at jukebox.craftingtable.cc.

### Improved
- **Logging in and signing up need no server address.** The app connects to jukebox.craftingtable.cc, which
  serves music and friends at one address. You type just your username and password (and the invite code
  when signing up).
- **Running your own server?** Open **Additional settings** under the login button and type its address. A
  separate friends server can still be given there, for the older two-server setup.
- **Updates now come from the Jukebox repository** (github.com/devasenan134/jukebox), where the app lives now.
  Nothing to do: this version and the ones after update in place, as before.
- The app is licensed under the Apache License 2.0 now (it was GPL-3.0), like the rest of Jukebox. Releases up
  to 0.11.1 stay under GPL-3.0.

---

## 0.11.1 (2026-09-25): Lyrics on the lock screen

App only. No server changes.

### New
- **Lyrics on the lock screen.** Turn it on in Settings → Lock screen. Then, when you lock your phone while music plays, turning the screen on shows the time, a small player (cover, title, artist, previous / play-pause / next) and the synced lyrics, on the song's colours. The page is slightly see-through, so your lock screen shows faintly behind. The phone stays locked: swipe up (or press Back) for the normal lock screen, and unlocking closes it. In someone else's jam, the controls are off, like in the player.
- **Keep the screen on** while the lyrics show and the music plays (a second switch, on by default). When the music is paused, the screen goes off as usual.
- Android shows a screen over the lock screen only for apps allowed to use "full-screen notifications". If yours doesn't allow it (Android 14 can turn it off), Settings says so and has a button to the right page in Android's settings.

---

## 0.11.0 (2026-09-25): Request songs and movies that aren't in the library

### New
- **Search finds music that isn't in the library.** After your results, a **Not in the library** section shows matching movies and songs from the iTunes catalog (Indian store), with cover, movie, year and artists. Songs and movies you already have are left out, whatever the spelling, and so are covers, karaoke and "slowed" versions.
- **Request button.** Tap **Request** on a song or a whole movie. It shows **Requested ✓**; tap that to take it back. If a friend already asked, you'll see "Asked for by …", and your request joins theirs.
- **Your requests.** Search (before you type) has **Your requests**: what you asked for, whether it's waiting, in the library (tap to open the movie) or couldn't be found (with the reason).
- **Notifications.** When your request is added you get "It's in the library"; tapping it opens the movie. If it can't be found, you're told that too. A new "Song and movie requests" notification channel can be muted on its own.
- **For admins:** you get a notification for each new request. **Your requests** has an **Everyone's** tab with **Added** (checks that the music is really in the library, then tells everyone who asked) and **Can't find** (with an optional reason).

### Server
- `GET /search/catalog?q=` (iTunes Search API; answers kept 6 hours, at most 16 catalog calls a minute), `GET/POST /requests`, `DELETE /requests/{id}`, and for Navidrome admins `GET /admin/requests`, `POST /admin/requests/{id}/done` and `POST /admin/requests/{id}/decline`. Up to 20 waiting requests per person.
- Push types `musicRequest` (admins), `musicReady` (with `albumId`) and `musicDeclined`.
- Database schema 17 (`music_requests`, `music_request_askers`), so back up the DB before deploying. The server now calls itunes.apple.com.

---

## 0.10.1 (2026-09-25): @mentions, and reactions your way

### New
- **@mention people in groups.** Type **@** in a group chat and the members show above the message box; keep typing to narrow them down, and tap one to put "@Their Name" in. Mentions show in colour in the chat, and a mention of you is highlighted. The person you mention gets a notification saying "Mentioned you: …", and the chat list shows an **@** next to the chat until they've read it.
- **See who reacted.** Tap the reactions under a message: a sheet lists everyone, with a tab for each emoji ("All 3 · 👍 2 · ❤️ 1"). Tap your own to remove it.
- **React with any emoji.** The long-press menu's emoji row has a **＋** for a bigger choice of emoji, or any emoji typed from the keyboard.
- **Choose your quick reactions.** Tap **✎** at the end of the emoji row, then tap a place and the emoji to put there. They're kept on your phone; **Reset** brings back 👍 ❤️ 😂 😮 😢 🙏.

### Server
- Messages take and carry `mentions` (people in the chat only; editing a message can change them). Chats carry `unreadMentions`. Push notifications to mentioned people start with "Mentioned you:".
- Database schema 16 (`messages.mentions`), so back up the DB before deploying.

---

## 0.10.0 (2026-09-25): Voice messages, forwarding and search in a chat

### New
- **Voice messages.** With nothing typed, the send button is a microphone. Tap it to record (the first time, Android asks to allow the microphone), then tap send, or the bin to throw it away. They can be up to 5 minutes. In the chat, tap play; drag the bar to skip around. Your music pauses while one plays and carries on after, except in a jam, where it keeps playing for everyone. Voice messages can be replies too.
- **Forward a message.** Long-press it, tap **Forward** and pick up to 10 chats. Text, songs, photos, GIFs, stickers and voice messages all work. It arrives from you, marked "↪ Forwarded".
- **Search in a chat.** Tap 🔍 at the top of a chat and type. Messages and shared songs (title or artist) that match show up, newest first, with the match in bold. Tap one to go to it; older messages load by themselves if it's far back.

### Improved
- Tapping "Load earlier messages" no longer jumps you back to the newest message.

### Server
- `POST /conversations/{id}/voice?durationMs=&replyTo=` (an .m4a recording up to 5 minutes and 5 MB) and `GET /conversations/{id}/messages/{messageId}/voice`. Recordings are kept in `data/chat-voice/<chat>/` and deleted with their message or chat.
- `POST /conversations/{id}/messages/{messageId}/forward` (`conversationIds`, up to 10) and `GET /conversations/{id}/search?q=`.
- Messages carry `voiceMs` and `forwarded`. Database schema 15, so back up the DB before deploying.

---

## 0.9.0 (2026-09-24): Reactions, editing, deleting, typing and seen

### New
- **React to messages.** Long-press a message: a row of emoji (👍 ❤️ 😂 😮 😢 🙏) is at the top of the menu. Reactions show under the message with how many people chose each. Tap one to react the same way, or tap yours to take it back. Everyone has one reaction per message; choosing another replaces it.
- **Edit your messages.** Long-press your own message and tap **Edit**. The text goes back into the message box; change it and tap send. It shows "edited" next to the time. Photo captions can be edited too.
- **Delete your messages for everyone.** Long-press your own message and tap **Delete for everyone**. It's replaced with "This message was deleted" (its photo, pins and reactions go too), and replies to it say "Deleted message".
- **"typing…"** shows under the chat's name while the other person types (in a group: "Alice is typing…").
- **Seen.** Under your newest message: "Seen" in a DM, and "Seen by Alice, Bob" or "Seen by everyone" in a group.

### Server
- `PUT`/`DELETE /conversations/{id}/messages/{messageId}/reaction`, `PATCH` (edit) and `DELETE` (delete for everyone) on `/conversations/{id}/messages/{messageId}`, only for your own messages.
- Messages carry `reactions`, `editedAt` and `deleted`; chats carry `readMarks`. New events: `typing` (the app sends one every few seconds while you type, and the server passes it on), and `read` (someone read up to a message).
- Database schema 14 (a `reactions` table, `edited_at` and `deleted_at` on messages), so back up the DB before deploying.

---

## 0.8.0 (2026-09-24): Photos, GIFs, stickers and pinned messages

### New
- **Send photos.** Tap the picture button next to the message box, then pick a photo (or a GIF) from your gallery or take one with the camera. You see it before it's sent and can add a caption. Photos are shrunk to a sharp but small size, so they send fast.
- **GIFs and stickers from your keyboard.** In Gboard or Samsung's keyboard, open the GIF or sticker tab and tap one: it's sent straight away. GIFs and animated stickers move in the chat. Stickers show on their own, without a bubble.
- **Open a photo full screen.** Tap it. Pinch or double-tap to zoom, and tap **Save** to keep it in your phone's Pictures/Isaipetti folder (Android 10 and newer).
- **Pin messages.** Long-press a message and tap **Pin**, then choose **24 hours**, **7 days** or **30 days**. Pinned messages show in a bar under the chat's name. Tap the bar to go to the message (with more than one pin, each tap goes to the next). Long-press the bar, or the message, to unpin it. A chat can have 3 pins; pinning a fourth replaces the oldest. Everyone sees "… pinned a message", and tapping that line goes to the message too.
- Replies, the chat list and notifications say "📷 Photo", "GIF" or "Sticker" for pictures.

### Server
- `POST /conversations/{id}/images?kind=photo|gif|sticker&width=&height=&caption=&replyTo=` (the picture is the body: JPEG, PNG, WebP or GIF, up to 5 MB) and `GET /conversations/{id}/messages/{messageId}/image` (members who can see that message only). Files are kept in `data/chat-images/<chat>/`, and deleted with the chat.
- `POST /conversations/{id}/pins` (`messageId`, `hours` = 24, 168 or 720) and `DELETE /conversations/{id}/pins/{messageId}`. Chats now include their `pins`. A new `conversationUpdated` event tells apps to fetch a chat again after an unpin.
- Database schema 13 (picture columns on `messages`, a `pins` table), so back up the DB before deploying.

---

## 0.7.0 (2026-09-24): Replies, and renaming groups

### New
- **Reply to a message.** Swipe a message to the right, or long-press it and tap **Reply**. A "Replying to …" bar shows above the text box (✕ cancels it), and your message carries a quote of the one you answered. Tap the quote to jump to that message. Songs you share from the music button can be replies too. Someone added to a group later sees who a reply answers but not what the earlier message said.
- **Copy a message's text** from the same long-press menu.
- **The group's owner can rename it.** Open the group's members (tap its name at the top of the chat) and tap the pencil next to the name. Everyone in the group sees "… renamed the group to …" in the chat, and the new name shows up everywhere. Only the owner can rename it, just like adding and removing people.

### Server
- New `PUT /conversations/{id}/name` (owner only, 1 to 50 characters).
- Messages can have `replyTo` (a message of the same chat you can see, not a system line); messages come back with the quoted message. Database schema 12 (`messages.reply_to`), so back up the DB before deploying.

---

## Server update (2026-09-24): Stations aren't empty anymore

Server only, released after 0.6.19. No new app version.

- Opening a station from Home (Your stations, or Composer and artist stations) showed "Nothing here right now" and a greyed-out Play button. The server handed back the station's name and cover from Home instead of picking its songs. Now it picks them. Starting a radio from a song or the player already worked.

---

## 0.6.19 (2026-09-24): Mixes that know their languages

### Improved
- **Mixes don't jumble languages anymore.** Before, a mix was picked mostly by how songs sound, so a Tamil kuthu song could be followed by any loud English dance track. Now every song has a language (from its tags, its movie, its composer and singers, and its sound), and mixes use it the way you listen:
  - **Mixes about a feeling** (Sad, Feel Good, Party, Chill, Romance, Workout, Focus, Sleep), Discover Weekly and the decade mixes have the languages you listen to, about as much of each as you play them. If you play some English songs, your Sad Songs can have some sad English songs too.
  - **Mixes about a music culture stay in one language:** Kuthu Mix and the new **Kollywood Mass** are Tamil, and only take songs that sound like Indian film music (a Western-style song from a Tamil film doesn't belong with thappu drums); Melody, Retro, Carnatic Touch and Devotional are in your main language.
  - **Daily Mixes, stations and "This Is" mixes** stay in the language of the songs or person they're made from. If you like English songs, they get a Daily Mix of their own.
  - A language you (almost) never play doesn't turn up in your mixes, Top 50, New Arrivals or Friends Mix.
- **Songs people play together come up together.** Mixes, stations and playlist suggestions now also look at which songs people here play one after another, and which songs share a playlist. That's what streaming services lean on most, and it knows what belongs together better than the sound alone.

### New
- **Kollywood Mass**, a mix of hero intros and mass beats.

### Server
- Each song's language (`Languages.kt`) and which songs people put together (`Together.kt`, from plays not started by a mix and from Navidrome playlists). The analyzer has two new descriptions, "indianfilm" and "western", to tell Indian film music from Western pop by sound; restart it to add them (it doesn't need to listen to the songs again). `./gradlew previewMixes` now shows each song's language and the languages in each mix.

---

## 0.6.18 (2026-09-24): Group members, and Now playing on Home

### New
- **See who's in a group.** Tap a group chat's name (or the names under it) to see its members, sorted into **In the jam**, **Online** and **Offline**. The jam's host has the DJ deck next to their name, and the group's owner is marked.
- **Add and remove people later.** The group's owner can add friends (**Add people**) or remove someone (✕ next to their name) from that list, or from ⋮ → **Members**. Someone who's added sees the chat from that moment on, not its earlier messages. Someone who's removed loses the chat. Everyone sees "… added …" or "… removed …" in the chat.
- **Long-press a profile picture** (or a group photo) to see it large. Tap anywhere to close it.
- **Now playing on Home.** The first tile in **Jump back in** is what's playing: the playlist, movie, mix, artist or Liked songs you started, with a record turning on it (it stops when you pause). Tap it to go back there.
- **The playing song stands out.** In **Recently Played** and in song lists, the song that's playing has bars moving up and down on it (they rest while it's paused).
- **Swipe between tabs** on the Friends page (Chats and Friends) and on Search → Movies (A–Z, Newest, Oldest, Recently added), like in Your Library.

### Fixed
- **Liking your own playlist keeps it in My Playlists.** Before, it moved to the other playlists.
- **Mixes in Jump back in have their own art again** (the same picture as everywhere else), not the cover of one of their songs.

### Server
- `POST /conversations/{id}/members` (`{"userIds": [...]}`) and `DELETE /conversations/{id}/members/{userId}`, for the group's owner only. `GET /conversations/{id}/online` lists which members have the app open.

---

## 0.6.17 (2026-09-24): The player keeps up, and closing the app stops the music

### Fixed
- **The player no longer gets stuck on an old song.** If the song changed while the app was in the background (Next or Previous in the notification, or a song ending on its own), the mini player and the full player now show the right song when you come back. Before, they showed the song from when you left until you swiped through the queue.
- **Closing the app stops the music.** Pressing Back on the app's first screen, or swiping the app away from your recent apps, now stops playback and removes the notification. Pressing Home still leaves the music playing.

---

## 0.6.16 (2026-09-24): Group menu on the right

### Improved
- **In a group chat, the ⋮ menu is the rightmost button**, with the queue and jam buttons to its left.

---

## 0.6.15 (2026-09-24): Jam buttons stay put

### Fixed
- **The chat's buttons no longer jump left during a jam.** With "Jamming with …" next to the chat's name, the queue and jam buttons stay at the right edge, where they are without a jam.

---

## 0.6.14 (2026-09-24): One jam button

### Improved
- **One jam button instead of two.** The bar under the chat's name (with **End jam** / **Leave**) is gone; who's jamming now shows next to the chat's name ("Jamming with Alice", "Alice is jamming").
- **The jam button shows where you stand.** A record: start a jam. A DJ deck: it's your jam, tap to end it. Headphones: you're in someone else's jam, tap to leave. Headphones with a plus: a jam is on, tap to join.

---

## 0.6.13 (2026-09-24): See who likes your playlists

### New
- **Likes on your playlists.** A playlist you made shows how many friends liked it under its name (**By you · 3 likes**, or **No likes yet**). In Your Library, your playlists that someone liked show the count too. Your own like isn't counted.

### Server
- `GET /likes/playlists/counts?ids=a,b` returns how many people (besides you) liked each playlist, up to 500 at a time. With an older server the app just doesn't show likes.

---

## 0.6.12 (2026-09-24): Jam controls in the chat

### New
- **Jam queue in the chat.** While you're in a chat's jam, a queue button sits next to the jam button at the top. Everyone in the jam can see what's coming up; the host can also drag songs to move them and swipe them left to remove them.
- **Your jam looks different.** When you started the jam in a chat, its button turns into a record. Tap it to end the jam for everyone. The bar under the chat's name says **End jam** instead of **Leave** for the host.

### Improved
- Search's recent list is now called **Your recent artists**.

### Fixed
- **Song requests in the chat can't be played by accident.** A request shows the song without a play button, and tapping it does nothing, so it can't replace your queue (or the jam's music).

---

## 0.6.11 (2026-09-24): Ask for a song now or next, and edit the queue

### New
- **Edit the queue.** In the queue panel, drag a song by its handle to move it, or swipe it left to remove it. The song that's playing can't be swiped away. Moving songs needs shuffle off. In a jam you host, your changes reach everyone; in someone else's jam the queue is view-only.
- **Ask for a song now or next.** In someone else's jam, swipe a song **right** to ask the host to play it right away (skipping the current song), or **left** to ask for it next. The request in the chat says which, and the host's button reads **Play now** or **Play next**.

### Fixed
- **No more request spam.** You can ask for a song once every 10 seconds, with at most 3 requests waiting for an answer at a time.

### Server
- `POST /conversations/{id}/listen/requests` takes `mode` (`"next"`, the default, or `"now"`); messages carry `requestMode`. Requests are refused with 429 when they come within 10 s of the last one (`songRequestCooldownMs`) or when 3 are already waiting. Database schema v11 (`messages.request_mode`).

---

## 0.6.10 (2026-09-24): Search by actor and lyricist, and spelling that doesn't have to be right

### New
- **Search by actor.** Type an actor's name to see their movies, marked **Starring …**. Tap them for a page with their movies and every song from those movies. The cast comes from Wikipedia and Wikidata and is still being filled in, so some films don't list everyone yet.
- **Search by lyricist.** Songs show **Lyrics by …** when you searched for who wrote them. A lyricist's page has all their songs and the movies they wrote for.
- **Spelling doesn't have to be right.** Search goes by how the words sound, so "kanavae" finds Kanave, "thanush" finds Dhanush and "putu velai malai" finds Pudhu Vellai Mazhai. Half-typed words work too.

### Improved
- Results are grouped as **People** (with what they do: Composer, Artist, Lyricist, Actor), **Movies** and **Songs**.
- One person, one result: different spellings of a name ("Vaali", "Vaalee") and an actor who also sings are shown as one person.

### Server
- `GET /search` and `GET /search/people/{id}`. Actors are read from `movie-cast.jsonl` next to the database (made by the library tools); without it, search just has no actors. If the friends server has no search, the app uses Navidrome's.

---

## 0.6.9 (2026-09-24): Jams have a host

### New
- **Whoever starts a jam runs it.** In a chat or a group, only the person who started listening together can play, pause, skip, seek or change the queue. Everyone else hears the same music; their controls are greyed out (and presses on the lock screen or in the notification are ignored).
- **Ask for a song.** In someone else's jam, swiping a song (either way), or **Play next** / **Add to queue**, sends a request in the chat. The host gets **Accept** and **Decline**; an accepted song plays after the current one, in the order requests were accepted. Everyone sees whether a request was accepted, declined, or left unanswered when the jam ended.
- **The mini player shows the jam.** Listeners see a jam icon where pause and next were. The host sees a small record spinning next to pause and next.

### Improved
- The player says whose jam you're in ("Alice's jam · they control the music").

### Fixed
- Swiping a song sometimes played it next (or queued it) twice.

### Server
- Sessions have an owner. The session ends when the owner leaves, or stays offline for more than a minute.
- `POST /conversations/{id}/listen/requests` and `POST /conversations/{id}/listen/requests/{messageId}` for song requests, and a `messageUpdated` event when one is answered.

---

## 0.6.7 (2026-09-24): Listening stats for admins

### New
- **Listening stats (admins only).** Settings has a new **Listening stats** card, shown only to people who are admins in Navidrome. It shows how much everyone listens: hours and plays for **today, 7 days, 30 days or all time**, when each person last played something, and a bar to compare them.
- **Tap a person** to see their top songs, movies and composers for that range.
- **Two charts for everyone together:** hours per day over the last 30 days, and which hours of the day people listen. Tap a bar to read its exact value.
- A song counts as played once half of it (or 4 minutes) is heard, and adds its full length, so the hours are a close estimate.

### Server
- `GET /admin/access` and `GET /admin/stats` read Navidrome's play history (read-only). Only Navidrome admins get an answer, and the server's own admin account is left out of the list.

---

## 0.6.6 (2026-09-24): A live pointer when sharing a part

### Improved
- **The pointer follows the song.** When you share a part of the song that's playing, the pointer on the waveform moves along with it. Drag the pointer and the song jumps there.
- **Preview plays just your part.** Preview moves the pointer to the start of your part and plays from there. At the end of the part the song pauses and the pointer stays there.
- **A play/pause button** at the end of the row (after Preview, Start here and End here) carries on playing from the pointer, past the end of your part too. It shows pause while the song plays.

---

## 0.6.5 (2026-09-24): Lyrics under the cover, a song waveform for clips

### New
- **The lyric being sung, under the cover.** When a song has synced lyrics, the current line shows between the cover and the title and slides up as the song moves on. Tap it to see all the lyrics.
- **See the song when you share a part of it.** "Share only a part" shows the song's waveform, like Instagram's music picker: the part you chose is in colour, the rest is faded. The first time for a song takes a few seconds; after that it shows at once.
- **A pointer on the waveform.** Tap or drag the small pointer above the waveform to anywhere in the song. **Start here** and **End here** use it, and it follows the preview while it plays.

### Improved
- **Every singer's name.** When the singers don't fit under the song title in the player, the line scrolls sideways so every name shows.
- **Previews leave your music alone.** Preview plays on its own, so it no longer changes your queue. Your music pauses for it and carries on afterwards. While listening together, Preview is off until the music is paused, and it never changes what the others hear.
- **"Artists" instead of "Singers"** in song details, the search box and Home.

### Server
- Home rows are now **This is: artists** and **Composer and artist stations**.

---

## 0.6.4 (2026-09-24): Graphite & mango, appearance settings and new tile art

### New
- **A brighter colour theme: Graphite & mango.** A soft graphite grey, lighter than before, with a ripe mango accent, in dark and light. The grey is neutral, so album covers bring the colour.
- **Appearance in Settings.** Choose **Auto** (follows your phone), **Light** or **Dark**. On Android 12 and later, **Use wallpaper colours** gives the app the colours of your wallpaper instead.
- **New art for mixes.** Made for you tiles (Daily Mixes, Discover Weekly, On Repeat...) are colour gradients. **This Is** tiles and **stations** show the composer, singer or song on a soft pastel.

### Improved
- **Home:** the list of songs is now **Recently Played** and lists every song that played, whether you tapped it or it came next in a movie, playlist or mix. The tiles below it are now **Jump back in**.
- **Your Library:** list rows are bigger (larger pictures and text).

---

## 0.6.3 (2026-09-24): Indigo night, and a new Home

### New
- **A new colour theme: Indigo night.** Deep indigo with a periwinkle accent, in dark and light mode. Titles are near white and subtitles a bright, cool grey, so text no longer looks dull.
- **Home, in a new order.** First **Made for <your name>** as tiles. Then **Recent songs**: songs you started by tapping them, from anywhere (a movie, a playlist, search, a chat...), as a list. Then **Recently played**: movies, playlists, Liked songs, mixes, composers and singers you started as a whole with **Play**, **Shuffle** or **Resume**, as tiles. Tapping a single song no longer adds its movie or playlist there.

### Improved
- **Your Library** starts at the top of the page (it sat in the middle), its sections are **All, Playlists, Movies, My Playlists**, and its pictures are a little bigger.

---

## 0.6.2 (2026-09-24): Made for you vs. showcases, cropping photos, and a tidier Your Library

### New
- **Crop your photos.** After you take or choose a photo for your profile, a group or a playlist cover, it opens so you can frame it: pinch to zoom, drag to move, **Rotate** to turn it. Profile and group pictures show a round guide, since that's how they appear.

### Improved
- **"Made for you" means it.** Only mixes built from your own listening say **Made for <your name>**: Daily Mixes, Discover Weekly, On Repeat, Friends Mix and Rewind, plus a new **Your stations** row (radio from the songs you play most).
- **Showcases for the library's composers and singers.** **This Is A.R. Rahman**, **This Is S. P. Balasubrahmanyam** and so on: their most played songs, the same for everyone. Mood, decade and **Composer and singer stations** rows are showcases too, and **Popular and new** has Top 50 and New Arrivals (newest first).
- **Bigger tiles on Home** (and in Search's rows).
- **Your Library is more compact:** smaller pictures in the list, and three tiles across in the grid.
- **"About this song" shows one Composer line** instead of both "Music director" and "Composer", which were the same person for film songs.
- **Your Library sections are All, Playlists, My Playlists, Movies.** Playlists has the mixes and playlists you saved; My Playlists has Liked songs and the playlists you made.

---

## Server update (2026-09-24): better mood mixes, composer and singer mixes

No app update needed; the friends server changed.

### Improved
- **Chill, Sleep and Focus** no longer share songs: each song goes to the one it fits best. Chill and Sleep only take songs that measure as quiet with soft beats, none of them party songs; Focus takes only songs that clearly sound instrumental.
- **Composer and singer mixes** (like the A.R. Rahman Mix) now have only that composer's or singer's songs. For music that sounds like theirs, use their **Radio**.

### Fixed
- A few songs whose files are longer than their music (the analyzer measured silence at the "middle") looked like the quietest songs in the library and showed up in Chill, Sleep and Focus. Such measurements are now ignored.

---

## 0.6.1 (2026-09-24): Pictures, invites, page colours and a bigger look

### New
- **Profile pictures.** In Settings, tap your picture to **take a photo** or **choose one from your photos** (or remove it). It's cropped to a square and made small on the phone, and your friends see it in their friends list, chats and the share sheet.
- **Group photos.** In a group chat, ⋮ → **Change group photo**. Anyone in the group can change or remove it, and the chat shows who did.
- **Playlist covers.** On a playlist you made, ⋮ → **Change cover** to use a photo instead of the automatic cover (or go back to it). Covers are stored in Navidrome, so they show everywhere, even in other Navidrome apps.
- **Manage your invites.** Friends → Invite now opens **Your invites**: every unused code with when it expires, and buttons to **copy** it, **share** it again or **delete** it (asks first; the code stops working). **New invite** makes another, up to 5 unused at a time, and "3 of 5 left" shows how many you can still make. Used and expired codes from the last month are listed below, with who joined.
- **Colour on every page, like the player.** Movie, playlist, singer and composer pages take a colour from their cover and fade it into the background behind the header. Mixes use their own colour, and Liked songs the brand colour.
- **Your Library as a grid or a list.** The button next to **+** switches between them; the phone remembers your choice. **Swipe left and right** to move between All, Movies and Playlists.
- **"Made for you" says your name.** On Home it's **Made for <your name>**, and mixes picked for your taste say **Made for <your name> · By Isai Pettai** on their page and in Your Library. Mixes everyone gets alike (Top 50, stations) don't.

### Improved
- **Bigger tiles and pictures, closer to Spotify.** Tiles on Home and Search, grid tiles, Your Library pictures, song covers in lists and the big picture on movie, playlist and mix pages are all larger, and section titles are bigger and bold.

### Fixed
- **Every song in Liked songs, and in playlists you made, had a ✓.** It said nothing new there. In Liked songs the ✓ now shows only for songs also in one of your playlists; in your own playlists, only for songs you've also liked or put in another playlist.

### Server
- Unused invites can be deleted (`DELETE /invites/{code}`).
- Mixes say whether they were picked for the person (`personal`).
- Profile pictures (`PUT/DELETE /me/avatar`, `GET /users/{id}/avatar`) and group photos (`/conversations/{id}/picture`), kept as files next to the database.
- Playlist covers (`PUT/DELETE /playlists/{id}/cover`): the server checks you made the playlist, then stores the picture in Navidrome with its admin account, so the phone never needs your Navidrome password for it.

---

## 0.6.0 (2026-09-23): Mixes by Isai Pettai (Phase 4)

### New
- **Made for you, like Spotify.** Home has new rows of mixes, playlists and stations made for you by **Isai Pettai**, the app's own DJ. Everything it makes says **By Isai Pettai** and has its own artwork: the mix's colour, covers from its movies, its name and the Isai Pettai mark.
  - **Daily Mix 1–6:** one for each side of your taste (say, 80s Ilaiyaraaja, Anirudh-era songs, A.R. Rahman melodies). Songs you love mixed with new ones that sound like them. A fresh selection every day.
  - **Discover Weekly:** 30 songs you've never played, picked for how you listen. New every Monday.
  - **On Repeat** (what you've played most this month), **Rewind** (old favourites you haven't played lately), **New Arrivals** (songs just added to the library, the ones that suit you first), **Friends Mix** (what your friends are playing) and **Top 50** (the most played on the server).
  - **Moods and vibes:** Chill, Romance, Feel Good, Party, Kuthu, Sad Songs, Melody, Workout, Devotional, Carnatic Touch, Focus, Sleep and Retro. They come from listening to every song, not from tags, and lean towards what you like. The moods that suit you most come first.
  - **Your composers, Singers you love and Through the decades:** a mix for each, with their best songs and music like theirs.
- **Stations that never end.** Start a radio from any song (⋮ → **Start song radio**, or the radio button in the player), a movie, a composer or a singer (**Radio** on their pages). It plays songs that sound like it and adds more as it goes, even with the screen off.
- **Mixes keep themselves up to date.** When songs are added to the library, the ones that fit a mix join it on their own; mixes also follow what you play, like and skip. Each mix's page says when it last changed.
- **Save mixes to Your Library** with ♡. They keep updating there. Or **⋮ → Save a copy as a playlist** to keep today's songs as a normal playlist ("By Isai Pettai" in its description).
- **Recommended songs** under your own playlists: songs that would fit, each with a **+** to add it. **Refresh** shows others.
- Mixes and stations you play show up in **Recently played**.

### Improved
- **Skips teach the mixes.** The app tells the friends server when you skip a song in its first 30 seconds. Songs you keep skipping stay out of your mixes. (Nothing is reported while listening together or for shared clips.)

### Server
- The friends server makes the mixes. It reads Navidrome's database (read-only) for the library and everyone's plays and likes. Turn it on with `NAVIDROME_DATA` in `.env`; see [server/README.md](server/README.md#mixes-by-isai-pettai-optional).
- New, optional **audio analyzer** ([analyzer/](analyzer/README.md)): listens to every song once with the CLAP music model to find moods and sound-alikes, then only new songs. Without it, mixes use composers, singers, years and listening.
- Lyricists credited as artists in some files no longer count as singers for mixes.
- `./gradlew previewMixes` prints the mixes someone would get, from copies of the databases.

---

## 0.5.4 (2026-09-23): Recently played, resume everywhere and fixes

### New
- **Recently played on Home shows everything.** Songs, movies, playlists, composers (from Shuffle all), artists and Liked songs, newest first. Composers and artists have **round** tiles; songs, movies, playlists and Liked songs are **square**. Tapping a song plays it; anything else opens its page.
- **Resume on movies and Liked songs**, not only playlists: continue from the song you were on, in the same order as before.

### Fixed
- **Changing a read-only playlist failed with "Couldn't change".** Navidrome doesn't allow changing the songs of smart playlists or playlists kept in sync with a playlist file on the server, even for their owner. The app now knows which ones those are: in **Save to** they're greyed out with an explanation, and on their page "Remove from this playlist" is hidden and a note explains why. Renaming, public/private and deleting still work.
- **The player's colour now really matches the cover.** It was using the cover's muted shades, blended only lightly into the background, so a red cover barely looked red. It now takes the cover's main colour (a red cover gives a deep red in dark mode, a soft pink in light mode) and shows it clearly at the top, easing into the background around the controls.

---

## 0.5.3 (2026-09-23): Swipe gestures, cover colours and resume

### New
- **Swipe down to minimize the player.** Drag the full player down and it follows your finger. Let go far enough (or flick) and it tucks back into the mini player; otherwise it springs back. If you've scrolled down to "About this song", swiping scrolls back up first.
- **A background that matches the song.** The full player has a gentle gradient in a muted colour from the cover art. It's picked to suit dark or light mode and fades smoothly when the song changes.
- **Swipe songs to queue them.** In any song list, **swipe right to play next** or **swipe left to add to the end of the queue**. The row springs back and a short message confirms it.
- **Saved marks, like Spotify.** Songs you've liked or added to one of your playlists show a **✓** in lists. Tap it to see where it's saved (Liked songs and which playlists) and change it there.
- **Resume a playlist where you left off.** When you play from a playlist, the app remembers the queue and which song you were on. Next time you open that playlist, **Resume · song name** continues from that song, in the same order as before, so a shuffled order stays the same. Songs added to the playlist since then join the end, and removed ones are skipped. The spot within the song isn't kept; the song starts from the beginning. This is kept on your phone for your 30 most recent playlists and cleared when you log out.

### Improved
- **Recents in Search include what you play.** Playing from a movie, composer or singer page (Play, Shuffle, Shuffle all or a song) now adds it to **Your recent movies** and **Your recent composers and artists**, however you got there, not only from search results.
- **Home** no longer lists every playlist. Playlists are under Search → Playlists and in Your Library.

---

## 0.5.2 (2026-09-23): Artists, playlist editing and live search

### New
- **Artists in Search.** The browse boxes are now a 2×2 grid: Movies, Composers, **Artists** and Playlists. Artists lists every singer A to Z (loading more as you scroll). A singer's page shows the songs they sing on, with Play and Shuffle. Search results show composers and artists separately.
- **Edit your playlists.** On a playlist you made, ⋮ lets you **Rename** it, make it **Public** or **Private**, or **Delete** it. Deleting asks twice, because it can't be undone. Each song's ⋮ menu has **Remove from this playlist**.
- **Save to playlists, like Spotify.** The player has a "playlist +" button next to the ♡ (also **Add to playlist** in any song's ⋮ menu). It opens **Save to**: Liked songs and each of your playlists, ticked where the song already is. Tick or untick and tap **Done** to add or remove it everywhere at once. **New playlist** is at the top.
- **Create a playlist from Your Library** with the **+** at the top. The new playlist opens right away.
- **Live suggestions while typing.** Matching past searches and names from the results appear under the search bar as you type; tap one to search for it. Results also update faster.

---

## 0.5.1 (2026-09-23): Playlists in Search, song details and what's new

### New
- **About this song.** In the full player, scroll down (or tap "About this song ⌄") to see the singers, music director, composer and lyricist (when tagged), movie, year, genre, track, length, audio quality (format, bitrate, sample rate), file size, how often you've played it and when it was added.
- **What's new, in Settings.** The App version card shows the patch notes of the version you're running, or of the new version when there's an update, with **Show more** / **Show less**. The update pop-up shows its notes properly formatted too.
- **Playlists in Search.** Next to Movies and Composers there's a **Playlists** box that opens every playlist on the music server. Below, **Recently played playlists** shows the playlists you last played from (remembered on your phone when you press Play or Shuffle, or tap a song, on a playlist).

### Improved
- **Search remembers what you picked.** Below the Movies, Composers and Playlists boxes, Search now shows **Recently searched songs**, **movies** and **composers**: the ones you played or opened from search results, instead of everything you played. Recently played playlists stay. It's kept on your phone and cleared when you log out.
- **Playlist pages** show who made the playlist, its description, whether it's public or private, and when it was last updated.
- Total playing time is easier to read on playlist, movie and Liked songs pages ("2 hr 15 min", or days for very long playlists), and song counts use thousands separators ("3,264 songs").
- **Your Library** shows who made each playlist. It lists your liked playlists first, then the other playlists you created.

---

## 0.5.0 (2026-09-23): Your Library, likes and a new Search

### New
- **New layout: Home, Search, Your Library, Friends.** Movies and Composers moved into Search, like browsing in Spotify: Search shows a **Movies** box and a **Composers** box, then your **recently played songs, movies and composers** in separate rows. Tapping the search bar shows your recent searches; typing shows results. Back closes the search bar first.
- **Liked songs.** Tap ♡ in the player, or **Like** in a song's ⋮ menu. Liked songs show a small heart in lists, and **Your Library → Liked songs** plays them all (Play or Shuffle).
- **Like whole movies and playlists** with the ♡ next to Play and Shuffle.
- **Your Library** lists Liked songs, your liked movies, and your liked and own playlists, with filters for All, Movies and Playlists.
- Liked songs and movies are saved in Navidrome (its "favourites"), so they're the same on every device and in Navidrome's web page. Navidrome can't like playlists, so liked playlists are saved with your account on the friends server and follow you to every phone (without a friends server they stay on the phone).
- **Know when friends listen together.** When a friend starts listening together in one of your chats, you get a notification ("Alice started listening together. Tap to join") that opens the chat. It only comes when you don't have the app open, at most once per chat every 30 minutes, and it has its own notification category ("Friends listening together") so you can turn it off separately in Android's settings.
- **Search remembers** your searches: tap one to run it again, ✕ to remove it, or Clear. A search is remembered when you press search on the keyboard or open a result. History and recently played songs are kept on your phone and cleared when you log out.

### Improved
- The share sheet lists **Groups** and **People** separately. Groups have a group icon and show their members; people show whether they're online. The chat list uses the same group icon.
- The search box has a ✕ to clear it.

### Server
- Liked playlists are stored per person (new table `liked_playlists`, database version 6): `GET /likes/playlists`, `PUT /likes/playlists`, `DELETE /likes/playlists/{id}`. They're removed with the person's account.
- Starting a new listen-together session sends a `listen` push notification to the chat's other members who don't have the app open (at most once per chat every 30 minutes).

---

## 0.4.2 (2026-09-23): Leaving and deleting groups, sharing recent songs

### New
- **Leave a group.** In a group chat, tap ⋮ → **Leave group**. The others see "… left the group" in the chat. If the group's owner leaves, the longest-standing member becomes the owner; when the last person leaves, the group is deleted.
- **Delete a group for everyone.** The group's owner (whoever created it) can tap ⋮ → **Delete for everyone**. The group and all its messages disappear for every member, and anyone who has it open is taken back to their chats.
- **Share music from a chat.** The music button next to the message box opens a list of what's playing and your recently played songs. Pick one and send it whole, or turn on "Share only a part" to choose a start and end first. Recently played songs are remembered on your phone and cleared when you log out.

### Improved
- The share sheet only lists chats you can still message.

### Server
- New: `POST /conversations/{id}/leave` and `DELETE /conversations/{id}/everyone` (owner only), with a `conversationRemoved` live event.
- Chats carry their owner (`createdBy`); messages can be system lines (`system`), such as "left the group". Leaving or deleting a group also ends its listen-together session.

---

## 0.4.1 (2026-09-23): Feature requests

### New
- **Suggest a feature** from Settings. The bug report card is now **Feedback**, with **Report a bug** and **Suggest a feature**. Both become issues on the app's GitHub page, labelled `bug` or `enhancement`. Feature requests leave out phone details unless you tick the box.

### Server
- `POST /bug-reports` takes an optional `kind` (`bug` or `feature`); older apps keep sending bugs.
- Notification registrations from old app versions (another Firebase project) are forgotten after their first failed delivery, instead of failing on every message.

---

## 0.4.0 (2026-09-23): Listen together, clips, updates and a public release

**One-time step: uninstall the old Isaipetti before installing this version.** It has a new app ID and signing key, so Android treats it as a different app. After installing, log in with your music server and friends server addresses.

### New
- **You enter your servers at login.** The login screen asks for the **Music server** and the **Friends server** (optional when logging in, needed to sign up). No server addresses are built into the app, so it works with anyone's servers. Invites you share now include both addresses, and Settings shows the servers you're using.
- **Listen together** in any chat, group or DM. Tap the headphones at the top of a chat to start a session with what you're playing; others in the chat see "listening together" and can join. Everyone in the session hears the same music, and anyone can play, pause, skip, seek or change the queue for everyone. Leave any time and your music keeps playing on its own. Shuffle is off during a session so everyone hears the same order, and a phone call or unplugged headphones pauses only your phone.
- **Share part of a song.** In the share sheet, turn on "Share only a part" and pick a start and end point with the slider, or tap "Start here" / "End here" while the song plays. Preview it before sending. Friends see a "Clip 1:05–1:35" card that plays just that part; press play again to hear the rest.
- **Updates from inside the app.** The app checks GitHub for a newer version when it starts (every few hours at most) and offers to install it, with the patch notes. Settings shows your version and has "Check for updates". The first time, Android asks you to allow Isaipetti to install apps.
- **Report a bug** from Settings. Describe what happened (optionally with app and phone details) and it becomes an issue on the app's GitHub page. Reports are public but don't show your name.
- **Delete old chats.** A chat with someone who left or is no longer your friend (or a group everyone else left) can be deleted: long-press it in the chat list, or tap "Delete chat" inside it. It's removed for you only.

### Improved
- Screen changes now use the standard Material animation found in most Android apps: the old screen fades out quickly while the new one fades in with a short sideways move. The whole thing takes 0.3 seconds, replacing the slower full-width slide.

### Fixed
- A slow back swipe no longer shrinks the page toward the middle over the previous screen. Slow and fast swipes now play the same animation, following your finger.

### Server
- Listen-together sessions: who's listening in each chat, and the shared queue and playback, relayed live over the WebSocket. Sessions are kept in memory and end when the last listener leaves or goes offline.
- Songs in messages can carry a clip start and end; nonsense ranges are refused. Notifications for clips show the range.
- New: `DELETE /conversations/{id}` for chats you can't message in anymore. It hides the chat and clears its history for you; once nobody who's still around has it, it's removed for good. Opening the DM again later starts it fresh.
- Chats in `GET /conversations` now say whether you can still message in them, and who's listening together.

### Security
- The app's saved logins are no longer included in Android backups.
- New app ID `io.github.devasenan134.isaipetti`, and release builds are signed with a permanent release key. **One-time step: uninstall the old app before installing this version** (Android sees it as a different app). You'll just need to log in again.
- The server refuses oversized requests and messages, and caps listen-together queues at 5,000 songs.
- Bug reports go through the friends server, so the GitHub token never ships inside the app.
- No Firebase settings are built into the app. After login the app fetches them from the friends server (`GET /push/config`), so each friends server uses its own Firebase project.
- The friends server's container runs as a normal user instead of root, and its Firebase files live in a `secrets/` folder.

### Server
- New: `POST /bug-reports`, turned on by setting `GITHUB_REPO` and `GITHUB_TOKEN`. At most 5 reports per person per hour.

### Project
- The project is licensed under GPL-3.0, with a README and the licenses of the bundled fonts and password list.
- Server addresses, Firebase files and signing keys are not in the repository or the app. Only the release signing key is a private build setting.
- The repository has three parts, each with a guide: `navidrome/` (the music server on Docker), `server/` (the friends server on Docker) and `android/` (the app).
- The companion server is now called `isaipetti-social`, and code packages are `io.github.devasenan134.isaipetti` (app) and `io.github.devasenan134.isaipetti.server` (server). Its database file is now `isaipetti-social.db`.

---

## 0.3.3 (2026-09-23): Push notifications and smooth back gesture

### New
- **Push notifications** for new chat messages, friend requests and accepted requests, even when the app is closed or in the background.
- Messages are stacked per chat. There's no notification for the chat you're looking at.
- Tapping a notification opens that chat, or the Friends tab for requests.
- On Android 13 and newer, the app asks for permission to show notifications the first time it opens.

### Fixed
- Going back with the Android back gesture no longer shows two screens faded over each other (for example, a movie page on top of a composer's movie grid). Screens now slide in from the right when opened, and slide away under your finger when you swipe back. Switching tabs still fades.

### Server
- Remembers each phone's notification token and sends notifications through Firebase Cloud Messaging, but only when none of your phones has the app on screen. The app tells the server whether it's on screen, so you still get notified while listening with the app in the background.
- Tokens Firebase rejects (for example, after the app is uninstalled) are forgotten.
- The app registers its token after login and removes it on logout.
- New: `POST /devices` and `POST /devices/remove`. Push is turned off when no Firebase key is configured.

---

## 0.3.2 (2026-09-23): Password rules and rename-safe users

### New
- Password rules for sign-up and password changes: at least 10 characters, not one of about 9,000 most-used passwords, not containing your username, and not overly repetitive.
- A live strength meter as you type a new password.

### Improved
- Existing passwords still log in, even if they don't meet the new rules.

### Server
- The same password rules are checked on the server at sign-up.
- Users are matched by Navidrome's permanent user id instead of their username. An account renamed in Navidrome keeps its friends and chats, and a new account that reuses a deleted username starts fresh. Existing users get their id filled in automatically.
- The server reuses its admin login instead of logging in for every request, to stay under Navidrome's login rate limit.

---

## 0.3.1 (2026-09-23): Settings

### New
- Settings page where you can change your display name.
- Password change. It goes straight from the app to Navidrome and requires your current password. Nothing stores the password, and the friends server never sees it.
- After a password change, the app switches to the new login and signs out your other devices. Devices whose saved login no longer works log out with a note explaining why.

### Improved
- Tapping the tab you're already on returns to that tab's main screen.
- Screens opened from a tab (a movie, a chat, settings) keep that tab highlighted.

### Fixed
- The play queue keeps working after a password change.

### Server
- New: rename (`PATCH /me`) and sign out other devices (`POST /auth/logout-others`).

---

## Server update (2026-09-23): Deleted accounts are cleaned up

Server only, released between 0.3.0 and 0.3.1. No new app version.

- Every 10 minutes the server checks for people whose Navidrome account was deleted and removes them. They lose their friends, sessions, requests and group memberships. Their messages stay, marked "(left)", and their username is freed.
- As a safety check, it skips the run if Navidrome can't be reached or if it would remove more than half the users at once.
- Direct messages to someone who is no longer your friend are refused.

---

## 0.3.0 (2026-09-23): Friends and chat

### New
- **Friends tab** with your chats, friends and friend requests.
- Add friends, and invite new people with invite codes.
- Sign up on the login screen with an invite code, which creates your Navidrome account.
- See which friends are online and what they're listening to, live.
- Direct messages and group chats.
- Share songs into a chat from song menus and from the player.

### Server
- New companion server (`server/`, Kotlin + Ktor + SQLite). It logs you in by checking your details with Navidrome, and handles invites, friend requests, presence, now-playing and chats over a live WebSocket.

---

## 0.2.0 (2026-09-23): Branding

### New
- Brass & night colour palette for light and dark mode, replacing the phone's wallpaper colours.
- New fonts: Bricolage Grotesque for headings and Catamaran for body text.
- New box-with-lid launcher icon.

---

## 0.1.1 (2026-09-23): Player

The first version in git.

### New
- Log in to your Navidrome server.
- Home screen with rows of music, a movies grid, composers, and search.
- Background playback with a play queue.
- Synced lyrics view.
- Scrobbling, so your plays are counted on the server.
- Swipe left or right to change songs in the full player and the mini player.

---

## 0.1.0 (2026-09-23)

The first build. Released before the project was in git, so its changes weren't recorded separately. 0.1.1 has the full feature list.
