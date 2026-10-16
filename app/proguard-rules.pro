# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.killhuishenghuo.** {
    *** Companion;
}
-keepclasseswithmembers class com.killhuishenghuo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.killhuishenghuo.**$$serializer { *; }
-keep class com.killhuishenghuo.data.model.** { *; }

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions

##---------------Begin: ML Kit barcode scanner ----------
#
# The scanner's implementation objects are built at runtime by a small component
# framework (`com.google.firebase.components`) from factories it looks up by
# name, so every class reachable only that way looks unused to R8 and is deleted.
# The result is not a build error and not a JVM test failure — it is a
# NullPointerException on the first frame of the camera, in a **minified build
# only**:
#
#   Scan  opened (permission=true)
#   Scan  error NullPointerException: Attempt to read from field 'xw3 zr3.a'
#       on a null object reference in method 'mu3 i54.b(xk)'
#
# `mapping.txt` resolves those frames:
#
#   mu3 = com.google.mlkit.vision.barcode.internal.zzh      (where it threw)
#   zr3 = com.google.mlkit.vision.barcode.internal.zzg      (the null object)
#   i54 = com.google.android.gms.internal.mlkit_vision_common.zzlu
#
# i.e. the barcode component came back null. The same build's `mapping.txt`
# listed **58 deleted classes** under `com.google.android.gms.internal.mlkit_*`
# and four under `com.google.mlkit.*`, `vision.common.internal.ImageUtils` among
# them.
#
# This was invisible until a phone ran the `benchmark` build, because the scanner
# had only ever been opened on `debug` — where R8 does not run at all. That is
# the project's third bug of this shape, after reflective serialization and the
# frame-metrics probe, and the reason `tools/verify-scan-keeps.ps1` now checks
# every build's mapping for it.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
##---------------End: ML Kit barcode scanner ----------
