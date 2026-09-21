# ONNX Runtime reaches its native library through JNI, so its classes must keep their names.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# jump3r (the LAME port used for MP3 export) is wired together reflectively in places.
-keep class de.sciss.jump3r.** { *; }
-dontwarn de.sciss.jump3r.**

# PDFBox-Android refers to desktop classes that are never loaded on Android.
-dontwarn org.apache.pdfbox.**
-dontwarn com.tom_roush.pdfbox.**
-dontwarn javax.imageio.**
-dontwarn java.awt.**
-dontwarn javax.naming.**

# okio and okhttp ship optional platform hooks that R8 cannot see.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization generates serializers that are looked up by name.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.soundbound.** {
    *** Companion;
}
-keepclasseswithmembers class app.soundbound.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Soundbound's own model classes are serialised; keep their fields.
-keep class app.soundbound.core.library.** { *; }
-keep class app.soundbound.core.prefs.** { *; }
