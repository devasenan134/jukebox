"""Two people in two browsers: they become friends, chat (messages arrive live), and listen together.

  uv run --with playwright python friends.py http://127.0.0.1:8195 OUT_DIR [CHROME_PATH]
  (users "tester" and "friend" / "secret", as fake_navidrome.py accepts)
"""
import asyncio, sys
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
        errors = []

        async def person(name):
            ctx = await browser.new_context(viewport={"width": 1280, "height": 820})
            page = await ctx.new_page()
            page.on("pageerror", lambda e: errors.append(f"{name}: {e}"))
            await page.goto(BASE + "/")
            await page.fill("input[autocomplete=username]", name)
            await page.fill("input[type=password]", "secret")
            await page.click("button[type=submit]")
            await page.wait_for_selector(".card", timeout=15000)
            return page

        a = await person("tester")
        b = await person("friend")

        async def friends(page):
            await page.locator("a:visible:has-text('Friends')").first.click()
            await page.wait_for_timeout(800)

        # Tester asks; friend accepts.
        await friends(a)
        await a.get_by_role("button", name="Add friend").click()
        await a.locator(".dialog input").fill("friend")
        await a.get_by_role("button", name="Send request").click()
        await a.wait_for_timeout(1000)
        await friends(b)
        await b.locator(".content").get_by_role("button", name="Friends").click()
        await b.wait_for_timeout(800)
        await b.get_by_role("button", name="Accept").click()
        await b.wait_for_timeout(1000)
        check(await b.get_by_text("tester", exact=True).count() > 0, "friend request accepted")
        await b.screenshot(path=str(OUT / "1-friends.png"))

        # Tester opens the chat from the friends list and says hello; it shows up for friend without reloading.
        await a.locator(".content").get_by_role("button", name="Friends").click()
        await a.wait_for_timeout(800)
        await a.locator(".content .list-row:has-text('friend')").first.click()
        await a.wait_for_selector("[aria-label=Message]")
        await a.fill("[aria-label=Message]", "hello from tester")
        await a.keyboard.press("Enter")
        await a.wait_for_timeout(1500)
        await b.locator(".content").get_by_role("button", name="Chats").click()
        await b.wait_for_timeout(1000)
        check(await b.get_by_text("hello from tester").count() > 0, "the chat list shows the new message")
        await b.locator("[data-testid=chat-row]").first.click()
        await b.wait_for_selector("[aria-label=Message]")
        await b.fill("[aria-label=Message]", "hi back")
        await b.keyboard.press("Enter")
        await a.wait_for_timeout(2000)
        check(await a.get_by_text("hi back").count() > 0, "a reply arrives live in the open chat")
        await a.screenshot(path=str(OUT / "2-chat.png"))

        # Listen together: tester starts a jam and plays an album; friend joins and hears the same song.
        await a.get_by_role("button", name="Listen together").click()
        await a.wait_for_timeout(800)
        await a.locator("a:visible:has-text('Home')").first.click()
        await a.wait_for_timeout(1000)
        await a.locator(".card .name").first.click()
        await a.wait_for_selector(".fab-play")
        await a.click(".fab-play")
        await a.wait_for_timeout(2000)
        await b.get_by_role("button", name="Join jam").click()
        await b.wait_for_timeout(3000)
        title_a = await a.locator(".player-bar .now .body-medium").first.inner_text()
        title_b = (await b.locator(".player-bar .now .body-medium").first.inner_text()) if await b.locator(".player-bar .now .body-medium").count() else ""
        check(title_a and title_a == title_b, f"in the jam both hear the same song ({title_a!r} / {title_b!r})")
        await b.screenshot(path=str(OUT / "3-jam.png"))

        check(not errors, "no page errors: " + "; ".join(errors[:3]))
        await browser.close()
    print("PROBLEMS:" if problems else "ALL GOOD", *problems, sep="\n  ")
    sys.exit(1 if problems else 0)


asyncio.run(main())
