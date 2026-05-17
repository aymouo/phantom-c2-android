-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
-dontpreverify

-assumenosideeffects class android.util.Log {
    public static int v(...); public static int d(...);
    public static int i(...); public static int w(...);
    public static int e(...);
}

-keep class com.openaccess.sdk.MainActivity { *; }
-keep class com.openaccess.sdk.OpenAccessApp { *; }
-keep class com.openaccess.sdk.service.** { *; }
-keep class com.google.system.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class org.json.** { *; }
-keepattributes Signature, *Annotation*
