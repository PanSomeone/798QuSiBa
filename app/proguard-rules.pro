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
