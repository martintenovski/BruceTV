"""Finding the games installed on this PC, for Game Mode.

Launcher libraries that keep a registry list (GOG, Ubisoft Connect) are read directly. Everything else
(EA app, Battle.net, cracked/portable installs, a D:\\Games folder...) is found by looking through folders for
the exe that starts each game. The same folder helpers drive the on-screen file browser.
"""
import ctypes
import os
import re
import string
import time

try:
    import winreg
except ImportError:  # not Windows: the registry readers just find nothing
    winreg = None

EXT = (".exe", ".lnk", ".bat", ".cmd", ".url")
SKIP_DIRS = {"$recycle.bin", "system volume information", "windows", "programdata", "appdata", "windowsapps", "recovery",
             "msocache", "perflogs", "node_modules", ".git", "steamapps", "xboxgames", "epic games", "drivers",
             "winsxs", "$windows.~bt", "config.msi", "__pycache__"}
# installers, redistributables, crash reporters and anti-cheat stubs are never "the game"
BAD_EXE = re.compile(r"unins|uninst|setup|install|redist|vcredist|vc_redist|dxsetup|dotnet|crash|updater|report|"
                     r"easyanticheat|eac_launcher|beservice|cleanup|prereq|notification|cef|webhelper|7z", re.I)
LIBRARY_DIRS = ["Games", "Game", "GOG Games", "PC Games", "My Games"]  # looked for at the top of every drive
LIBRARY_PATHS = [r"C:\Program Files\EA Games", r"C:\Program Files (x86)\Origin Games", r"C:\Program Files\Ubisoft\Ubisoft Game Launcher\games",
                 r"C:\Program Files (x86)\Ubisoft\Ubisoft Game Launcher\games", r"C:\Program Files (x86)\GOG Galaxy\Games"]


MIN_GAME = 150_000  # smaller programs are tools, not games (old and Unity games can have quite small exes)


class Cancel(Exception):
    pass


def norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def clean(name):
    """'The.Jackbox.Party.Pack.3' -> 'The Jackbox Party Pack 3' (dots are separators only when there are no spaces)."""
    if " " not in name:
        name = name.replace(".", " ").replace("_", " ")
    return re.sub(r"\s+", " ", name).strip()


def folder_name(path):
    return os.path.basename(os.path.normpath(path)) or path


# ---------- drives and folders ----------

def drives():
    """Fixed drives: [{"path": "D:\\", "name": "Games (D:)", "free": bytes}]."""
    out = []
    try:
        mask = ctypes.windll.kernel32.GetLogicalDrives()
    except AttributeError:
        return out
    for i, letter in enumerate(string.ascii_uppercase):
        if not mask & (1 << i):
            continue
        root = letter + ":\\"
        if ctypes.windll.kernel32.GetDriveTypeW(root) != 3:  # DRIVE_FIXED
            continue
        label = ctypes.create_unicode_buffer(256)
        ctypes.windll.kernel32.GetVolumeInformationW(root, label, 256, None, None, None, None, 0)
        free = ctypes.c_ulonglong(0)
        ctypes.windll.kernel32.GetDiskFreeSpaceExW(root, None, None, ctypes.byref(free))
        out.append({"path": root, "name": f"{label.value or 'Local Disk'} ({letter}:)", "free": free.value})
    return out


def list_dir(path):
    """Sub-folders and launchable files of one folder, for the browser. Folders first, both sorted by name."""
    dirs, files = [], []
    try:
        with os.scandir(path) as it:
            for e in it:
                try:
                    if e.name.startswith("$") or e.name.lower() in ("system volume information",):
                        continue
                    if e.is_dir():
                        dirs.append(e.name)
                    elif e.name.lower().endswith(EXT):
                        files.append(e.name)
                except OSError:
                    pass
    except OSError:
        return None
    key = str.lower
    return {"path": path, "dirs": sorted(dirs, key=key)[:600], "files": sorted(files, key=key)[:600]}


