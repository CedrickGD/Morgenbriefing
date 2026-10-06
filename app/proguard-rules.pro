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
# annotation attributes, kotlin.Metadata + kotlin.reflect, Jackson enums and TypeReference, the
# classes annotated with @JsonSerialize/@JsonDeserialize, and the constructors and @Json* members
# of com.anthropic.**. kotlin-reflect ships its rules, too. With SDK 2.68.0 + Jackson 2.19 R8
# reports no missing classes, so no -dontwarn is needed.
#
# Those rules are NOT enough: they keep annotated members and the members of Jackson's
# annotations, but not annotation types that nothing but annotations refer to. R8 removes such
# an annotation class, every use of it, and whatever its meta-annotations name. That is what
# happened to the SDK's @ExcludeMissing (= @JacksonAnnotationsInside @JsonInclude(CUSTOM,
# valueFilter = JsonField.IsMissing)) on every model property: Jackson then serialized each unset
# field (the first one is cache_control) and failed with "JsonMissing cannot be serialized", an
# IOException, so every Claude request of the release build ended as "Keine Verbindung".
# app/build/outputs/mapping/release/usage.txt must never list a class of com.anthropic.core that
# carries Jackson annotations or is named in an annotation (JsonField and the classes below).

# Attributes Jackson and kotlin-reflect read at runtime: annotations (also on parameters and
# their default values), generic signatures and the nesting of inner classes.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# 1. Every annotation type of the SDK and every Jackson annotation type, with all members, so
#    annotations and meta-annotations survive even when no code mentions their class.
-keep @interface com.anthropic.** { *; }
-keep @interface com.fasterxml.jackson.annotation.** { *; }
-keep @interface com.fasterxml.jackson.databind.annotation.** { *; }

# 2. The SDK's JSON value model (com.anthropic.core, Values.kt) with all nested classes:
#    JsonField and its subclasses JsonValue, JsonMissing, JsonNull, JsonBoolean, JsonNumber,
#    JsonString, JsonArray, JsonObject and KnownValue, plus MultipartField. Jackson builds and
#    writes them through @JsonCreator/@JsonValue, @JsonSerialize/@JsonDeserialize(using = their
#    nested Serializer/Deserializer) and the JsonField.IsMissing value filter of @ExcludeMissing.
-keep class com.anthropic.core.JsonField { *; }
-keep class * extends com.anthropic.core.JsonField { *; }
-keep class com.anthropic.core.JsonField$* { *; }
-keep class com.anthropic.core.JsonValue$* { *; }
-keep class com.anthropic.core.JsonMissing$* { *; }
-keep class com.anthropic.core.JsonNull$* { *; }
-keep class com.anthropic.core.JsonBoolean$* { *; }
-keep class com.anthropic.core.JsonNumber$* { *; }
-keep class com.anthropic.core.JsonString$* { *; }
-keep class com.anthropic.core.JsonArray$* { *; }
-keep class com.anthropic.core.JsonObject$* { *; }
-keep class com.anthropic.core.KnownValue$* { *; }
-keep class com.anthropic.core.MultipartField { *; }
-keep class com.anthropic.core.MultipartField$* { *; }
-keep class com.anthropic.core.BaseSerializer { *; }
-keep class com.anthropic.core.BaseDeserializer { *; }

# 3. Classes that annotations name, which Jackson instantiates reflectively through their no-arg
#    constructor: the SDK's (de)serializers of @JsonSerialize/@JsonDeserialize(using = …) and the
#    value filter of @JsonInclude(valueFilter = JsonField.IsMissing, kept in 2.). The SDK
#    registers no Jackson mix-ins (checked for 2.68.0).
-keep class com.anthropic.** extends com.fasterxml.jackson.databind.JsonSerializer { <init>(...); }
-keep class com.anthropic.** extends com.fasterxml.jackson.databind.JsonDeserializer { <init>(...); }
-keepclassmembers class * extends com.fasterxml.jackson.databind.JsonDeserializer { <init>(); }
-keepclassmembers class * extends com.fasterxml.jackson.databind.JsonSerializer { <init>(); }

# 4. For R8 full mode: Jackson reads the SDK's models reflectively and jackson-module-kotlin
#    creates them through kotlin-reflect, which matches Kotlin metadata against JVM names and
#    generic signatures. Keep the SDK's class and member *names* (unused models are still removed).
-keepnames class com.anthropic.**
-keepclassmembernames class com.anthropic.** { *; }

# jackson-module-kotlin ships no rules: its instantiators/introspectors are registered by the SDK's
# mapper and work on Kotlin metadata.
-keep class com.fasterxml.jackson.module.kotlin.** { *; }
