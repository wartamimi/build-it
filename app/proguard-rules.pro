# kotlinx.serialization: keep generated serializers for project format classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.buildit.** {
    *** Companion;
}
-keepclasseswithmembers class dev.buildit.** {
    kotlinx.serialization.KSerializer serializer(...);
}
