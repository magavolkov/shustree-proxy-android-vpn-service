# This rule is unnecessary with modern Android Gradle Plugin (AGP > 4.2).
# R8 automatically removes Log calls when isMinifyEnabled is true.
# You can safely remove the following -assumenosideeffects block.
# -assumenosideeffects class android.util.Log { ... }

# Ktor and Kotlinx.serialization Rules
# Keep data classes used for serialization from being renamed or removed,
# as they are accessed via reflection.
-keep public class ru.shustree.shustreeproxy.data.** { *; }
-keepnames public class ru.shustree.shustreeproxy.data.**

# Keep serialization-specific generated classes and metadata.
-keep class kotlin.Metadata
-keepclassmembers public class ** {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclasseswithmembers public class * {
    public static final kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    public <init>(...);
}

# --- CUSTOM RULE TO REMOVE ALL LOGS ---
# By default, R8 only removes d(), v(), and i() logs in release builds.
# This rule tells R8 that w() and e() calls also have no side effects,
# allowing it to remove them completely from the final release APK/AAB.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# Ktor CIO Engine (if you use it, though you have OkHttp)
-keep class io.ktor.client.engine.cio.* { *; }

# Ktor OkHttp Engine (your project uses this)
# This often requires keeping OkHttp internal classes if issues arise.
# Add these if you see crashes related to OkHttp in release builds.
-keep class okhttp3.internal.ws.** { *; }
-keep class okio.** { *; }

# Coroutines
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    private static kotlin.coroutines.MainCoroutineDispatcher a;
}
-keep class kotlin.coroutines.Continuation