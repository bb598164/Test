# -*- coding: utf-8 -*-
"""
VidGrab engine
==============

A small JSON-in / JSON-out bridge between the Kotlin app (through Chaquopy) and yt-dlp.

* yt-dlp is NOT imported from the APK.  The official "zipapp" release (a single file that
  already contains yt-dlp, yt-dlp-ejs and the lazy extractors) is unpacked into the app's
  private storage.  A copy is bundled in the APK (assets/yt-dlp) so the very first run works
  offline, and `update()` can fetch newer releases from GitHub (SHA-256 verified).
* Every public function takes / returns plain strings (JSON) so the Kotlin side stays simple.
* Progress is *polled* by Kotlin (`get_state`) instead of calling back into Kotlin.
* Pause / cancel = raise a DownloadCancelled subclass from the progress hook.  The `.part`
  files stay on disk, so a later `download()` with the same tmp_dir resumes where it stopped.
"""
import hashlib
import importlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import zipfile

_lock = threading.RLock()
_cfg = {}
_yt = None          # the imported yt_dlp module
_Stop = None        # exception class used to interrupt a download
_tasks = {}         # task_id -> live state dict

_REPOS = {
    "stable": "yt-dlp/yt-dlp",
    "nightly": "yt-dlp/yt-dlp-nightly-builds",
    "master": "yt-dlp/yt-dlp-master-builds",
}
_ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
_USER_AGENT = "VidGrab/1.0 (+https://github.com/yt-dlp/yt-dlp)"


# --------------------------------------------------------------------------- helpers
def _clean(msg):
    msg = _ANSI.sub("", str(msg or "")).strip()
    msg = re.sub(r"^(ERROR|WARNING):\s*", "", msg)
    return msg[:600]


def _root():
    return os.path.join(_cfg["files_dir"], "ytdlp")


def _vroot():
    return os.path.join(_root(), "versions")


def _vdir(name):
    return os.path.join(_vroot(), name)


def _vkey(text):
    nums = re.findall(r"\d+", text or "")
    return tuple(int(n) for n in nums) or (0,)


def _is_valid(path):
    return os.path.isfile(os.path.join(path, "yt_dlp", "version.py"))


def _read_ptr(name):
    try:
        with open(os.path.join(_root(), name), "r") as f:
            value = f.read().strip()
    except OSError:
        return None
    return value if value and _is_valid(_vdir(value)) else None


def _write_ptr(name, value):
    os.makedirs(_root(), exist_ok=True)
    tmp = os.path.join(_root(), name + ".tmp")
    with open(tmp, "w") as f:
        f.write(value)
    os.replace(tmp, os.path.join(_root(), name))


def _zip_version(zf):
    src = zf.read("yt_dlp/version.py").decode("utf-8", "replace")
    m = re.search(r"__version__\s*=\s*'([^']+)'", src)
    return m.group(1) if m else "0"


def _install_zip(zip_path):
    """Unpack a yt-dlp zipapp into versions/<version>/ and return <version>."""
    with zipfile.ZipFile(zip_path) as zf:
        name = _zip_version(zf)
        dest = _vdir(name)
        if _is_valid(dest):
            return name
        os.makedirs(_vroot(), exist_ok=True)
        tmp = dest + ".tmp"
        shutil.rmtree(tmp, ignore_errors=True)
        zf.extractall(tmp)
        shutil.rmtree(dest, ignore_errors=True)
        os.replace(tmp, dest)
    return name


def _prune(keep):
    try:
        names = os.listdir(_vroot())
    except OSError:
        return
    for n in names:
        if n not in keep:
            shutil.rmtree(os.path.join(_vroot(), n), ignore_errors=True)


def _ensure_installed():
    bundled_name = None
    bundled = _cfg.get("bundled_zip") or ""
    if bundled and os.path.isfile(bundled):
        try:
            bundled_name = _install_zip(bundled)
        except Exception:
            bundled_name = None
    cur = _read_ptr("current")
    if cur and (bundled_name is None or _vkey(cur) >= _vkey(bundled_name)):
        return cur
    if bundled_name is None:
        raise RuntimeError("yt-dlp is not installed and no bundled copy was found")
    if cur:
        _write_ptr("previous", cur)
    _write_ptr("current", bundled_name)
    _prune({bundled_name, cur or ""})
    return bundled_name


