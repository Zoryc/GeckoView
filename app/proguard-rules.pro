# GeckoView ships its own consumer rules. Keep our extension message models intact.
-keep class app.snapcast.core.detection.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.mozilla.**
