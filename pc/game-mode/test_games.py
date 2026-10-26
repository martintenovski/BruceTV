"""python test_games.py: checks the folder game finder on a made-up tree."""
import os
import tempfile

import games


def touch(path, size=2_000_000):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\0" * size)


with tempfile.TemporaryDirectory() as root:
    touch(os.path.join(root, "Hades", "Hades.exe"))
    touch(os.path.join(root, "Hades", "unins000.exe"))
    touch(os.path.join(root, "Hades", "vc_redist.x64.exe"))
    touch(os.path.join(root, "Jackbox", "The.Jackbox.Party.Pack.3", "The.Jackbox.Party.Pack.3", "Pack3.exe"))
    touch(os.path.join(root, "Jackbox", "The.Jackbox.Party.Pack.8", "The Jackbox Party Pack 8", "Pack8.exe"))
    touch(os.path.join(root, "Tools", "notes.txt"), 10)
    touch(os.path.join(root, "Tiny Game", "tiny.exe"), 5000)  # under the size floor: not a game for the automatic scan
    found = {g["name"]: os.path.basename(g["path"]) for g in games.find_games(root)}
    assert found == {"Hades": "Hades.exe", "The Jackbox Party Pack 3": "Pack3.exe", "The Jackbox Party Pack 8": "Pack8.exe"}, found

    best = games.exes(os.path.join(root, "Hades"))
    assert [e["name"] for e in best] == ["Hades.exe"], best
    assert games.exes(os.path.join(root, "Tiny Game"))[0]["name"] == "tiny.exe"  # a manual pick has no size floor

    d = games.list_dir(root)
    assert d["dirs"] == ["Hades", "Jackbox", "Tiny Game", "Tools"] and d["files"] == [], d

with tempfile.TemporaryDirectory() as pn:
    touch(os.path.join(pn, "Playnite.DesktopApp.exe"), 10)
    assert os.path.basename(games.playnite_exe(pn)) == "Playnite.DesktopApp.exe"
    touch(os.path.join(pn, "Playnite.FullscreenApp.exe"), 10)
    assert games.playnite_dir(pn) == pn and os.path.basename(games.playnite_exe(pn)) == "Playnite.FullscreenApp.exe"
    assert games.playnite_dir(os.path.join(pn, "Playnite.FullscreenApp.exe")) == pn  # picking the exe itself works too

assert games.clean("The.Jackbox.Party.Pack.3") == "The Jackbox Party Pack 3"
assert games.clean("Half-Life 2.5") == "Half-Life 2.5"
print("ok", len(games.drives()), "drives,", len(games.gog_games()), "GOG,", len(games.ubisoft_games()), "Ubisoft,", games.steam_root())