def _load():
    """(Re)import yt_dlp from the version directory that `current` points to."""
    global _yt, _Stop
    with _lock:
        name = _read_ptr("current")
        if not name:
            raise RuntimeError("no current yt-dlp version")
        vr = os.path.normpath(_vroot())
        sys.path[:] = [p for p in sys.path if not os.path.normpath(p).startswith(vr)]
        sys.path.insert(0, _vdir(name))
        for m in [m for m in sys.modules
                  if m in ("yt_dlp", "yt_dlp_ejs") or m.startswith(("yt_dlp.", "yt_dlp_ejs."))]:
            del sys.modules[m]
        importlib.invalidate_caches()
        import yt_dlp
        from yt_dlp.utils import DownloadCancelled

        class StopDownload(DownloadCancelled):
            msg = "Stopped by user"

        _yt = yt_dlp
        _Stop = StopDownload
        return yt_dlp.version.__version__


def _probe_qjs():
    qjs = _cfg.get("qjs") or ""
    if not (qjs and os.path.isfile(qjs)):
        return ""
    try:
        r = subprocess.run([qjs, "--help"], capture_output=True, text=True, timeout=20)
        out = (r.stdout or "") + (r.stderr or "")
        m = re.search(r"QuickJS(?:-ng)?\s+version\s+\S+", out)
        return m.group(0) if m else ""
    except Exception:
        return ""


def _status():
    ver = _yt.version.__version__ if _yt else ""
    return {"version": ver, "js_runtime": _cfg.get("_js", ""), "ffmpeg": bool(_cfg.get("ffmpeg"))}


# --------------------------------------------------------------------------- public: lifecycle
def init(cfg_json):
    global _cfg
    with _lock:
        _cfg = json.loads(cfg_json)
        for key in ("files_dir", "cache_dir"):
            os.makedirs(_cfg[key], exist_ok=True)
        tempfile.tempdir = _cfg["cache_dir"]
        os.environ["TMPDIR"] = _cfg["cache_dir"]
        lib = _cfg.get("ffmpeg_lib") or ""
        if lib and os.path.isdir(lib):
            old = os.environ.get("LD_LIBRARY_PATH", "")
            if lib not in old.split(":"):
                os.environ["LD_LIBRARY_PATH"] = lib + (":" + old if old else "")
        _cfg["_js"] = _probe_qjs()
        _ensure_installed()
        _load()
        return json.dumps(_status())


def reload_engine():
    """Re-import yt-dlp from the `current` pointer; roll back if the new version is broken."""
    with _lock:
        try:
            _load()
        except Exception as e:
            prev = _read_ptr("previous")
            if prev:
                _write_ptr("current", prev)
                try:
                    _load()
                except Exception:
                    pass
            return json.dumps({"ok": False, "error": _clean(e)})
        res = _status()
        res["ok"] = True
        return json.dumps(res)


def _http_get(url, timeout=60, method="GET"):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": _USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.geturl(), (resp.read() if method == "GET" else b"")


def update(channel="stable"):
    """Download the newest yt-dlp release of `channel`, verify SHA-256 and make it current."""
    try:
        repo = _REPOS.get(channel, _REPOS["stable"])
        final_url, _ = _http_get("https://github.com/%s/releases/latest" % repo, 25, "HEAD")
        m = re.search(r"/tag/([^/?#]+)", final_url)
        if not m:
            raise RuntimeError("could not resolve latest release")
        tag = m.group(1)
        cur = _read_ptr("current")
        if cur == tag:
            return json.dumps({"status": "uptodate", "version": tag})
        base = "https://github.com/%s/releases/download/%s/" % (repo, tag)
        _, data = _http_get(base + "yt-dlp", 120)
        _, sums = _http_get(base + "SHA2-256SUMS", 30)
        expected = None
        for line in sums.decode("utf-8", "replace").splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1].lstrip("*") == "yt-dlp":
                expected = parts[0].lower()
        if not expected or hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError("checksum mismatch - update rejected")
        os.makedirs(_cfg["cache_dir"], exist_ok=True)
        tmp_zip = os.path.join(_cfg["cache_dir"], "yt-dlp-update.zip")
        with open(tmp_zip, "wb") as f:
            f.write(data)
        with _lock:
            name = _install_zip(tmp_zip)
            if cur and cur != name:
                _write_ptr("previous", cur)
            _write_ptr("current", name)
            _prune({name, cur or ""})
        try:
            os.remove(tmp_zip)
        except OSError:
            pass
        return json.dumps({"status": "updated", "version": name})
    except Exception as e:
        return json.dumps({"status": "error", "message": _clean(e)})


