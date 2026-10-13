# kotlinx.serialization: keep generated serializers of the app and the core models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.markedoe.ondo.**$$serializer { *; }
-keepclassmembers class com.markedoe.ondo.** { *** Companion; }
-keepclasseswithmembers class com.markedoe.ondo.** { kotlinx.serialization.KSerializer serializer(...); }
