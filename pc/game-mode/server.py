"""Console-style game menu for Moonlight/Apollo streams.

Serves index.html, lists installed Steam, Xbox (Microsoft Store), Epic, GOG, Ubisoft and hand-added games with
artwork, launches them, controls DS4Windows, and runs a fullscreen browser window.
Exiting the menu closes the window, which ends the Apollo stream.
`python server.py --no-browser` only serves (for testing).
"""
import ctypes
import getpass
import glob
import ipaddress
import http.server
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

import games as gm

PORT = 8765
HERE = os.path.dirname(os.path.abspath(__file__))
STEAM = gm.steam_root()
EPIC_MANIFESTS = r"C:\ProgramData\Epic\EpicGamesLauncher\Data\Manifests"
DS4 = r"C:\Program Files\DS4Windows\DS4Windows.exe"
DS4_DATA = os.path.join(os.environ.get("APPDATA", ""), "DS4Windows")
CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/{}/{}"
ART_CACHE = os.path.join(HERE, "art-cache.json")   # game name -> Steam appid, for non-Steam games
PROFILE = os.path.join(HERE, "profile.json")
CUSTOM = os.path.join(HERE, "custom-games.json")   # [{"name", "path"}]: games added by hand or from a scan
LAUNCHES = os.path.join(HERE, "launches.json")   # game id -> when Game Mode last launched it
HIDDEN_FILE = os.path.join(HERE, "hidden.json")   # game ids kept out of the menu (Tyr until the user brings it back)
FOLDERS_FILE = os.path.join(HERE, "game-folders.json")   # extra folders the game scan looks through
PLAYNITE_FILE = os.path.join(HERE, "playnite.json")   # {"dir": ...}: where the user says Playnite is (empty: look for it)
LAUNCHER_FILE = os.path.join(HERE, "launcher.json")   # {"mode": "playnite"}: what the TV wants Game Mode to open (set through last-game.py)
APOLLO_CONF = r"C:\Program Files\Apollo\config\sunshine.conf"
BROWSERS = [r"C:\Program Files\Google\Chrome\Application\chrome.exe",
            r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"]
NO_WINDOW = 0x08000000
browser = None
# Remote keys forwarded by Bruce TV on the TV: Apollo never allocates a virtual pad for the TV remote, so
# Moonlight's copy of those presses is dropped. The page listens on /api/events and reports focus back.
REMOTE_KEYS = {"up", "down", "left", "right", "ok", "back", "menu"}
keys = []
keys_cond = threading.Condition()
focus = {"menu": False, "at": 0.0}
in_game = False  # a game was launched from the menu and the menu hasn't had focus since
left_menu = False  # ...and the menu has lost focus since launch (it still has it while the game loads)
exiting = False  # Exit Game Mode was picked; the browser closes shortly


def top_windows():
    """{hwnd: title} of the visible, titled, unowned, non-tool, non-cloaked windows (what the taskbar would show)."""
    u = ctypes.windll.user32
    found = {}

    @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
    def cb(h, _):
        n = u.GetWindowTextLengthW(h)
        if n and u.IsWindowVisible(h) and not u.GetWindow(h, 4) and not u.GetWindowLongW(h, -20) & 0x80:
            cloaked = ctypes.c_int(0)  # DWMWA_CLOAKED: hidden UWP frames
            ctypes.windll.dwmapi.DwmGetWindowAttribute(h, 13, ctypes.byref(cloaked), 4)
            if not cloaked.value:
                buf = ctypes.create_unicode_buffer(n + 1)
                u.GetWindowTextW(h, buf, n + 1)
                found[h] = buf.value
        return True

    u.EnumWindows(cb, 0)
    return found


def raise_window(h):
    """Windows won't let a background process raise its window, so tap Alt first (the usual foreground-lock workaround)."""
    u = ctypes.windll.user32
    # Borrow the current foreground window's input queue so Windows lets us hand focus over.
    fg_thread = u.GetWindowThreadProcessId(u.GetForegroundWindow(), None)
    me = ctypes.windll.kernel32.GetCurrentThreadId()
    u.AttachThreadInput(fg_thread, me, True)
    u.keybd_event(0x12, 0, 0, 0)
    u.keybd_event(0x12, 0, 2, 0)
    u.ShowWindow(h, 9)  # SW_RESTORE
    u.BringWindowToTop(h)
    u.SetForegroundWindow(h)
    u.AttachThreadInput(fg_thread, me, False)
    if u.GetForegroundWindow() != h:
        u.SwitchToThisWindow(h, True)


def cover_screen(h):
    """A game can change the resolution or leave the taskbar on top; stretch the menu over the whole monitor again
    so Windows treats it as fullscreen and hides the taskbar."""
    u = ctypes.windll.user32
    u.SetProcessDPIAware()  # real pixels, not scaled ones
    u.SetWindowPos(h, -1, 0, 0, u.GetSystemMetrics(0), u.GetSystemMetrics(1), 0x40)  # HWND_TOPMOST, SWP_SHOWWINDOW
    u.SetWindowPos(h, -2, 0, 0, 0, 0, 0x53)  # HWND_NOTOPMOST again, keep size/position/focus


def bring_to_front():
    found = [h for h, t in top_windows().items() if t == "Game Mode"]
    for h in found:
        raise_window(h)
        cover_screen(h)
    return bool(found)


def refit_menu():
    """The menu got focus back after a game: the resolution may still be settling, so fit it twice."""
    for _ in range(2):
        time.sleep(1)
        for h, t in top_windows().items():
            if t == "Game Mode":
                cover_screen(h)


def front_new_windows():
    """A launched game's window (or its launcher/splash) often opens behind the fullscreen menu, or never gets
    foreground because a background process started it. Raise every new window that appears for a while."""
    seen = set(top_windows())
    for _ in range(120):  # 60 s: Forza-sized games take a while to show anything
        time.sleep(0.5)
        if not in_game:
            return  # the menu is back in front, nothing more to do
        for h, t in top_windows().items():
            if h not in seen:
                seen.add(h)
                if t != "Game Mode":
                    raise_window(h)


def front_on_start():
    for _ in range(40):  # wait for the kiosk window, then make sure it is in front
        time.sleep(0.5)
        if bring_to_front():
            return
library = {"games": [], "all": [], "launch": {}}  # filled in the background at startup; "all" includes hidden games
loaded = threading.Event()
lib_lock = threading.Lock()
src_cache = {}   # the slow Microsoft Store listing, kept between quick reloads


def read(path):
    return open(path, encoding="utf-8", errors="ignore").read()


def load_json(path, default):
    try:
        return json.loads(read(path))
    except (OSError, ValueError):
        return default


def save_json(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=1)


def steam_playtime():
    """{appid: minutes played} from every Steam account's localconfig.vdf."""
    out = {}
    for f in glob.glob(os.path.join(STEAM, "userdata", "*", "config", "localconfig.vdf")):
        for appid, mins in re.findall(r'"(\d+)"\s*\{[^{}]*?"Playtime"\s+"(\d+)"', read(f)):
            out[appid] = max(out.get(appid, 0), int(mins))
    return out


def steam_games():
    played = steam_playtime()
    try:
        libs = [p.replace("\\\\", "\\") for p in re.findall(r'"path"\s+"([^"]+)"', read(os.path.join(STEAM, "steamapps", "libraryfolders.vdf")))]
    except OSError:
        libs = [STEAM]
    out = []
    for lib in libs:
        for acf in glob.glob(os.path.join(lib, "steamapps", "appmanifest_*.acf")):
            kv = dict(re.findall(r'"(appid|name|LastPlayed|SizeOnDisk)"\s+"([^"]*)"', read(acf)))
            name = kv.get("name", "")
            if "appid" not in kv or "Redistributable" in name or name.startswith("Steamworks"):
                continue
            out.append({"id": kv["appid"], "name": name, "art": kv["appid"], "last": int(kv.get("LastPlayed") or 0),
                        "size": int(kv.get("SizeOnDisk") or 0), "launch": "steam://rungameid/" + kv["appid"], "src": "steam",
                        "played": played.get(kv["appid"], 0)})
    return out


def xbox_games():
    """Microsoft Store / Game Pass games: packages that ship a MicrosoftGame.config."""
    ps = ("Get-AppxPackage | Where-Object { -not $_.IsFramework -and $_.InstallLocation -and "
          "(Test-Path (Join-Path $_.InstallLocation 'MicrosoftGame.config')) } | ForEach-Object { "
          "$a = @((Get-AppxPackageManifest $_).Package.Applications.Application)[0]; "
          "if ($a) { [pscustomobject]@{fam=$_.PackageFamilyName; loc=$_.InstallLocation; app=$a.Id} } } | ConvertTo-Json")
    try:
        raw = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                             timeout=60, creationflags=NO_WINDOW).stdout.strip()
        pkgs = json.loads(raw) if raw else []
    except Exception:
        return []
    out = []
    for p in pkgs if isinstance(pkgs, list) else [pkgs]:
        cfg = read(os.path.join(p["loc"], "MicrosoftGame.config"))
        m = re.search(r'DefaultDisplayName="([^"]+)"', cfg)
        name = m.group(1) if m and not m.group(1).startswith("ms-resource") else p["fam"].split("_")[0]
        data = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Packages", p["fam"])
        last = int(os.path.getmtime(data)) if os.path.exists(data) else 0
        out.append({"id": "x-" + p["fam"], "name": name, "last": last, "size": 0, "src": "xbox",
                    "launch": "shell:AppsFolder\\" + p["fam"] + "!" + p["app"]})
    return out


