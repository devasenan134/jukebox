# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27"]
# ///
"""fulfil.py cleans song names as the server does (Titles.kt): both are checked against title-cases.tsv.
Run: uv run --script scripts/requests/test_titles.py"""
import importlib.util
from pathlib import Path

here = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("fulfil", here / "fulfil.py")
fulfil = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fulfil)

failed = 0
cases = [line.split("\t") for line in (here / "title-cases.tsv").read_text(encoding="utf-8").splitlines() if line.strip() and not line.startswith("#")]
for raw, title, *film in cases:
    want = (title, (film[0] if film else "") or None)
    got = fulfil.song_name(raw)
    if got != want:
        failed += 1
        print(f"✗ {raw!r}: {got} (want {want})")
# What "already there" makes of them: another way of writing the same song is it, another version isn't.
checks = [(fulfil.has_song(["Hukum Reloaded - Tamil"], "Hukum Reloaded"), True), (fulfil.has_song(["Kanmani"], "Kanmani (Reprise)"), False),
          (fulfil.has_song(["Nenjorathil"], "Nenjorathil (Female)"), False), (fulfil.has_song(["Hukum - Thalaivar Alappara"], "Hukum"), True)]
for n, (got, want) in enumerate(checks):
    if got != want:
        failed += 1
        print(f"✗ has_song check {n}: {got}")
print(f"{len(cases) + len(checks) - failed} of {len(cases) + len(checks)} passed")
raise SystemExit(1 if failed else 0)
