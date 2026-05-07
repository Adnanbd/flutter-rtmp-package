# RootEncoder (com.pedro.*) — uses reflection + GL-thread dispatch on filter
# internals. When consumer apps enable R8/minify, mangling these classes
# silently breaks ImageObjectFilterRender so no overlays render.
-keep class com.pedro.** { *; }
-keep interface com.pedro.** { *; }
-keepclassmembers class com.pedro.** { *; }
-dontwarn com.pedro.**

# Plugin's own RTMP / overlay packages — ConnectChecker callbacks and
# ImageObjectFilterRender registration cross R8's reachability blind spot.
-keep class com.flutterrtmp.broadcaster.rtmp.** { *; }
-keep class com.flutterrtmp.broadcaster.overlay.** { *; }

# serenegiant USB camera — native methods called from libUVCCamera.so via
# RegisterNatives; R8 can't see these callers.
-keep class com.serenegiant.** { *; }