def epic_games():
    out = []
    for item in glob.glob(os.path.join(EPIC_MANIFESTS, "*.item")):
        try:
            j = json.loads(read(item))
        except ValueError:
            continue
        if j.get("bIsIncompleteInstall") or not j.get("bIsExecutable", True):
            continue
        target = urllib.parse.quote(f'{j["CatalogNamespace"]}:{j["CatalogItemId"]}:{j["AppName"]}')
        out.append({"id": "e-" + j["AppName"], "name": j["DisplayName"], "last": int(os.path.getmtime(item)),
                    "size": int(j.get("InstallSize") or 0), "src": "epic",
                    "launch": f"com.epicgames.launcher://apps/{target}?action=launch&silent=true"})
    return out


def custom_games():
    """Hand-added games from custom-games.json: [{"name": ..., "path": "C:\\...\\game.exe"}]. Entries whose file is gone are skipped."""
    return [{"id": "c-" + g["name"], "name": g["name"], "last": 0, "size": 0, "launch": g["path"], "src": "custom"}
            for g in load_json(CUSTOM, []) if g.get("path") and os.path.exists(g["path"])]


def add_custom(name, path):
    """Put a game into the menu by the path of the program that starts it. False if that isn't a launchable file."""
    path = os.path.normpath(path)
    if not os.path.isabs(path) or not os.path.isfile(path) or not path.lower().endswith(gm.EXT):
        return False
    name = name.strip()[:60] or gm.clean(os.path.splitext(os.path.basename(path))[0])
    items = [g for g in load_json(CUSTOM, []) if g.get("name") != name]
    items.append({"name": name, "path": path})
    save_json(CUSTOM, items)
    return True


