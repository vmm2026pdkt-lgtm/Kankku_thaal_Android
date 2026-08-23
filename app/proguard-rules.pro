# Keep WebView JavaScript interface methods (none are currently exposed,
# but this keeps the app safe if one is added later).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
