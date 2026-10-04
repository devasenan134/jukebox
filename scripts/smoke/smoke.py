"""Smoke test for the Jukebox web app: signs in, walks every screen, plays music, and fails on any page
crash, console error, blank screen or missing mini player. Screenshots go to the output folder.

  uv run --with playwright python smoke.py http://127.0.0.1:8195 OUT_DIR [CHROME_PATH]
  (user "tester" / "secret", as fake_navidrome.py accepts)
"""
import asyncio, sys, time
from pathlib import Path
from playwright.async_api import async_playwright

BASE, OUT = sys.argv[1].rstrip("/"), Path(sys.argv[2])
CHROME = sys.argv[3] if len(sys.argv) > 3 else None
problems = []


def check(ok, what):
    print(("  ok   " if ok else "  FAIL ") + what)
    if not ok:
        problems.append(what)


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch(executable_path=CHROME) if CHROME else await p.chromium.launch()
        for name, size in (("desktop", (1280, 820)), ("phone", (390, 844))):
            print(name)
            page = await browser.new_page(viewport={"width": size[0], "height": size[1]})
            errors = []
            page.on("pageerror", lambda e: errors.append(f"pageerror: {e}"))
            page.on("console", lambda m: m.type == "error" and errors.append(f"console: {m.text[:200]}"))

            async def shot(step):
                await page.screenshot(path=str(OUT / f"{name}-{step}.png"))

            async def visible_text():
                return (await page.evaluate("document.getElementById('root').innerText")).strip()

            t = time.time()
            await page.goto(BASE + "/", wait_until="load")
            await page.wait_for_selector("input[type=password]")
            await page.fill("input[autocomplete=username]", "tester")
            await page.fill("input[type=password]", "secret")
            await page.click("button[type=submit]")
            await page.wait_for_selector(".card", timeout=15000)
            check(True, f"signed in, Home with albums in {time.time() - t:.1f}s")
            await shot("1-home")

            t = time.time()
            await page.locator(".card").first.click()
            await page.wait_for_selector(".song-row, .fab-play", timeout=10000)
            check(True, f"album opened in {time.time() - t:.1f}s")
            await shot("2-album")

            await page.click(".fab-play")
            await page.wait_for_timeout(1500)
            check(len(await visible_text()) > 20, "page still shows content after pressing Play")
            check(await page.locator("button[aria-label='Next']:visible").count() > 0, "mini player shows after pressing Play")
            await shot("3-playing")

            # A song row: click the second song.
            rows = page.locator(".song-row")
            if await rows.count() > 1:
                await rows.nth(1).click()
                await page.wait_for_timeout(1000)
                check(len(await visible_text()) > 20, "page still shows content after picking a song")

            # The full player with lyrics: from the player bar on desktop, the mini player on a phone.
            if name == "desktop":
                await page.locator(".player-bar [aria-label='Open the player']").click()
            else:
                await page.locator(".mini-slot [aria-label='Next']").locator("xpath=ancestor::div[2]").click(position={"x": 60, "y": 20})
            await page.wait_for_timeout(1200)
            check(await page.get_by_text("PLAYING FROM").count() > 0, "full player opens from the mini player")
            await shot("4-now-playing")
            await page.keyboard.press("Escape")
            await page.wait_for_timeout(500)

            async def tab(label):
                # The bottom tabs on a phone, the side panel on desktop.
                await page.locator(f"a:visible:has-text('{label}')").first.click()
                await page.wait_for_timeout(1500)

            t = time.time()
            await tab("Search")
            await page.fill(".search-box input", "kariga")
            await page.wait_for_timeout(1500)
            check(await page.get_by_text("Kaariga").count() > 0, "search forgives spelling (kariga finds Kaariga)")
            check(len(await visible_text()) > 20, f"Search shows content ({time.time() - t:.1f}s)")
            await shot("5-search")

            for chip, step in (("Albums", "6-albums"), ("Music directors", "7-composers")):
                await tab("Home")
                await page.locator(".content").get_by_role("button", name=chip, exact=True).click()
                await page.wait_for_timeout(1500)
                check(len(await visible_text()) > 20, f"{chip} shows content")
                await shot(step)

            await page.get_by_text("Ilaiyaraaja").first.click()
            await page.wait_for_timeout(1500)
            check(await page.get_by_text("Mouna Raagam").count() > 0, "a music director's page lists their albums")
            await shot("8-composer")

            # Like the album, like its first song, and save the song to a new playlist.
            playlist = f"Road trip {name}"
            await page.get_by_text("Mouna Raagam").first.click()
            await page.wait_for_selector(".song-row", timeout=10000)
            # (The phone run is the same user: already liked there.)
            if await page.locator(".hero-bar button[aria-label='Like']").count():
                await page.locator(".hero-bar button[aria-label='Like']").first.click()
            await page.locator(".song-row button[aria-label='More']").first.click()
            like = page.get_by_text("Like", exact=True)
            await (like.click() if await like.count() else page.keyboard.press("Escape"))
            await page.wait_for_timeout(500)
            await page.locator(".song-row button[aria-label='More']").first.click()
            await page.get_by_text("Add to playlist", exact=True).click()
            await page.get_by_text("New playlist", exact=True).click()
            await page.locator(".field input").last.fill(playlist)
            await page.get_by_role("button", name="Create").click()
            await page.wait_for_timeout(800)
            await page.get_by_role("button", name="Done").click()
            await page.wait_for_timeout(800)

            await tab("Your Library")
            text = await visible_text()
            check("Liked songs" in text and "Mouna Raagam" in text and playlist in text,
                  "Your Library has Liked songs, the liked album and the new playlist")
            await shot("9-library")

            await page.get_by_text(playlist, exact=True).first.click()
            await page.wait_for_timeout(1500)
            check(await page.locator(".song-row").count() == 1, "the new playlist has the song")
            await page.locator(".fab-play").click()
            await page.wait_for_timeout(1000)
            await shot("10-playlist")

            await tab("Your Library")
            await page.get_by_text("Liked songs", exact=True).first.click()
            await page.wait_for_timeout(1500)
            check(await page.locator(".song-row").count() == 1, "Liked songs has the liked song")
            await shot("11-liked")

            await tab("Home")
            text = await visible_text()
            check("Recently played" in text and "Jump back in" in text, "Home shows Recently played and Jump back in")
            await shot("12-home-after")

            check(not errors, "no page errors: " + "; ".join(errors[:3]))
            await page.close()
        await browser.close()
    print("PROBLEMS:" if problems else "ALL GOOD", *problems, sep="\n  ")
    sys.exit(1 if problems else 0)


asyncio.run(main())