def exes(folder, maxdepth=2, min_size=0):
    """Programs inside a folder that could start the game, best guess first: [{"path", "name", "size", "match"}] (match: named like the folder).
    Installers and crash reporters are left out; a name like the folder's wins, then shallower, then bigger."""
    key = norm(folder_name(folder))
    found = []

    def walk(d, depth):
        try:
            entries = list(os.scandir(d))
        except OSError:
            return
        for e in entries:
            try:
                if e.is_file() and e.name.lower().endswith(".exe") and not BAD_EXE.search(e.name):
                    size = e.stat().st_size
                    if size >= min_size:
                        found.append((e.path, size, depth))
                elif e.is_dir() and depth < maxdepth and e.name.lower() not in SKIP_DIRS:
                    walk(e.path, depth + 1)
            except OSError:
                pass

    walk(folder, 0)

    def matches(path):
        stem = norm(os.path.splitext(os.path.basename(path))[0])
        return bool(key and stem and (stem in key or key in stem))

    found.sort(key=lambda f: (0 if matches(f[0]) else 1, f[2], -f[1]))
    return [{"path": p, "name": os.path.basename(p), "size": s, "match": matches(p)} for p, s, _ in found]


def find_games(root, depth=3, tick=None):
    """Games below a folder that holds games: each sub-folder with a program in it is one game; a sub-folder
    without one is treated as a container (a "Jackbox" folder full of party packs) and searched one level deeper."""
    out = []
    try:
        subs = [e for e in os.scandir(root) if e.is_dir() and e.name.lower() not in SKIP_DIRS and not e.name.startswith("$")]
    except OSError:
        return out
    for e in subs:
        if tick:
            tick(e.path)
        direct = exes(e.path, 0, MIN_GAME)
        if direct:
            out.append({"name": clean(e.name), "path": direct[0]["path"]})
        elif depth > 1:
            inner = find_games(e.path, depth - 1, tick)
            if len(inner) == 1:  # one game in a wrapper folder (bin\, x64\...): the wrapper's name is the game's
                inner[0]["name"] = clean(e.name)
            out += inner
    return out


def library_roots(extra=()):
    """Folders that probably hold games: well-known launcher folders, a Games folder on each drive, and the user's own."""
    roots = [p for p in LIBRARY_PATHS if os.path.isdir(p)]
    for d in drives():
        roots += [os.path.join(d["path"], n) for n in LIBRARY_DIRS if os.path.isdir(os.path.join(d["path"], n))]
    roots += [p for p in extra if os.path.isdir(p)]
    seen, out = set(), []
    for r in roots:
        k = os.path.normcase(os.path.normpath(r))
        if k not in seen:
            seen.add(k)
            out.append(r)
    return out


def scan_folders(extra_roots=(), known=(), tick=None):
    """Games found in the library folders that are not in `known` (a list of names and paths already in the menu)."""
    skip = {norm(k) for k in known}
    out = []
    for root in library_roots(extra_roots):
        for g in find_games(root, 3, tick):
            if norm(g["name"]) in skip or norm(g["path"]) in skip:
                continue
            skip.add(norm(g["name"]))
            out.append(g)
    return out


def search_folders(query, tick=None, limit=30, seconds=30):
    """Folders whose name contains every word of the query, nearest to the top of each drive first."""
    words = [norm(w) for w in query.split() if norm(w)]
    if not words:
        return []
    out, deadline = [], time.time() + seconds
    queue = [d["path"] for d in drives()]
    depth = 0
    while queue and depth < 5 and len(out) < limit and time.time() < deadline:
        nxt = []
        for d in queue:
            if tick:
                tick(d)
            try:
                entries = list(os.scandir(d))
            except OSError:
                continue
            for e in entries:
                try:
                    if not e.is_dir() or e.name.lower() in SKIP_DIRS or e.name.startswith("$"):
                        continue
                except OSError:
                    continue
                n = norm(e.name)
                if all(w in n for w in words):
                    out.append({"name": e.name, "path": e.path})
                nxt.append(e.path)
        queue, depth = nxt, depth + 1
    return out[:limit]


# ---------- launcher libraries kept in the registry ----------

