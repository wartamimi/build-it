# kotlinx.serialization: keep generated serializers for project format classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.buildit.** {
    *** Companion;
}
-keepclasseswithmembers class dev.buildit.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# On-device build tools (ECJ, R8/D8, apksig) run as libraries inside the app. They load message
# bundles, services and classes by name, so they are kept whole rather than shrunk.
-keep class org.eclipse.jdt.** { *; }
-keep class com.android.tools.r8.** { *; }
-keep class com.android.apksig.** { *; }
-keepattributes Signature, Exceptions, EnclosingMethod
-dontwarn org.eclipse.jdt.**
-dontwarn com.android.tools.r8.**
-dontwarn com.android.apksig.**
-dontwarn com.google.auto.value.AutoValue

# Stand-in for javax.lang.model on Android (see app/src/main/java/javax/lang/model).
-keep class javax.lang.model.SourceVersion { *; }