def remove_custom(gid):
    save_json(CUSTOM, [g for g in load_json(CUSTOM, []) if "c-" + g.get("name", "") != gid])


def hidden_ids():
    return set(load_json(HIDDEN_FILE, ["2445260"]))


def set_hidden(gid, hide):
    ids = hidden_ids() - {gid}
    if hide:
        ids.add(gid)
    save_json(HIDDEN_FILE, sorted(ids))


def folders():
    return load_json(FOLDERS_FILE, [])


def playnite_saved():
    return load_json(PLAYNITE_FILE, {}).get("dir", "")


def pc_name():
    """The name Moonlight lists this PC under: Apollo's own setting, else the computer name."""
    try:
        for line in read(APOLLO_CONF).splitlines():
            k, _, v = line.partition("=")
            if k.strip() == "sunshine_name" and v.strip():
                return v.strip()
    except OSError:
        pass
    return socket.gethostname()


def steam_appid_for(name, cache):
    """Non-Steam games borrow artwork from their Steam store page, found by name."""
    if name not in cache:
        try:
            url = "https://store.steampowered.com/api/storesearch/?cc=us&l=en&term=" + urllib.parse.quote(name)
            items = json.load(urllib.request.urlopen(url, timeout=8)).get("items", [])
            cache[name] = str(items[0]["id"]) if items else ""
        except Exception:
            return ""  # offline: try again next start
    return cache[name]


