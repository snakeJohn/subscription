# kotlinx.serialization 反射所需
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.substat.app.data.** { *; }
-keep,includedescriptorclasses class com.substat.app.**$$serializer { *; }
-keepclasseswithmembers class com.substat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
