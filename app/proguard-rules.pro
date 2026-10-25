# GeckoView, Media3, Room and kotlinx.serialization ship their own consumer rules.

# Referenced by name from the cast module's manifest meta-data.
-keep class app.snapcast.cast.CastOptionsProvider { <init>(); }

# Detector wire protocol and saved-state models are (de)serialized by kotlinx.serialization.
-keep,includedescriptorclasses class app.snapcast.**$$serializer { *; }
-keepclassmembers class app.snapcast.** { *** Companion; }

-dontwarn org.mozilla.**