def load_library(full=True):
    """Read every launcher's list. full=False reuses the (slow) Microsoft Store listing from the last full load."""
    with lib_lock:
        if full or "xbox" not in src_cache:
            src_cache["xbox"] = xbox_games()
        games = steam_games()
        others = [dict(g) for g in src_cache["xbox"]] + epic_games() + gm.gog_games() + gm.ubisoft_games() + custom_games()
        cache = load_json(ART_CACHE, {})
        for g in others:
            g["art"] = steam_appid_for(g["name"], cache)
        try:
            save_json(ART_CACHE, cache)
        except OSError:
            pass
        games += others
        launches = load_json(LAUNCHES, {})
        for g in games:
            g["last"] = max(g["last"], int(launches.get(g["id"], 0)))
        games.sort(key=lambda g: -g["last"])
        library["launch"] = {g["id"]: g["launch"] for g in games}
        for g in games:
            del g["launch"]
        hidden = hidden_ids()
        library["all"] = games
        library["games"] = [g for g in games if g["id"] not in hidden]
        loaded.set()


def summary():
    """How many games each launcher has (for the TV's first-time setup)."""
    loaded.wait(60)
    counts = {}
    for g in library["all"]:
        counts[g["src"]] = counts.get(g["src"], 0) + 1
    return {"total": len(library["all"]), "counts": counts}


def manage_list():
    """Everything Game Mode knows, hidden games and hand-added games whose file has gone included, for the Manage games screen."""
    loaded.wait(60)
    hidden = hidden_ids()
    out = [{"id": g["id"], "name": g["name"], "src": g["src"], "hidden": g["id"] in hidden} for g in library["all"]]
    have = {g["id"] for g in out}
    for g in load_json(CUSTOM, []):
        if "c-" + g.get("name", "") not in have:
            out.append({"id": "c-" + g["name"], "name": g["name"], "src": "custom", "hidden": False, "missing": True})
    return sorted(out, key=lambda g: g["name"].lower())


# ---------- long jobs (scan, search): one at a time, polled by the page ----------

job = {"id": 0, "kind": "", "running": False, "text": "", "result": None, "cancel": False}


def start_job(kind, fn):
    if job["running"]:
        return False
    job.update(id=job["id"] + 1, kind=kind, running=True, text="Starting…", result=None, cancel=False)

    def run():
        def tick(text):
            if job["cancel"]:
                raise gm.Cancel()
            job["text"] = text

        try:
            job["result"] = fn(tick)
        except gm.Cancel:
            job["result"] = {"cancelled": True}
        except Exception as e:
            job["result"] = {"error": str(e)}
        job["running"] = False

    threading.Thread(target=run, daemon=True).start()
    return True


def scan_job(tick):
    tick("Reading your game launchers…")
    load_library()
    known = [g["name"] for g in library["all"]] + list(library["launch"].values())
    tick("Looking through your game folders…")
    found = gm.scan_folders(folders(), known, lambda p: tick("Looking in " + p))
    return {**summary(), "found": found}


def places():
    home = os.environ.get("USERPROFILE", "")
    out = [{"name": n, "path": os.path.join(home, n)} for n in ("Desktop", "Downloads") if os.path.isdir(os.path.join(home, n))]
    for p in gm.library_roots(folders()):  # game folders worth jumping to: the ones with something in them
        try:
            if any(e.is_dir() for e in os.scandir(p)):
                out.append({"name": f"{gm.folder_name(p)} ({p[0]}:)", "path": p})
        except OSError:
            pass
    return out


def start(target):
    """Open a game's launch target; a program starts in its own folder (many games load files relative to it)."""
    if os.path.isfile(target):
        os.startfile(target, cwd=os.path.dirname(target))
    else:
        os.startfile(target)


def note_launch(gid):
    """Remember that Game Mode just started this game: Epic/Xbox only know file dates, so this is the real 'last played'."""
    now = int(time.time())
    for g in library["games"]:
        if g["id"] == gid:
            g["last"] = now
    try:
        try:
            launches = json.loads(read(LAUNCHES))
        except (OSError, ValueError):
            launches = {}
        launches[gid] = now
        json.dump(launches, open(LAUNCHES, "w"))
    except OSError:
        pass


def last_played():
    """The most recently played game that has cover art (what the TV's Bento tile shows), or {}."""
    loaded.wait(60)
    for g in sorted(library["games"], key=lambda g: -g["last"]):
        if g["last"] and g.get("art"):
            return {"name": g["name"], "art": g["art"], "local": bool(local_art(g["art"], "library_600x900.jpg"))}
    return {}


def local_art(appid, name):
    for root, _, files in os.walk(os.path.join(STEAM, "appcache", "librarycache", appid)):
        if name in files:
            return os.path.join(root, name)
    return None


# ---------- DS4Windows ----------

