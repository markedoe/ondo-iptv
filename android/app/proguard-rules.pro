# kotlinx.serialization: keep generated serializers of the app and the core models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.markedoe.ondo.**$$serializer { *; }
-keepclassmembers class com.markedoe.ondo.** { *** Companion; }
-keepclasseswithmembers class com.markedoe.ondo.** { kotlinx.serialization.KSerializer serializer(...); }

# Readable crash reports: line numbers, and the app's own class and method names (Settings → About shows the last
# crash on the device; Google Play gets the mapping file for the rest).
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-keepnames class com.markedoe.ondo.** { *; }
