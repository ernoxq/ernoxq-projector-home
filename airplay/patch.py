#!/usr/bin/env python3
"""
Turns the open-source AirPlay receiver (github.com/jqssun/android-airplay-server, GPL-3.0) into
Projector Home's own receiver. Usage: patch.py <receiver checkout> <version code>

- Own package (md.grin.airplay), signed with Projector Home's key, ARM only (the projector is ARM).
- Music over AirPlay plays in the background: no window opens just because someone connected.
- The window opens only when screen mirroring or a video starts, dark from the very first frame,
  with the Apple TV-style connecting animation (GrinShell.kt), and closes itself on disconnect.

Every edit must match exactly; if the receiver's code changes, the build stops here instead of
producing something half-patched.
"""
import pathlib
import shutil
import sys

root = pathlib.Path(sys.argv[1])
version = int(sys.argv[2])
here = pathlib.Path(__file__).parent
src = root / "app/src/main/kotlin/io/github/jqssun/airplay"


def edit(path, old, new, count=1):
    p = root / path if not isinstance(path, pathlib.Path) else path
    s = p.read_text()
    n = s.count(old)
    if n != count:
        sys.exit(f"patch failed: {p}: expected {count} match(es), found {n}:\n{old}")
    p.write_text(s.replace(old, new))


# --- build: own id, version, ARM only -------------------------------------------------------
edit("app/build.gradle.kts", 'applicationId = "io.github.jqssun.airplay"', 'applicationId = "md.grin.airplay"')
edit("app/build.gradle.kts", "versionCode = 36", f"versionCode = {version}")
edit("app/build.gradle.kts", 'val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")',
     'val allAbis = listOf("arm64-v8a", "armeabi-v7a")')

# --- service: no window for music; a window for mirroring and video -------------------------
svc = src / "service/AirPlayService.kt"
edit(svc, "if (firstConnection && shouldLaunchOnConnect()) launchMainActivity()",
     "// Projector Home: nothing opens on connect; music keeps playing in the background.")
edit(svc, "if (running) videoRenderer.startSession() else {",
     "if (running) { videoRenderer.startSession(); launchMainActivity() } else {")
edit(svc, "        _videoPlaybackActive.value = true\n        airPlayVideoPlayer.play(location, startPositionSeconds)",
     "        _videoPlaybackActive.value = true\n        launchMainActivity()\n        airPlayVideoPlayer.play(location, startPositionSeconds)")

# --- activity: always dark, wrapped in the Apple TV-style shell ------------------------------
act = src / "MainActivity.kt"
edit(act, """        setContent {
            AirPlayTheme {
                MainScreen(
                    viewModel = viewModel,
                    isInPip = isInPip.value,
                    onSurfaceAvailable = { viewModel.onSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onSurfaceDestroyed(it) },
                    onPip = { enterPip() },
                    onLocalNetwork = ::_requestLocalNetwork
                )
            }
        }""", """        setContent {
            AirPlayTheme(darkTheme = true) {
                io.github.jqssun.airplay.ui.GrinShell(viewModel) {
                    MainScreen(
                        viewModel = viewModel,
                        isInPip = isInPip.value,
                        onSurfaceAvailable = { viewModel.onSurfaceAvailable(it) },
                        onSurfaceDestroyed = { viewModel.onSurfaceDestroyed(it) },
                        onPip = { enterPip() },
                        onLocalNetwork = ::_requestLocalNetwork
                    )
                }
            }
        }""")
shutil.copy(here / "GrinShell.kt", src / "ui/GrinShell.kt")

# --- window theme: black from the first frame, never a white flash ----------------------------
dark = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AirPlayServer" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/black</item>
        <item name="android:colorBackground">@android:color/black</item>
        <item name="android:statusBarColor">@android:color/black</item>
        <item name="android:navigationBarColor">@android:color/black</item>
        <item name="android:windowAnimationStyle">@style/GrinFade</item>
    </style>
    <style name="GrinFade" parent="@android:style/Animation.Activity">
        <item name="android:activityOpenEnterAnimation">@android:anim/fade_in</item>
        <item name="android:activityOpenExitAnimation">@android:anim/fade_out</item>
        <item name="android:activityCloseEnterAnimation">@android:anim/fade_in</item>
        <item name="android:activityCloseExitAnimation">@android:anim/fade_out</item>
    </style>
</resources>
"""
res = root / "app/src/main/res"
for d in ["values", "values-night", "values-television"]:
    f = res / d / "themes.xml"
    if f.exists():
        f.write_text(dark)

print(f"receiver patched: md.grin.airplay versionCode {version}")
