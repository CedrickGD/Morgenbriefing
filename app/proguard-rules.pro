# kotlinx.serialization ships its own consumer rules; keep our @Serializable models' companions explicit
# so R8 full mode never strips generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.cedrickgd.morgenbriefing.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cedrickgd.morgenbriefing.**$$serializer { *; }

# WorkManager instantiates workers reflectively by class name.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# OkHttp optional platform classes
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# -------------------------------------------------------------------------------------------------
# Anthropic Java SDK (AI features; only ever loaded on Android 8.0+, see ai/AiServices.kt)
#
# The SDK jar ships rules that R8 applies on its own (META-INF/proguard/anthropic-java-core.pro):
# annotation attributes, kotlin.Metadata + kotlin.reflect, Jackson enums and TypeReference, and the
# constructors and @Json* members of com.anthropic.**. kotlin-reflect ships its rules, too. With
# SDK 2.68.0 + Jackson 2.19 R8 reports no missing classes, so no -dontwarn is needed.
#
# On top of that, for R8 full mode: Jackson reads the SDK's models reflectively and
# jackson-module-kotlin creates them through kotlin-reflect, which matches Kotlin metadata against
# JVM names and generic signatures. Keep the SDK's class and member *names* (unused classes are
# still removed) and the attributes reflection needs.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepnames class com.anthropic.**
-keepclassmembernames class com.anthropic.** { *; }

# jackson-module-kotlin ships no rules: its instantiators/introspectors are registered by the SDK's
# mapper and work on Kotlin metadata.
-keep class com.fasterxml.jackson.module.kotlin.** { *; }

# Jackson creates (de)serializers named in @JsonSerialize/@JsonDeserialize via no-arg constructors.
-keepclassmembers class * extends com.fasterxml.jackson.databind.JsonDeserializer { <init>(); }
-keepclassmembers class * extends com.fasterxml.jackson.databind.JsonSerializer { <init>(); }