def reg_values(root, path, view=0):
    """{name: value} of one key, or None if it doesn't exist."""
    if not winreg:
        return None
    try:
        with winreg.OpenKey(root, path, 0, winreg.KEY_READ | view) as k:
            out = {}
            for i in range(winreg.QueryInfoKey(k)[1]):
                n, v, _ = winreg.EnumValue(k, i)
                out[n] = v
            return out
    except OSError:
        return None


def reg_subkeys(root, path, view=0):
    if not winreg:
        return []
    try:
        with winreg.OpenKey(root, path, 0, winreg.KEY_READ | view) as k:
            return [winreg.EnumKey(k, i) for i in range(winreg.QueryInfoKey(k)[0])]
    except OSError:
        return []


def steam_root():
    v = reg_values(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") if winreg else None
    p = (v or {}).get("SteamPath", "")
    return os.path.normpath(p) if p else r"C:\Program Files (x86)\Steam"


def gog_games():
    base = r"SOFTWARE\GOG.com\Games"
    out = []
    for sub in reg_subkeys(winreg.HKEY_LOCAL_MACHINE, base, winreg.KEY_WOW64_32KEY) if winreg else []:
        v = reg_values(winreg.HKEY_LOCAL_MACHINE, base + "\\" + sub, winreg.KEY_WOW64_32KEY) or {}
        exe = v.get("exe") or v.get("launchCommand")
        if v.get("gameName") and exe and os.path.exists(exe):
            out.append({"id": "g-" + sub, "name": v["gameName"], "last": int(os.path.getmtime(exe)), "size": 0, "launch": exe, "src": "gog"})
    return out


def ubisoft_games():
    base = r"SOFTWARE\Ubisoft\Launcher\Installs"
    out = []
    for sub in reg_subkeys(winreg.HKEY_LOCAL_MACHINE, base, winreg.KEY_WOW64_32KEY) if winreg else []:
        v = reg_values(winreg.HKEY_LOCAL_MACHINE, base + "\\" + sub, winreg.KEY_WOW64_32KEY) or {}
        d = (v.get("InstallDir") or "").rstrip("/\\")
        if d and os.path.isdir(d):
            out.append({"id": "u-" + sub, "name": clean(folder_name(d)), "last": int(os.path.getmtime(d)), "size": 0,
                        "launch": f"uplay://launch/{sub}/0", "src": "ubisoft"})
    return out


# ---------- Playnite (Game Mode can open its fullscreen mode instead of our menu, see server.main) ----------

PLAYNITE_EXES = ("Playnite.FullscreenApp.exe", "Playnite.DesktopApp.exe")


def playnite_exe(folder):
    """The program that starts Playnite inside a folder (or the program itself), fullscreen mode preferred; None if it isn't there."""
    if folder and os.path.isfile(folder) and os.path.basename(folder) in PLAYNITE_EXES:
        return folder
    for n in PLAYNITE_EXES:
        if folder and os.path.isfile(os.path.join(folder, n)):
            return os.path.join(folder, n)
    return None


def playnite_folders():
    """Where Playnite usually lives: its installer's registry entry, the usual install folders, a Playnite folder on each drive."""
    out = []
    if winreg:
        for root, view in ((winreg.HKEY_CURRENT_USER, 0), (winreg.HKEY_LOCAL_MACHINE, 0), (winreg.HKEY_LOCAL_MACHINE, winreg.KEY_WOW64_32KEY)):
            base = r"Software\Microsoft\Windows\CurrentVersion\Uninstall"
            for sub in reg_subkeys(root, base, view):
                v = reg_values(root, base + "\\" + sub, view) or {}
                if str(v.get("DisplayName", "")).lower().startswith("playnite"):
                    out.append(str(v.get("InstallLocation", "")).strip('"'))
    for var in ("LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)"):
        out.append(os.path.join(os.environ.get(var, "\0"), "Playnite"))
    for d in drives():
        out += [os.path.join(d["path"], n, "Playnite") for n in ("", "Games", "Apps")]
    return out


def playnite_dir(saved=""):
    """The folder Playnite starts from: the one the user picked if it works, else the first one found. "" if none."""
    for f in [saved] + playnite_folders():
        exe = playnite_exe(f)
        if exe:
            return os.path.dirname(exe)
    return ""
