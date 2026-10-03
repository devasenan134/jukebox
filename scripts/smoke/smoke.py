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
            check(await page.locator("button[aria-label='Next']").count() > 0, "mini player shows after pressing Play")
            await shot("3-playing")

            # A song row: click the second song.
            rows = page.locator(".song-row")
            if await rows.count() > 1:
                await rows.nth(1).click()
                await page.wait_for_timeout(1000)
                check(len(await visible_text()) > 20, "page still shows content after picking a song")

            # The full player with lyrics.
            await page.locator("[aria-label='Next']").first.locator("xpath=ancestor::div[2]").click(position={"x": 60, "y": 20})
            await page.wait_for_timeout(1200)
            check(await page.get_by_text("Now playing").count() > 0, "full player opens from the mini player")
            await shot("4-now-playing")
            await page.keyboard.press("Escape")
            await page.wait_for_timeout(500)

            for tab, step in (("Search", "5-search"), ("Albums", "6-albums"), ("Music directors", "7-composers")):
                t = time.time()
                await page.get_by_role("link", name=tab).click()
                await page.wait_for_timeout(1500)
                if tab == "Search":
                    await page.fill(".search-box input", "kariga")
                    await page.wait_for_timeout(1500)
                    check(await page.get_by_text("Kaariga").count() > 0, "search forgives spelling (kariga finds Kaariga)")
                check(len(await visible_text()) > 20, f"{tab} shows content ({time.time() - t:.1f}s)")
                await shot(step)

            await page.get_by_text("Ilaiyaraaja").first.click()
            await page.wait_for_timeout(1500)
            check(await page.get_by_text("Mouna Raagam").count() > 0, "a music director's page lists their albums")
            await shot("8-composer")

            check(not errors, "no page errors: " + "; ".join(errors[:3]))
            await page.close()
        await browser.close()
    print("PROBLEMS:" if problems else "ALL GOOD", *problems, sep="\n  ")
    sys.exit(1 if problems else 0)


asyncio.run(main())
