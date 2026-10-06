# The Android app

Kotlin with Jetpack Compose and Media3. The app connects to `jukebox.craftingtable.cc` unless you change the
server under **Additional settings** on the login screen, so it works with anyone's Jukebox. Firebase settings
for notifications come from the server; none are built in.

## Install (for friends)

1. Download the newest `isaipetti-<version>.apk` from the repository's [Releases](../../../releases) and open it on your phone. Allow installing from your browser or file manager when Android asks.
2. Open the app and log in, or tap **Got an invite code? Sign up** with the code a friend sent you. Someone
   running their own Jukebox types its address under **Additional settings** first.

The app checks Releases for new versions and offers to install them (*Settings → App version* checks manually). The first time, Android asks you to allow Isaipetti to install apps.

## Build it yourself

Requires JDK 17+ and the Android SDK (Android Studio installs both).

```bash
cd android
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Debug builds may use plain `http://` servers, handy for a local test server (`http://10.0.2.2:8095` from the Android emulator). Release builds require HTTPS.

### Signed release builds

Android only installs an update if it's signed with the same key as the installed app, so create one key and keep it forever (back it up; if it's lost, everyone has to reinstall):

```bash
keytool -genkeypair -keystore ~/keys/isaipetti-release.jks -storetype PKCS12 -alias isaipetti \
  -keyalg RSA -keysize 4096 -validity 36500
```

Tell Gradle where it is in `~/.gradle/gradle.properties`, outside the repository:

```properties
ISAIPETTI_KEYSTORE=/home/you/keys/isaipetti-release.jks
ISAIPETTI_KEYSTORE_PASSWORD=...
ISAIPETTI_KEY_ALIAS=isaipetti
```

Then `./gradlew assembleRelease` builds `app/build/outputs/apk/release/app-release.apk`.

### If you publish your own version

- Change `applicationId` in `app/build.gradle.kts` so it doesn't clash with this app, and register that id in your Firebase project (see `../server/README.md`).
- Set `ISAIPETTI_GITHUB_REPO=owner/repo` in `~/.gradle/gradle.properties` so the update check looks at your Releases.

## Where things are

```
app/src/main/java/io/github/devasenan134/isaipetti/
  data/        the server's Subsonic and friends APIs, saved login, updates
  playback/    background player, listen-together sync
  social/      friends, chats and listen-together state
  push/        notifications
  ui/          screens (Compose)
```