# --------------------------------------------------------------------------- argv builders
def _common_argv(o):
    argv = [
        "--ignore-config", "--color", "never", "--quiet", "--no-progress", "--abort-on-error",
        "--socket-timeout", str(int(o.get("timeout", 30))),
        "--retries", "10", "--fragment-retries", "10", "--extractor-retries", "3",
        "--cache-dir", _cfg["cache_dir"], "--no-mtime",
    ]
    qjs = _cfg.get("qjs") or ""
    if qjs and os.path.isfile(qjs):
        argv += ["--js-runtimes", "quickjs:" + qjs]
    cookies = o.get("cookies") or ""
    if cookies and os.path.isfile(cookies):
        argv += ["--cookies", cookies]
    # direct files / streams found by the in-app browser often need the page they came from
    user_agent = o.get("user_agent") or ""
    if user_agent:
        argv += ["--user-agent", user_agent]
    for key, value in (o.get("headers") or {}).items():
        if key and value:
            argv += ["--add-headers", "%s:%s" % (key, value)]
    return argv


def _download_argv(o):
    argv = _common_argv(o)
    argv += ["--no-playlist", "-o", os.path.join(o["tmp_dir"], "media.%(ext)s")]
    n = int(o.get("concurrent") or 1)
    if n > 1:
        argv += ["--concurrent-fragments", str(n)]
    rate = int(o.get("rate_limit") or 0)  # bytes / second
    if rate > 0:
        argv += ["--limit-rate", str(rate)]
    ffmpeg = _cfg.get("ffmpeg") or ""
    if ffmpeg and os.path.isfile(ffmpeg):
        argv += ["--ffmpeg-location", ffmpeg]

    if o.get("kind") == "audio":
        fmt = o.get("audio_format") or "mp3"
        selector = {"m4a": "ba[ext=m4a]/ba/b", "opus": "ba[acodec=opus]/ba/b"}.get(fmt, "ba/b")
        argv += ["-f", selector, "-x", "--audio-format", fmt]
        if fmt in ("mp3", "m4a"):
            argv += ["--audio-quality", "%dK" % int(o.get("audio_bitrate") or 192)]
        if o.get("embed_meta", True):
            argv += ["--embed-metadata"]
        if o.get("embed_thumb") and fmt in ("mp3", "m4a"):
            argv += ["--embed-thumbnail", "--convert-thumbnails", "jpg"]
    else:
        h = int(o.get("max_height") or 0)
        if h <= 0:
            selector = "bv*+ba/b"
        else:
            selector = "bv*[height<=?%d]+ba/b[height<=?%d]/bv*+ba/b" % (h, h)
        # highest resolution first, then prefer H.264 + AAC (plays everywhere), container mp4 or mkv
        argv += ["-f", selector, "-S", "res,vcodec:h264,acodec:m4a",
                 "--merge-output-format", "mp4/mkv"]
        if o.get("embed_meta", True):
            argv += ["--embed-metadata"]
        if o.get("embed_thumb"):
            argv += ["--embed-thumbnail", "--convert-thumbnails", "jpg"]
        if o.get("embed_subs"):
            langs = (o.get("sub_langs") or "en").replace(" ", "")
            argv += ["--write-subs", "--embed-subs", "--sub-langs", langs]
    return argv


def _parse(argv):
    try:
        return _yt.parse_options(argv)[3]  # ParsedOptions(parser, options, urls, ydl_opts)
    except SystemExit:
        raise RuntimeError("invalid yt-dlp options")


class _Logger:
    def __init__(self, state=None):
        self.state = state

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        if self.state is not None:
            self.state["warning"] = _clean(msg)

    def error(self, msg):
        if self.state is not None:
            self.state["last_error"] = _clean(msg)


def _error_kind(msg):
    low = msg.lower()
    if any(k in low for k in ("sign in", "log in", "login", "cookies", "age-restricted", "confirm your age",
                              "private", "members-only", "authentication", "rate-limit", "not a bot")):
        return "login"
    if "unsupported url" in low:
        return "unsupported"
    if any(k in low for k in ("drm", "protected")):
        return "drm"
    if any(k in low for k in ("urlopen error", "timed out", "connection", "network", "resolve")):
        return "network"
    return "other"


def _error(e):
    msg = _clean(e) or e.__class__.__name__
    return {"error": msg, "kind": _error_kind(msg)}


