#!/usr/bin/env python3
"""
Self-test of the Python download engine (app/src/main/python/vidgrab_engine.py) on your PC.

It runs the SAME code that runs inside the app, against the bundled yt-dlp, using a tiny local web
server, so no phone and no internet connection are needed:

    python tools/engine_selftest.py

Checks: engine start-up, metadata extraction, live progress + speed, pause (stop) and resume of a
partial download (the final file must be byte-identical), error reporting.
Needs Python 3.10+ (same requirement as yt-dlp itself).
"""
import http.server
import json
import os
import platform
import re
import socket
import sys
import tempfile
import threading
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(ROOT, "app", "src", "main", "python"))
import vidgrab_engine as engine  # noqa: E402

SIZE = 16 * 1024 * 1024


def start_server(path):
    seen = []  # headers of every request, to check what the app really sends

    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def do_HEAD(self):
            self.serve(True)

        def do_GET(self):
            self.serve(False)

        def serve(self, head):
            seen.append({k: self.headers.get(k) for k in ("Referer", "User-Agent", "Cookie")})
            size = os.path.getsize(path)
            start, end = 0, size - 1
            rng = self.headers.get("Range")
            if rng:
                m = re.match(r"bytes=(\d+)-(\d*)", rng)
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
                self.send_response(206)
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
            else:
                self.send_response(200)
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            self.end_headers()
            if head:
                return
            try:
                with open(path, "rb") as f:
                    f.seek(start)
                    remaining = end - start + 1
                    while remaining > 0:
                        chunk = f.read(min(65536, remaining))
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
                        time.sleep(0.012)  # ~5 MB/s so there is time to pause
            except (BrokenPipeError, ConnectionResetError):
                pass

    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, port, seen


def main():
    if sys.version_info < (3, 10):
        sys.exit("Python 3.10+ is required (yt-dlp itself needs it).")
    base = tempfile.mkdtemp(prefix="vidgrab_selftest_")
    media = os.path.join(base, "clip.mp4")
    with open(media, "wb") as f:
        f.write(os.urandom(SIZE))
    server, port, seen = start_server(media)
    url = "http://127.0.0.1:%d/clip.mp4" % port

    qjs = os.path.join(ROOT, "app", "src", "main", "jniLibs", "x86_64", "libqjs.so")
    if not (platform.system() == "Linux" and platform.machine() in ("x86_64", "AMD64") and os.path.isfile(qjs)):
        qjs = ""  # the bundled QuickJS binaries are Linux/Android executables
    cfg = {
        "files_dir": os.path.join(base, "files"),
        "cache_dir": os.path.join(base, "cache"),
        "bundled_zip": os.path.join(ROOT, "app", "src", "main", "assets", "yt-dlp"),
        "ffmpeg": "", "ffmpeg_lib": "", "qjs": qjs,
    }

    t0 = time.time()
    res = json.loads(engine.init(json.dumps(cfg)))
    print("1) engine started: yt-dlp %s | JS runtime: %s | %.1fs" % (res["version"], res["js_runtime"] or "n/a on this OS", time.time() - t0))

    info = json.loads(engine.fetch_info(url, "{}"))
    assert "error" not in info, info
    print("2) metadata OK:", info["title"], "| extractor:", info["extractor"])

    opts = {"tmp_dir": os.path.join(base, "task"), "kind": "video", "max_height": 0, "concurrent": 4,
            "embed_meta": False, "expected_bytes": SIZE}
    box = {}

    def run():
        box["r"] = json.loads(engine.download("t1", url, json.dumps(opts)))

    th = threading.Thread(target=run)
    th.start()
    speeds, stopped = [], False
    while th.is_alive():
        time.sleep(0.3)
        st = json.loads(engine.get_state("t1"))
        if st.get("speed"):
            speeds.append(st["speed"])
        if st.get("pct", 0) > 0.3 and not stopped:
            engine.request_stop("t1")  # <- this is what the "Pause" button does
            stopped = True
    th.join()
    assert box["r"].get("stopped"), box
    part = [f for f in os.listdir(opts["tmp_dir"]) if f.endswith(".part")]
    print("3) paused at ~%d%%, partial file kept: %s" % (int(st["pct"] * 100), part))
    assert part

    th = threading.Thread(target=run)
    th.start()
    th.join()  # <- "Resume": same folder, yt-dlp continues the .part file
    assert box["r"]["ok"], box
    path = box["r"]["path"]
    assert os.path.getsize(path) == SIZE
    with open(path, "rb") as a, open(media, "rb") as b:
        assert a.read() == b.read(), "resumed file differs from the source!"
    print("4) resumed and finished: %s (%d bytes, identical to the source)" % (os.path.basename(path), SIZE))
    if speeds:
        print("   measured speed: %.1f MB/s" % (max(speeds) / 1048576))

    bad = json.loads(engine.fetch_info("http://127.0.0.1:1/none.mp4", "{}"))
    assert "error" in bad
    print("5) errors are reported with a readable message:", bad["error"][:70])

    # 6) what the in-app browser adds to a request: Referer, User-Agent and the cookies of a logged-in site
    cookie_file = os.path.join(base, "cookies.txt")
    with open(cookie_file, "w") as f:
        f.write("# Netscape HTTP Cookie File\n")
        f.write("127.0.0.1\tFALSE\t/\tFALSE\t2147483647\tsid\tabc123\n")
        f.write("127.0.0.1\tFALSE\t/\tFALSE\t2147483647\ttoken\ta=b==\n")
    extra = {"headers": {"Referer": "https://example.com/page"}, "user_agent": "VidGrabTest/1.0", "cookies": cookie_file}
    seen.clear()
    assert "error" not in json.loads(engine.fetch_info(url, json.dumps(extra)))
    res = json.loads(engine.download("t2", url, json.dumps(dict(opts, tmp_dir=os.path.join(base, "task2"), **extra))))
    assert res["ok"], res
    assert seen and all(r["Referer"] == "https://example.com/page" for r in seen), seen[:2]
    assert all(r["User-Agent"] == "VidGrabTest/1.0" for r in seen), seen[:2]
    assert all("sid=abc123" in (r["Cookie"] or "") and "token=a=b==" in (r["Cookie"] or "") for r in seen), seen[:2]
    print("6) Referer + User-Agent + cookies are sent on %d requests (browser downloads work)" % len(seen))
    server.shutdown()
    print("\nALL OK")


if __name__ == "__main__":
    main()
