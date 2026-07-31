# kotlinx.serialization 反射所需
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.substat.app.data.** { *; }
-keep,includedescriptorclasses class com.substat.app.**$$serializer { *; }
-keepclasseswithmembers class com.substat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor 经 slf4j-api 记日志，但 slf4j 1.7 的绑定类在 Android 上不存在
-dontwarn org.slf4j.**

# OkHttp 可选的 TLS provider，在 Android 运行时同样不存在
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
