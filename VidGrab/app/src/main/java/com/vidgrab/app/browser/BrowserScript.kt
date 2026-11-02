package com.vidgrab.app.browser

const val BRIDGE_NAME = "VidGrabBridge"

/**
 * Injected into every frame of every page (document start). It reports <video> / <audio> elements the
 * moment they start playing. It only *reports*: nothing is ever downloaded without the user's tap.
 */
val DETECTOR_JS: String = """
(function () {
  try {
    if (window.__vgDetector) return;
    window.__vgDetector = true;
    var bridge = window.VidGrabBridge;
    if (!bridge || typeof bridge.onMedia !== 'function') return;
    var sent = {};
    var rate = 1;
    function applyRate() {
      var list = document.querySelectorAll('video,audio');
      for (var i = 0; i < list.length; i++) {
        try { list[i].defaultPlaybackRate = rate; list[i].playbackRate = rate; } catch (x) {}
      }
    }
    function forwardRate() {
      var frames = document.querySelectorAll('iframe');
      for (var i = 0; i < frames.length; i++) {
        try { frames[i].contentWindow.postMessage({ __vg: 'rate', rate: rate }, '*'); } catch (x) {}
      }
    }
    // playback speed: the app calls this; nested frames are told through postMessage
    window.__vgSetSpeed = function (r) { rate = r; applyRate(); forwardRate(); };
    window.addEventListener('message', function (e) {
      var d = e.data;
      if (d && typeof d === 'object' && d.__vg === 'rate' && typeof d.rate === 'number') {
        rate = d.rate; applyRate(); forwardRate();
      }
    });
    function report(el, ev) {
      try {
        if (el.loop && el.muted) return;
        var dur = (typeof el.duration === 'number' && isFinite(el.duration)) ? Math.round(el.duration) : 0;
        if (dur > 0 && dur < 4) return;
        var src = el.currentSrc || el.src || '';
        if (!src && el.querySelector) { var s = el.querySelector('source'); if (s) src = s.src || ''; }
        var key = src + '|' + location.href;
        var now = Date.now();
        if (sent[key] && now - sent[key] < 4000) return;
        sent[key] = now;
        bridge.onMedia(JSON.stringify({
          src: src, frame: location.href, top: window === window.top,
          title: document.title || '', poster: el.poster || '',
          duration: dur, w: el.videoWidth || 0, h: el.videoHeight || 0,
          audio: el.tagName === 'AUDIO', ev: ev
        }));
      } catch (e) {}
    }
    ['play', 'playing'].forEach(function (ev) {
      document.addEventListener(ev, function (e) {
        var t = e.target;
        if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
          if (rate !== 1 && t.playbackRate !== rate) { try { t.playbackRate = rate; } catch (x) {} }
          report(t, ev);
        }
      }, true);
    });
    // also callable from the app (after in-page navigation, where the same <video> keeps playing)
    function scan() {
      var list = document.querySelectorAll('video,audio');
      for (var i = 0; i < list.length; i++) {
        var v = list[i];
        if (!v.paused && !v.ended && v.readyState > 2) report(v, 'scan');
      }
    }
    window.__vgScan = scan;
    setTimeout(scan, 800);
  } catch (e) {}
})();
""".trimIndent()
