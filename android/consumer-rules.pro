# Keep all serenegiant USB camera classes — native methods are called from
# libUVCCamera.so via RegisterNatives; R8 can't see these callers.
-keep class com.serenegiant.** { *; }