def ds4_running():
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq DS4Windows.exe", "/NH"], capture_output=True, text=True,
                         creationflags=NO_WINDOW).stdout
    return "DS4Windows.exe" in out


def ds4_state():
    profiles = sorted(os.path.splitext(os.path.basename(f))[0] for f in glob.glob(os.path.join(DS4_DATA, "Profiles", "*.xml")))
    try:
        current = re.search(r"<Controller1>([^<]*)</Controller1>", read(os.path.join(DS4_DATA, "Profiles.xml"))).group(1)
    except (OSError, AttributeError):
        current = ""
    return {"installed": os.path.exists(DS4), "running": ds4_running(), "profiles": profiles, "current": current}


PAD_NAMES = {"05C4": "DualShock 4", "09CC": "DualShock 4", "0BA0": "DualShock 4 (USB adapter)", "0CE6": "DualSense"}
PAD_ID = re.compile(r"(?:VID&0002054C_PID&|VID_054C&PID_)(05C4|09CC|0BA0|0CE6)", re.I)


def pads():
    """Connected Sony pads, in the order DS4Windows fills its slots (assumed to be by address; exact with one pad)."""
    ps = ("Get-PnpDevice -Class HIDClass -PresentOnly | Where-Object { $_.Status -eq 'OK' -and "
          "($_.InstanceId -like 'BTHENUM*054C*' -or $_.InstanceId -like 'HID\\VID_054C*') } | "
          "ForEach-Object { $_.InstanceId } | ConvertTo-Json")
    try:
        raw = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                             timeout=20, creationflags=NO_WINDOW).stdout.strip()
        ids = json.loads(raw) if raw else []
    except Exception:
        return []
    found = {}
    for i in ids if isinstance(ids, list) else [ids]:
        m = PAD_ID.search(i)
        if not m:
            continue
        mac = re.search(r"([0-9A-F]{12})_C", i)
        key = mac.group(1) if mac else i
        found[key] = {"id": key, "name": PAD_NAMES[m.group(1).upper()], "bt": bool(mac)}
    out = [found[k] for k in sorted(found)]
    try:
        xml = read(os.path.join(DS4_DATA, "Profiles.xml"))
    except OSError:
        xml = ""
    for n, p in enumerate(out, 1):
        m = re.search(rf"<Controller{n}>([^<]*)</Controller{n}>", xml)
        p["slot"], p["profile"] = n, m.group(1) if m else ""
    return out


DS4_REQUEST = os.path.join(HERE, "ds4-request.txt")


def ds4_action(action, arg, slot="1"):
    """DS4Windows runs as administrator, so the elevated "LunaTV DS4Windows" task (ds4-helper.ps1) does the work."""
    if action == "profile":
        if arg not in ds4_state()["profiles"] or not slot.isdigit() or not 1 <= int(slot) <= 8:
            return False
        line = f"profile {slot} {arg}"
    elif action in ("start", "stop", "restart"):
        line = action
    else:
        return False
    open(DS4_REQUEST, "w").write(line)
    subprocess.run(["schtasks", "/run", "/tn", "LunaTV DS4Windows"], capture_output=True, creationflags=NO_WINDOW)
    for _ in range(60):  # the helper deletes the request when finished
        if not os.path.exists(DS4_REQUEST):
            return True
        time.sleep(0.5)
    return False


# ---------- profile ----------

def profile():
    try:
        return json.loads(read(PROFILE))
    except (OSError, ValueError):
        return {"name": getpass.getuser(), "avatar": 0}


