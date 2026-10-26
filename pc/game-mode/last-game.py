"""Always-on helper for the TV: tells the first-time setup where this PC is and how many games it has, and the Bento
home layout which game was played last, with its cover art.

Game Mode's own server (server.py) only runs while a stream is up, so this tiny one (port 8766) runs from logon
(see pc/setup.ps1). It reuses server.py's library loading and only answers private-network clients.
"""
import http.server
import ipaddress
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server as s  # noqa: E402  (importing it starts nothing)

PORT = 8766
REFRESH = 300  # seconds between library reloads, so a game played outside Game Mode shows up too


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if ipaddress.ip_address(self.client_address[0]).is_private and self.path == "/api/rescan":
            threading.Thread(target=s.load_library, daemon=True).start()
            return self.send(200, b"{}")
        if ipaddress.ip_address(self.client_address[0]).is_private and self.path in ("/api/launcher/playnite", "/api/launcher/gamemode"):
            s.save_json(s.LAUNCHER_FILE, {"mode": self.path.rsplit("/", 1)[1]})  # what the next "Game Mode" stream opens, see server.main
            return self.send(200, b"{}")
        self.send(404)

    def do_GET(self):
        path = self.path.split("?")[0]
        if not ipaddress.ip_address(self.client_address[0]).is_private:
            return self.send(403)
        if path == "/api/last":
            return self.send(200, json.dumps(s.last_played()).encode())
        if path == "/api/hello":  # how the TV's first-time setup finds this PC
            return self.send(200, json.dumps({"app": "luna-game-mode", "name": s.pc_name()}).encode())
        if path == "/api/summary":
            return self.send(200, json.dumps(s.summary()).encode())
        parts = path.split("/")
        if len(parts) == 4 and parts[1] == "art" and parts[2].isdigit() and parts[3] == "library_600x900.jpg":
            f = s.local_art(parts[2], parts[3])
            if f:
                return self.send(200, open(f, "rb").read(), "image/jpeg")
        self.send(404)


def refresh_forever():
    while True:
        try:
            s.load_library()
        except Exception:
            pass  # try again next round
        time.sleep(REFRESH)


def main():
    try:
        server = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    except OSError:
        return  # already running
    threading.Thread(target=refresh_forever, daemon=True).start()
    server.serve_forever()


if __name__ == "__main__":
    main()
