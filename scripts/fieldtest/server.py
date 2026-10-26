#!/usr/bin/env python3
"""Local "hostile" video site for field-testing Snapcast on an emulator.

One HTTP server, three virtual hosts (routed by the Host header; *.nip.io names resolve to the host
machine, which the emulator reaches as 10.0.2.2):

  site.<ip>.nip.io   the page the user opens: og:image, a hotlink-protected MP4, an iframe player
  embed.<ip>.nip.io  the embedded player: sets a session cookie, mints an expiring token, plays HLS
                     with hls.js (MSE, so the <video> element only has a blob: source)
  cdn.<ip>.nip.io    playlists and segments; answers 403 unless Referer, cookie and token are valid

Every request is logged as JSON lines to the file given by --log so a test can show exactly which
request was refused and why.
"""
import argparse
import hashlib
import hmac
import json
import os
import time
from http.cookies import SimpleCookie
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

SECRET = b"fieldtest"
TOKEN_TTL = 120  # seconds; short so a test can observe expiry


def token(now=None):
    exp = int(now or time.time()) + TOKEN_TTL
    sig = hmac.new(SECRET, str(exp).encode(), hashlib.sha256).hexdigest()[:16]
    return f"{exp}-{sig}"


def token_ok(value):
    try:
        exp, sig = value.split("-")
        good = hmac.new(SECRET, exp.encode(), hashlib.sha256).hexdigest()[:16]
        return hmac.compare_digest(sig, good) and int(exp) >= time.time()
    except Exception:
        return False


