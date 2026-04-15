# Keep accessibility service
-keep class com.osrsbot.autotrainer.OSRSAccessibilityService { *; }
-keep class com.osrsbot.autotrainer.BotService { *; }
-keep class com.osrsbot.autotrainer.MainActivity { *; }

# Keep all model classes
-keep class com.osrsbot.autotrainer.utils.** { *; }
-keep class com.osrsbot.autotrainer.selector.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
