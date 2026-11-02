# Third-party components

| Component | Used for | License |
|---|---|---|
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) (+ yt-dlp-ejs) | extracting and downloading media from 1000+ sites (bundled release, updated at run time) | Unlicense |
| [QuickJS-ng](https://github.com/quickjs-ng/quickjs) (`libqjs.so`, prebuilt static binary from the official release) | JavaScript runtime that yt-dlp needs for YouTube | MIT |
| [Chaquopy](https://chaquo.com/chaquopy/) | embeds CPython 3.13 in the app | MIT (Python itself: PSF License) |
| FFmpeg binaries from [youtubedl-android](https://github.com/JunkFood02/youtubedl-android) (`io.github.junkfood02.youtubedl-android:ffmpeg`) | merging video + audio, MP3 / M4A conversion, embedding metadata | the FFmpeg build contains GPL components (e.g. x264): if you distribute the APK you must comply with the GPL |
| Jetpack Compose, AndroidX (including androidx.webkit), Material 3 | user interface, in-app browser helpers | Apache 2.0 |
| Android System WebView (Chromium) | the in-app browser engine (provided by the device, not bundled) | BSD-style (Chromium) |
| [Coil](https://coil-kt.github.io/coil/) | thumbnails | Apache 2.0 |
| Kotlin, kotlinx.coroutines | language / concurrency | Apache 2.0 |

The code in `app/src/main/java` and `app/src/main/python/vidgrab_engine.py` was written for this project.