# --------------------------------------------------------------------------- public: info
def _thumb(d):
    t = d.get("thumbnail")
    if t:
        return str(t)
    ts = d.get("thumbnails") or []
    for item in reversed(ts):
        if isinstance(item, dict) and item.get("url"):
            return str(item["url"])
    return ""


def _s(value):
    return "" if value is None else str(value)


def _trim_info(info, url):
    is_playlist = info.get("_type") in ("playlist", "multi_video") or "entries" in info
    out = {
        "url": _s(info.get("webpage_url") or url),
        "title": _s(info.get("title") or info.get("id") or url),
        "uploader": _s(info.get("uploader") or info.get("channel") or info.get("uploader_id")),
        "thumbnail": _thumb(info),
        "duration": int(info.get("duration") or 0),
        "extractor": _s(info.get("extractor_key") or info.get("extractor")),
        "playlist": bool(is_playlist),
    }
    if is_playlist:
        entries = []
        for e in info.get("entries") or []:
            if not isinstance(e, dict):
                continue
            eu = _s(e.get("webpage_url") or e.get("url"))
            if not eu.startswith("http"):
                if e.get("id") and _s(e.get("ie_key")).lower().startswith("youtube"):
                    eu = "https://www.youtube.com/watch?v=" + _s(e["id"])
                else:
                    continue
            entries.append({
                "url": eu,
                "title": _s(e.get("title") or e.get("id") or eu),
                "thumbnail": _thumb(e),
                "duration": int(e.get("duration") or 0),
                "uploader": _s(e.get("uploader") or e.get("channel")),
            })
        out["entries"] = entries
    else:
        formats = []
        for f in info.get("formats") or []:
            vc = _s(f.get("vcodec") or "none")
            ac = _s(f.get("acodec") or "none")
            has_v, has_a = vc != "none", ac != "none"
            if not (has_v or has_a) or _s(f.get("protocol")) == "mhtml":
                continue
            formats.append({
                "id": _s(f.get("format_id")),
                "h": int(f.get("height") or 0),
                "w": int(f.get("width") or 0),
                "v": has_v,
                "a": has_a,
                "ext": _s(f.get("ext")),
                "size": int(f.get("filesize") or f.get("filesize_approx") or 0),
                "abr": float(f.get("abr") or 0),
            })
        out["formats"] = formats
    return out


def fetch_info(url, opts_json):
    yt = _yt
    if yt is None:
        return json.dumps({"error": "Engine is not ready", "kind": "other"})
    try:
        o = json.loads(opts_json or "{}")
        argv = _common_argv(o) + ["--skip-download", "--flat-playlist",
                                  "--playlist-end", str(int(o.get("max_entries", 300)))]
        argv.append("--no-playlist" if o.get("no_playlist") else "--yes-playlist")
        opts = _parse(argv)
        opts["logger"] = _Logger()
        with yt.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if not info:
                raise RuntimeError("No information could be extracted")
            info = ydl.sanitize_info(info)
        return json.dumps(_trim_info(info, url))
    except Exception as e:
        return json.dumps(_error(e))


# --------------------------------------------------------------------------- public: download
_MEDIA_SKIP = (".part", ".ytdl", ".temp", ".json", ".jpg", ".jpeg", ".png", ".webp", ".vtt", ".srt",
               ".ass", ".lrc", ".description")


def _find_media(tmp_dir):
    best, best_size = None, -1
    try:
        names = os.listdir(tmp_dir)
    except OSError:
        return None
    for n in names:
        p = os.path.join(tmp_dir, n)
        low = n.lower()
        if not os.path.isfile(p) or low.endswith(_MEDIA_SKIP) or ".part" in low:
            continue
        # intermediate streams look like media.f137.mp4 - prefer the merged media.mp4
        size = os.path.getsize(p)
        if re.match(r"^media\.f[\w-]+\.", low):
            size = size // 4
        if size > best_size:
            best, best_size = p, size
    return best


def _final_path(info, tmp_dir):
    for d in (info or {}).get("requested_downloads") or []:
        p = d.get("filepath") or d.get("_filename")
        if p and os.path.isfile(p):
            return p
    return _find_media(tmp_dir)