class Handler(BaseHTTPRequestHandler):
    server_version = "fieldtest/1.0"

    def log_message(self, *args):  # silence default stderr logging
        pass

    def _record(self, status, reason=""):
        entry = {
            "t": round(time.time(), 1),
            "method": self.command,
            "host": (self.headers.get("Host") or "").split(".")[0],
            "path": self.path.split("?")[0],
            "status": status,
            "reason": reason,
            "referer": self.headers.get("Referer"),
            "origin": self.headers.get("Origin"),
            "cookie": bool(self.headers.get("Cookie")),
            "range": self.headers.get("Range"),
            "ua": (self.headers.get("User-Agent") or "")[:60],
        }
        with open(self.server.log_path, "a") as f:
            f.write(json.dumps(entry) + "\n")

    def _send(self, status, body=b"", ctype="text/plain", headers=None, reason=""):
        self._record(status, reason)
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _file(self, path, ctype, extra=None):
        with open(path, "rb") as f:
            data = f.read()
        headers = {"Accept-Ranges": "bytes", **(extra or {})}
        rng = self.headers.get("Range")
        if rng and rng.startswith("bytes="):
            start_s, _, end_s = rng[6:].partition("-")
            start = int(start_s or 0)
            end = int(end_s) if end_s else len(data) - 1
            chunk = data[start:end + 1]
            headers["Content-Range"] = f"bytes {start}-{start + len(chunk) - 1}/{len(data)}"
            return self._send(206, chunk, ctype, headers)
        return self._send(200, data, ctype, headers)

    def do_HEAD(self):
        self.do_GET()

    def do_OPTIONS(self):
        self._send(204, headers=self._cors())

    def _cors(self):
        origin = self.headers.get("Origin")
        embed = f"http://embed.{self.server.base}:{self.server.port}"
        if origin == embed:
            return {"Access-Control-Allow-Origin": embed, "Access-Control-Allow-Credentials": "true",
                    "Access-Control-Allow-Headers": "Range", "Vary": "Origin"}
        return {}

    def do_GET(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        sub = host.split(".")[0]
        url = urlparse(self.path)
        q = parse_qs(url.query)
        d = self.server.media_dir
        base = f"{self.server.base}:{self.server.port}"

        if sub == "site":
            if url.path in ("/", "/index.html"):
                html = f"""<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Field test: Big Buck Bunny episode 1</title>
<meta property="og:title" content="Big Buck Bunny (field test)">
<meta property="og:image" content="http://site.{base}/og.jpg">
<style>body{{font-family:sans-serif;margin:12px;background:#111;color:#eee}}iframe{{width:100%;aspect-ratio:16/9;border:0}}</style>
</head><body><h2>Episode 1</h2>
<iframe src="http://embed.{base}/player.html" allow="autoplay; fullscreen"></iframe>
<h3>Trailer (direct MP4, hotlink protected)</h3>
<video controls preload="metadata" width="320" poster="http://site.{base}/trailer.jpg" src="http://site.{base}/trailer.mp4"></video>
</body></html>"""
                return self._send(200, html.encode(), "text/html; charset=utf-8")
            if url.path in ("/og.jpg", "/trailer.jpg"):
                ref = self.headers.get("Referer") or ""
                if ref and not ref.startswith(f"http://site.{base}/"):
                    return self._send(403, b"hotlink", reason="image bad referer")
                return self._file(os.path.join(d, "poster.jpg"), "image/jpeg")
            if url.path == "/trailer.mp4":
                ref = self.headers.get("Referer") or ""
                if not ref.startswith(f"http://site.{base}/"):
                    return self._send(403, b"hotlink", reason="mp4 bad referer")
                return self._file(os.path.join(d, "trailer.mp4"), "video/mp4")

        if sub == "embed":
            if url.path == "/player.html":
                t = token()
                html = f"""<!doctype html><html><head><meta charset="utf-8"><title>player</title>
<style>html,body{{margin:0;background:#000}}video{{width:100%;height:100vh}}</style>
<script src="http://embed.{base}/hls.min.js"></script></head><body>
<video id="v" controls muted playsinline></video>
<script>
var src = "http://cdn.{base}/hls/master.m3u8?t={t}";
var v = document.getElementById('v');
if (window.Hls && Hls.isSupported()) {{
  var hls = new Hls({{ startLevel: 0, xhrSetup: function (xhr) {{ xhr.withCredentials = true; }} }});
  hls.loadSource(src); hls.attachMedia(v);
  hls.on(Hls.Events.MANIFEST_PARSED, function () {{ v.play().catch(function(){{}}); }});
}} else {{ v.src = src; }}
</script></body></html>"""
                return self._send(200, html.encode(), "text/html; charset=utf-8",
                                  {"Set-Cookie": "sess=ok; Path=/; Domain=" + self.server.base + "; SameSite=None"})
            if url.path == "/hls.min.js":
                return self._file(os.path.join(d, "hls.min.js"), "application/javascript")
            if url.path == "/poster.jpg":
                return self._file(os.path.join(d, "poster.jpg"), "image/jpeg")

        if sub == "cdn":
            cors = self._cors()
            ref = self.headers.get("Referer") or ""
            cookie = SimpleCookie(self.headers.get("Cookie") or "")
            if not ref.startswith(f"http://embed.{base}/"):
                return self._send(403, b"bad referer", headers=cors, reason="bad referer")
            if cookie.get("sess") is None or cookie["sess"].value != "ok":
                return self._send(403, b"no session", headers=cors, reason="no session cookie")
            if not token_ok((q.get("t") or [""])[0]):
                return self._send(403, b"expired", headers=cors, reason="bad or expired token")
            t = q["t"][0]
            path = url.path
            if path == "/hls/master.m3u8":
                lines = ["#EXTM3U", "#EXT-X-VERSION:3"]
                for h, w, bw in ((360, 640, 800000), (720, 1280, 2500000), (1080, 1920, 5000000)):
                    lines += [f"#EXT-X-STREAM-INF:BANDWIDTH={bw},RESOLUTION={w}x{h},CODECS=\"avc1.64001f,mp4a.40.2\"",
                              f"{h}p/index.m3u8?t={t}"]
                return self._send(200, ("\n".join(lines) + "\n").encode(), "application/vnd.apple.mpegurl", cors)
            parts = path.strip("/").split("/")
            if len(parts) == 3 and parts[0] == "hls":
                rendition, name = parts[1], parts[2]
                full = os.path.join(d, "hls", rendition, name)
                if not os.path.isfile(full):
                    return self._send(404, b"missing", headers=cors, reason="missing")
                if name.endswith(".m3u8"):
                    text = open(full).read()
                    out = []
                    for line in text.splitlines():
                        out.append(f"{line}?t={t}" if line and not line.startswith("#") else line)
                    return self._send(200, ("\n".join(out) + "\n").encode(), "application/vnd.apple.mpegurl", cors)
                return self._file(full, "video/mp2t", cors)

        return self._send(404, b"not found", reason="unknown route")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--base", default="10.0.2.2.nip.io", help="host suffix the emulator resolves to this machine")
    p.add_argument("--media", required=True)
    p.add_argument("--log", required=True)
    a = p.parse_args()
    srv = ThreadingHTTPServer(("0.0.0.0", a.port), Handler)
    srv.base, srv.port, srv.media_dir, srv.log_path = a.base, a.port, a.media, a.log
    print(f"fieldtest server on :{a.port} for *.{a.base}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
