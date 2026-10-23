# The support page calls this bridge by name from JavaScript.
-keepclassmembers class com.dbrij.resolve.ResolveSupportActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
