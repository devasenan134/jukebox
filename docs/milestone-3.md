# Milestone 3: Realtime gateway

> In progress. How Jukebox works today is in [ARCHITECTURE.md](ARCHITECTURE.md); design goals are in
> [DESIGN.md](DESIGN.md).

The realtime gateway gives every device one live connection to Jukebox: presence, playback state across your
devices ("playing on your phone, controlled from your laptop"), jams and notifications. It also puts the social
features behind a setting, so Jukebox can run as a standalone music server.

---

## 1. Why a realtime gateway?

Today, `Hub.kt` maintains a WebSocket connection (`/ws?token=...`) primarily for chat, presence, and chat-based
listening together. However:

1. **Connections are not device-aware**: the server tracks only `userId: Long`. If you open Jukebox on your phone,
   tablet, and laptop, the server treats them as identical anonymous sessions.
2. **No playback sync across your own devices**: when music is playing on your phone, the web app or Mac app has no
   idea what is playing and cannot control it (pause, skip, adjust volume, or take over playback).
3. **Jams are tied to group chats**: listening together requires opening a chat conversation rather than starting an
   independent listening session with friends.
4. **Social features are hardwired**: self-hosters wanting a private, standalone music server cannot turn off the
   social layer.

Milestone 3 evolves the hub into a unified **Realtime Gateway**.

```
                ┌────────────────────────────────────────────────────────┐
                │               Jukebox Realtime Gateway                 │
                └──────────────────────────┬─────────────────────────────┘
                                           │ WebSocket (/ws)
                  ┌────────────────────────┼────────────────────────┐
                  ▼                        ▼                        ▼
       Android (Phone)            Web App (Laptop)          Desktop (Mac)
       device: "Pixel 8"          device: "Chrome / macOS"  device: "Mac mini"
       role: Active Player        role: Remote Controller   role: Idle
```

---

## 2. Device identity and sessions

### Handshake

When a client connects to `/ws`, it passes its authentication token and device metadata:

```
GET /ws?token=<session_token>&device_id=<uuid>&device_name=Pixel+8&client_type=android
```

- `device_id`: Stable client-generated UUID (persisted in local storage or app settings).
- `device_name`: Human-friendly name ("Pixel 8", "Living Room Mac", "Chrome on Linux").
- `client_type`: `android`, `web`, `desktop`, `other`.

**Backwards compatibility**: If an older client connects without `device_id` (e.g. app ≤ 0.13.0), the server assigns
a synthetic device ID (`legacy-<hash>`) and default name, preserving existing functionality.

### In-memory state

The gateway maintains the live device registry in memory:

```kotlin
data class DeviceSession(
    val deviceId: String,
    val userId: Long,
    val deviceName: String,
    val clientType: String,
    val session: WebSocketSession,
    var visible: Boolean = true,
    var playback: DevicePlayback? = null,
    val connectedAt: Long = System.currentTimeMillis(),
    var lastActiveAt: Long = System.currentTimeMillis(),
)
```

---

## 3. Playback state across devices (Remote control)

### Device playback state

An active player periodically reports or pushes playback updates:

```kotlin
@Serializable
data class DevicePlayback(
    val song: SongRef?,
    val queue: List<SongRef>? = null,
    val queueId: String? = null,
    val index: Int = 0,
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val volume: Float = 1.0f,
    val updatedAt: Long = System.currentTimeMillis(),
)
```

Whenever a user's device changes playback state, the server broadcasts the updated device list to all of that user's
connected devices:

```json
{
  "type": "devices",
  "activeDeviceId": "dev-phone-123",
  "devices": [
    {
      "id": "dev-phone-123",
      "name": "Pixel 8",
      "type": "android",
      "playing": true,
      "song": { "id": "rec-456", "title": "Nenjukkul Peidhidum" },
      "positionMs": 42000
    },
    {
      "id": "dev-laptop-789",
      "name": "Chrome on MacBook",
      "type": "web",
      "playing": false
    }
  ]
}
```

### Remote commands

A secondary device (e.g. the web app) can send commands targeting the active player:

- **`remoteControl`**:
  ```json
  { "type": "remoteControl", "targetDeviceId": "dev-phone-123", "action": "pause" }
  ```
  Supported actions: `play`, `pause`, `next`, `previous`, `seek` (`positionMs`), `setVolume` (`volume`).
- **`transferPlayback`**:
  ```json
  { "type": "transferPlayback", "fromDeviceId": "dev-phone-123", "toDeviceId": "dev-laptop-789" }
  ```
  Transfers current queue, song index, and playback position to the new target device, pausing the old one and
  starting playback on the new one.

---

## 4. Presence and friends

- **Aggregated online state**: A user is online if they have at least one device connected to the gateway.
- **Aggregated now playing**: Derived from the user's current active player. If the user is playing on their phone,
  their profile status and friend presence reflect the track automatically.
- **Friend broadcasts**: Friends receive standard `PresenceEvent(userId, online, nowPlaying)`, maintaining seamless
  compatibility with the existing friends list.

---

## 5. Listening together (Jams)

Jams evolve to become independent sessions:

- A jam has a unique session code/id.
- Jams can still be linked to a chat room (`conversationId`), but can also exist as a standalone session.
- The host's active device drives the jam state; listeners sync their active player to the host's queue and position.
- Permissions: host controls playback and queue; listeners can submit song requests.

---

## 6. Unified in-app notifications

All instant events travel across the gateway:

- Chat messages and typing indicators
- Direct mentions (`@username`)
- Friend requests and accepts
- Jam invitations and song requests
- Server announcements

When a user has no active visible device sessions, the server routes notifications through Firebase Cloud Messaging
(if configured).

---

## 7. The social module behind a setting

In `server/.env`:

```env
JUKEBOX_SOCIAL=true   # default: true. Set to false for a private, standalone music server.
```

When `JUKEBOX_SOCIAL=false`:
- The realtime gateway stays enabled, but handles only personal device synchronization, remote control, and user
  settings.
- Friends, chats, message push, invites, and public playlist social interactions are turned off.
- The web app and Android app hide social tabs (Friends, Chat) and display a streamlined music-only interface.

---

## 8. Implementation steps

1. **Gateway protocol & device registry (`server/`)**:
   - Extend `/ws` endpoint with device handshake query parameters (`deviceId`, `deviceName`, `clientType`).
   - Add `DeviceSession` tracking to `Hub.kt` with thread-safe session maps.
   - Implement `DevicesEvent` and `DevicePlaybackUpdate` events.
2. **Remote playback dispatch (`server/`)**:
   - Add `RemoteCommand` and `TransferPlayback` routing between sessions of the same user.
   - Connect active device playback to user presence.
3. **Web app client integration (`web/`)**:
   - Persistent `deviceId` generation in `localStorage`.
   - Pass device metadata when opening `/ws`.
   - Add Device Picker UI in the player bar ("Listening on Phone" / "Play on this device").
   - Handle remote control commands if the web app is the active player.
4. **Android app client integration (`android/`)**:
   - Pass Android `deviceId` and device model in WebSocket URL.
   - Handle remote control events (`play`, `pause`, `seek`, `next`, `previous`) in background playback service.
5. **Standalone mode (`Config.kt`, `Main.kt`)**:
   - Add `socialEnabled: Boolean` to configuration.
   - Guard social endpoints and conditionally advertise social features.
6. **Tests**:
   - Unit and integration tests for multi-device registration, remote command routing, and playback handoff.
