"""Makes web/public/fonts/material-symbols-rounded.woff2: the Material Symbols Rounded font (Apache 2.0) with
only the icons the web app uses, served by Jukebox itself instead of Google Fonts.

Every word in src/ that is also an icon name is kept (a few extra don't hurt), so icons picked in code
(`playing ? 'pause' : 'play_arrow'`) are found too. Run it after using a new icon:

    python3 web/scripts/icons.py
"""
import re, urllib.parse, urllib.request
from pathlib import Path

WEB = Path(__file__).resolve().parent.parent
CODEPOINTS = "https://raw.githubusercontent.com/google/material-design-icons/master/variablefont/MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.codepoints"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"}


def get(url: str) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()


known = {line.split()[0] for line in get(CODEPOINTS).decode().splitlines() if line.strip()}
words = set()
for f in (WEB / "src").rglob("*.ts*"):
    words |= set(re.findall(r"\b[a-z][a-z0-9_]{1,}\b", f.read_text()))
icons = sorted(words & known)
css = get("https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@20..48,400,0..1,0"
          f"&icon_names={','.join(icons)}&display=block").decode()
font = re.search(r"url\((https://[^)]+)\)", css).group(1)
out = WEB / "public/fonts/material-symbols-rounded.woff2"
out.write_bytes(get(font))
print(f"{len(icons)} icons, {out.stat().st_size // 1024} KB -> {out.relative_to(WEB)}")
