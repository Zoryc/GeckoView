# GeckoView, Media3, Room, Coil, OkHttp, Hilt and kotlinx.serialization ship their own consumer rules.
# CastOptionsProvider is kept by cast/consumer-rules.pro (named in the manifest meta-data).

# Detector wire protocol and saved-state models are (de)serialized by kotlinx.serialization.
-keep,includedescriptorclasses class app.snapcast.**$$serializer { *; }
-keepclassmembers class app.snapcast.** { *** Companion; }

-dontwarn org.mozilla.**