def close_browser():
    if browser and browser.poll() is None:
        browser.terminate()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body=b"", ctype="application/json", headers=()):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "max-age=86400" if ctype.startswith("image/") else "no-store")
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def json(self, obj):
        self.send(200, json.dumps(obj).encode())

    def query(self):
        return {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).items()}

    def body(self):
        return json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")

    def lan_allowed(self, method, path):
        """Only loopback gets the full API; other private-network clients may only forward remote keys."""
        ip = ipaddress.ip_address(self.client_address[0])
        if ip.is_loopback:
            return True
        return ip.is_private and ((method, path) in (("GET", "/api/focus"), ("GET", "/api/last"), ("POST", "/api/front")) or
                                  (method == "GET" and path.startswith("/art/")) or
                                  (method == "POST" and path.startswith("/api/key/")))

    def events(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        with keys_cond:
            seen = len(keys)
        try:
            while True:
                with keys_cond:
                    keys_cond.wait_for(lambda: len(keys) > seen, timeout=15)
                    new, seen = keys[seen:], len(keys)
                self.wfile.write("".join(f"data: {k}\n\n" for k in new).encode() if new else b": ping\n\n")
                self.wfile.flush()
        except OSError:
            pass  # page closed

    def do_GET(self):
        path = self.path.split("?")[0]
        if not self.lan_allowed("GET", path):
            return self.send(403)
        if path == "/":
            self.send(200, open(os.path.join(HERE, "index.html"), "rb").read(), "text/html; charset=utf-8")
        elif path == "/api/games":
            loaded.wait(60)
            self.json({"games": library["games"]})
        elif path == "/api/last":
            self.json(last_played())
        elif path == "/api/hello":
            self.json({"app": "luna-game-mode", "name": pc_name()})
        elif path == "/api/summary":
            self.json(summary())
        elif path == "/api/manage":
            self.json({"games": manage_list()})
        elif path == "/api/folders":
            self.json({"folders": folders()})
        elif path == "/api/playnite":
            self.json({"saved": playnite_saved(), "found": gm.playnite_dir(playnite_saved())})
        elif path == "/api/job":
            self.json(job)
        elif path == "/api/fs":
            p = self.query().get("path", "")
            if not p:
                return self.json({"path": "", "parent": None, "drives": gm.drives(), "places": places()})
            d = gm.list_dir(p)
            if d is None:
                return self.send(404)
            parent = os.path.dirname(p.rstrip("\\/"))
            d["parent"] = parent if parent and parent != p.rstrip("\\/") else ""
            self.json(d)
        elif path == "/api/exes":
            p = self.query().get("folder", "")
            self.json({"name": gm.clean(gm.folder_name(p)), "exes": gm.exes(p, 3)[:12]})
        elif path == "/api/profile":
            self.json(profile())
        elif path == "/api/events":
            self.events()
        elif path == "/api/focus":
            alive = time.time() - focus["at"] < 6  # the menu page is open (it reports every 2 s)
            self.json({"menu": focus["menu"] and alive, "alive": alive, "exiting": exiting})
        elif path == "/api/ds4":
            self.json(ds4_state())
        elif path == "/api/pads":
            self.json({"pads": pads(), "profiles": ds4_state()["profiles"], "running": ds4_running()})
        elif path.startswith("/art/"):
            parts = path.split("/")
            if len(parts) != 4 or not parts[2].isdigit() or parts[3] not in (
                    "library_600x900.jpg", "library_hero.jpg", "logo.png", "header.jpg"):
                return self.send(404)
            appid, name = parts[2], parts[3]
            f = local_art(appid, name if name != "header.jpg" else "library_header.jpg")
            if f:
                self.send(200, open(f, "rb").read(), "image/png" if name.endswith(".png") else "image/jpeg")
            else:
                self.send(302, headers=[("Location", CDN.format(appid, name))])
        else:
            self.send(404)

    def do_POST(self):
        global in_game, left_menu, exiting
        if not self.lan_allowed("POST", self.path):
            return self.send(403)
        parts = [urllib.parse.unquote(p) for p in self.path.strip("/").split("/")]
        if parts[:2] == ["api", "key"] and len(parts) == 3 and parts[2] in REMOTE_KEYS:
            # The TV sends every remote key while a stream is up. A game in front keeps them away from the menu;
            # otherwise the menu gets them, raised first if some other window took focus.
            if not focus["menu"]:
                if in_game:
                    return self.send(204)
                bring_to_front()
            with keys_cond:
                keys.append(parts[2])
                keys_cond.notify_all()
        elif parts == ["api", "focus"]:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            focus.update(menu=bool(body.get("focused")), at=time.time())
            # The menu keeps focus while a game loads, so only count focus that returns after it was lost.
            if not focus["menu"]:
                left_menu = True
            elif left_menu:
                in_game = False
                threading.Thread(target=refit_menu, daemon=True).start()
        elif parts == ["api", "front"]:
            # The TV asks for this when a stream starts or resumes; never pull the menu over a running game.
            if not in_game:
                bring_to_front()
        elif parts[:2] == ["api", "launch"] and len(parts) == 3 and parts[2] in library["launch"]:
            in_game, left_menu = True, False
            note_launch(parts[2])
            threading.Thread(target=front_new_windows, daemon=True).start()
            start(library["launch"][parts[2]])
        elif parts == ["api", "job", "scan"]:
            return self.json({"started": start_job("scan", scan_job)})
        elif parts == ["api", "job", "find"]:
            q = str(self.body().get("q", ""))
            return self.json({"started": start_job("find", lambda tick: {"folders": gm.search_folders(q, tick)})})
        elif parts == ["api", "job", "cancel"]:
            job["cancel"] = True
        elif parts == ["api", "games", "add"]:
            b = self.body()
            ok = add_custom(str(b.get("name", "")), str(b.get("path", "")))
            if ok:
                load_library(full=False)
            return self.json({"ok": ok})
        elif parts == ["api", "games", "addmany"]:
            n = sum(add_custom(str(g.get("name", "")), str(g.get("path", ""))) for g in self.body().get("games", []))
            load_library(full=False)
            return self.json({"added": n})
        elif parts[:2] == ["api", "games"] and len(parts) == 4 and parts[2] in ("remove", "hide", "show"):
            if parts[2] == "remove":
                remove_custom(parts[3])
            else:
                set_hidden(parts[3], parts[2] == "hide")
            load_library(full=False)
        elif parts[:2] == ["api", "folders"] and len(parts) == 3 and parts[2] in ("add", "remove"):
            p = os.path.normpath(str(self.body().get("path", "")))
            fl = [x for x in folders() if x != p]
            if parts[2] == "add" and os.path.isdir(p):
                fl.append(p)
            save_json(FOLDERS_FILE, fl)
            return self.json({"folders": fl})
        elif parts == ["api", "playnite"]:
            p = str(self.body().get("path", "")).strip()
            save_json(PLAYNITE_FILE, {"dir": os.path.normpath(p) if p else ""})
            load_library(full=False)
            return self.json({"saved": playnite_saved(), "found": gm.playnite_dir(playnite_saved())})
        elif parts == ["api", "bigpicture"]:
            in_game, left_menu = True, False
            threading.Thread(target=front_new_windows, daemon=True).start()
            os.startfile("steam://open/bigpicture")
        elif parts == ["api", "exit"]:
            # The TV polls /api/focus once a second: give it time to cover the stream before the desktop shows.
            exiting = True
            threading.Timer(2.5, close_browser).start()
        elif parts == ["api", "profile"]:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            p = {"name": str(body.get("name", ""))[:24].strip() or getpass.getuser(), "avatar": int(body.get("avatar", 0)),
                 "style": body.get("style") if re.fullmatch(r"[a-z-]{1,30}", str(body.get("style"))) else "pixel-art"}
            json.dump(p, open(PROFILE, "w"))
            return self.json(p)
        elif parts[:2] == ["api", "ds4"] and len(parts) in (3, 4, 5):
            if not ds4_action(parts[2], parts[3] if len(parts) > 3 else "", parts[4] if len(parts) == 5 else "1"):
                return self.send(400)
            return self.json(ds4_state())
        else:
            return self.send(404)
        self.send(200, b"{}")


def main():
    threading.Thread(target=load_library, daemon=True).start()
    try:
        server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)  # LAN for remote keys only, see lan_allowed
    except OSError:
        server = None  # already running: just open another window onto it
    if server:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    if load_json(LAUNCHER_FILE, {}).get("mode") == "playnite":
        exe = gm.playnite_exe(gm.playnite_dir(playnite_saved()))
        if exe:  # the TV picked Playnite as the Game Mode launcher: open it instead of our menu, and stay up while it runs (the stream ends when it closes)
            subprocess.Popen([exe], cwd=os.path.dirname(exe)).wait()
            return
    if "--no-browser" in sys.argv:
        print(f"Serving on http://127.0.0.1:{PORT}/")
        threading.Event().wait()
    global browser
    exe = next(p for p in BROWSERS if os.path.exists(p))
    data = os.path.join(os.environ.get("LOCALAPPDATA", HERE), "BearacleGameMode")
    browser = subprocess.Popen([exe, "--kiosk", f"--app=http://127.0.0.1:{PORT}/", f"--user-data-dir={data}",
                                "--no-first-run", "--no-default-browser-check", "--hide-crash-restore-bubble",
                                "--autoplay-policy=no-user-gesture-required", "--disable-features=Translate"])
    threading.Thread(target=front_on_start, daemon=True).start()
    browser.wait()


if __name__ == "__main__":
    main()
