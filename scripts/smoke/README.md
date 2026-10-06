# Smoke test

Runs Jukebox locally on a tiny generated library and walks the web app in a headless browser, failing on
page crashes, blank screens or a missing mini player.

```bash
scripts/smoke/make_library.sh /tmp/jb/music                      # three films, covers, synced lyrics (needs ffmpeg)
(cd server && ./gradlew installDist) && (cd web && npm ci && npm run build)
for u in tester friend; do                                        # the two people the scripts sign in as
  DB_PATH=/tmp/jb/jukebox.db JUKEBOX_PASSWORD=smoke-test-secret server/build/install/jukebox/bin/jukebox user add $u
done
PORT=8195 DB_PATH=/tmp/jb/jukebox.db LIBRARIES="test=/tmp/jb/music:film:tamil" \
  WEB_DIR=web/dist FINGERPRINTS=off server/build/install/jukebox/bin/jukebox &
uv run --with playwright python scripts/smoke/smoke.py http://127.0.0.1:8195 /tmp/jb/shots
```

`friends.py` signs two people in (users `tester` and `friend`) in two browsers: they become friends, chat
(messages arrive live), and listen together (both players end up on the same song).

```bash
uv run --with playwright python scripts/smoke/friends.py http://127.0.0.1:8195 /tmp/jb/friends
```
