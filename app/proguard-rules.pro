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

# Readability4J logs through slf4j-api without a binding
-dontwarn org.slf4j.**