def _make_hooks(state, opts):
    hint = int(opts.get("expected_bytes") or 0)
    c = {"done": 0, "cur_dl": 0, "cur_total": 0, "streams": 0, "extra": 0.0,
         "t": None, "b": 0, "speed": 0.0}

    def overall():
        return c["done"] + c["cur_dl"]

    def publish():
        total_est = max(hint, c["done"] + c["cur_total"] + c["extra"])
        done = overall()
        pct = (done / total_est) if total_est > 0 else 0.0
        state["pct"] = min(0.995, max(pct, state.get("pct", 0.0) if state.get("phase") == "downloading" else 0.0))
        state["done"] = int(done)
        state["total"] = int(total_est)
        now = time.monotonic()
        if c["t"] is None:
            c["t"], c["b"] = now, done
        elif now - c["t"] >= 0.8:
            inst = max(0.0, (done - c["b"]) / (now - c["t"]))
            c["speed"] = inst if c["speed"] == 0 else 0.6 * c["speed"] + 0.4 * inst
            c["t"], c["b"] = now, done
        state["speed"] = int(c["speed"])
        if c["speed"] > 1 and total_est > done:
            state["eta"] = int((total_est - done) / c["speed"])
        else:
            state["eta"] = -1

    def progress_hook(d):
        if state.get("stop"):
            raise _Stop()
        status = d.get("status")
        if status == "downloading":
            state["phase"] = "downloading"
            c["cur_dl"] = int(d.get("downloaded_bytes") or 0)
            c["cur_total"] = int(d.get("total_bytes") or d.get("total_bytes_estimate") or 0)
            if c["streams"] == 0 and c["extra"] == 0:
                info = d.get("info_dict") or {}
                vc, ac = _s(info.get("vcodec") or ""), _s(info.get("acodec") or "")
                video_only = vc not in ("", "none") and ac == "none"
                if video_only and c["cur_total"] > 0:
                    c["extra"] = float(max(hint - c["cur_total"], 0) or c["cur_total"] * 0.12)
            publish()
        elif status == "finished":
            c["done"] += int(d.get("downloaded_bytes") or d.get("total_bytes") or c["cur_dl"])
            c["cur_dl"] = c["cur_total"] = 0
            c["streams"] += 1
            c["extra"] = 0.0 if c["streams"] >= 2 else c["extra"]
            publish()

    def pp_hook(d):
        state["pp"] = _s(d.get("postprocessor"))
        if d.get("status") in ("started", "processing"):
            state["phase"] = "processing"
            state["pct"] = max(state.get("pct", 0.0), 0.99)
            state["speed"] = 0
            state["eta"] = -1

    return progress_hook, pp_hook


def download(task_id, url, opts_json):
    yt = _yt
    if yt is None:
        return json.dumps({"ok": False, "error": "Engine is not ready"})
    state = {"phase": "downloading", "pct": 0.0, "done": 0, "total": 0, "speed": 0, "eta": -1,
             "stop": False, "pp": ""}
    _tasks[task_id] = state
    try:
        o = json.loads(opts_json)
        os.makedirs(o["tmp_dir"], exist_ok=True)
        progress_hook, pp_hook = _make_hooks(state, o)
        opts = _parse(_download_argv(o))
        opts["logger"] = _Logger(state)
        opts["progress_hooks"] = [progress_hook]
        opts["postprocessor_hooks"] = [pp_hook]
        with yt.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
            info = ydl.sanitize_info(info) if info else None
        path = _final_path(info, o["tmp_dir"])
        if not path:
            raise RuntimeError("The download finished but no media file was produced")
        return json.dumps({"ok": True, "path": path})
    except Exception as e:
        if state.get("stop"):
            return json.dumps({"ok": False, "stopped": True})
        # a failing optional post-processor (e.g. thumbnail embedding) must not lose a good file
        if state.get("phase") == "processing":
            try:
                path = _find_media(json.loads(opts_json)["tmp_dir"])
            except Exception:
                path = None
            if path and not path.endswith(".part"):
                return json.dumps({"ok": True, "path": path, "warning": _clean(e)})
        msg = state.get("last_error") or _clean(e) or e.__class__.__name__
        return json.dumps({"ok": False, "error": _clean(msg), "kind": _error_kind(msg)})


def get_state(task_id):
    st = _tasks.get(task_id)
    if not st:
        return "{}"
    return json.dumps({k: st.get(k) for k in ("phase", "pct", "done", "total", "speed", "eta", "pp")})


def request_stop(task_id):
    st = _tasks.get(task_id)
    if st is not None:
        st["stop"] = True
    return "ok"


def forget(task_id):
    _tasks.pop(task_id, None)
    return "ok"
