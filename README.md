# கணக்கு தாள் — Android WebView Wrapper

This is an Android Studio project that packages the **existing, unmodified**
`கணக்கு தாள்` web app (from `kanakku-netlify-site.zip`) as a native Android
app, using a WebView. No redesign, no rewritten UI — `index.html`,
`admin.html`, `manifest.json`, `sw.js`, and the icon set are copied in as-is
under `app/src/main/assets/www/`.

## What's in this project

```
android-project/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/.../MainActivity.kt      ← the entire native layer
│   │   ├── res/                          ← launcher icon, theme, strings
│   │   └── assets/www/                   ← YOUR ORIGINAL FILES, untouched
│   │       ├── index.html
│   │       ├── admin.html
│   │       ├── kanakku-debug.html
│   │       ├── manifest.json
│   │       ├── sw.js
│   │       └── icons/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── .github/workflows/android-build.yml   ← CI, same pattern as your Flutter repo
```

## How to build it

### Option A — Android Studio (recommended, easiest)

1. Open Android Studio → **Open** → select the `android-project` folder.
2. Android Studio will offer to generate the missing Gradle wrapper
   (`gradlew` / `gradle-wrapper.jar`) automatically on first sync — accept
   it, or run **File → Sync Project with Gradle Files**.
   *(These two files are binary/auto-generated, so they aren't included —
   everything else needed to build is here.)*
3. Build → **Build Bundle(s)/APK(s) → Build APK(s)**.
4. Debug APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — Command line, if you have Gradle installed

```bash
cd android-project
gradle wrapper --gradle-version 8.7   # one-time, generates gradlew
./gradlew assembleDebug               # debug APK
./gradlew assembleRelease             # release APK (unsigned/debug-signed until you add a keystore)
./gradlew bundleRelease               # release AAB for Play Store
```

### Option C — CI/CD (GitHub Actions, matches your Flutter repo's setup)

`.github/workflows/android-build.yml` builds debug + release APK and an AAB
on every push, using Gradle directly (no wrapper needed in CI) and uploads
them as workflow artifacts.

- If you drop this project into your existing `Kankku_thaal_new` repo as an
  `android/` subfolder, the workflow works unchanged.
- If it's a standalone repo instead, edit the workflow: remove
  `working-directory: android` and the `android/` path prefixes.

**Release signing (optional):** add these repo secrets and the release
build will be properly signed instead of falling back to the debug key:
`ANDROID_KEYSTORE_BASE64` (base64 of your `.keystore` file),
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

## Android-specific changes made (per your "don't change anything not required" rule)

Nothing inside `index.html`, `admin.html`, `manifest.json`, or `sw.js` was
edited. All changes live entirely in the native Android layer:

1. **Serving strategy** — files are served from
   `https://appassets.androidplatform.net/assets/www/...` via
   `WebViewAssetLoader`, not `file://`. This is required so `fetch()`, the
   Supabase client, and CORS behave like they do on a real HTTPS origin
   (Netlify), and so `sw.js` can register at all — WebView disallows service
   workers on `file://`.
2. **External links** — `wa.me`/WhatsApp, `tel:`, `mailto:`, and any link to
   a host outside your app's known set (Supabase, jsDelivr, cdnjs, Google
   Fonts) are handed off to a real Android app/browser via `Intent`, instead
   of trying to load inside the WebView.
3. **Downloads** — the app's PDF/CSV/Excel export links and `data:` URI
   downloads are caught by a `DownloadListener` and saved via
   `DownloadManager` (or directly to app storage for `data:` URIs), since a
   plain WebView can't save files on its own.
4. **File uploads** — `<input type="file">` is wired to Android's native
   file/document picker via `onShowFileChooser`.
5. **Back button** — closes an open modal/sheet/menu first (by removing the
   same `open` CSS class your own `close*()` functions already use), then
   falls back to WebView history, then requires a second press to exit —
   instead of the OS default of instantly killing the app.
6. **Offline banner** — a small native screen (not part of your HTML) only
   appears if the very first page load fails with no cached copy available;
   once anything has loaded once, transient network hiccups are left to your
   existing service worker/app logic to handle, exactly as on the web.
7. **Permissions** — only `INTERNET` and `ACCESS_NETWORK_STATE` are
   requested (plus a legacy storage permission capped at API 28 for old
   devices). No camera, location, or notification permission is requested,
   since the current app doesn't use any of them; `onPermissionRequest`
   denies anything unexpected by default rather than silently granting it.
8. **App identity** — app name, launcher icon, background/theme color, and
   portrait lock are all pulled directly from your existing `manifest.json`
   (`கணக்கு தாள்`, `icon-512.png`/`icon-maskable-512.png`, `#0f1117`).

## Notes / things to double check

- **Package name**: set to `com.karyakartha.kanakkuthaal`. Rename via
  Android Studio's refactor tool if you'd prefer something else before your
  first Play Store upload (it can't be changed after publishing).
- **`kanakku-debug.html`** was included in the ZIP and copied into assets
  for completeness, but nothing links to it from `index.html`/`admin.html` —
  confirm whether you want it shipped in the production APK or removed.
- **minSdk 24 / targetSdk 34** — covers effectively all active Android
  devices; raise `minSdk` only if you need a feature that requires it.
- This wrapper doesn't add a splash screen, extra libraries, or SDKs beyond
  what's required to host the WebView, per your "no unnecessary
  dependencies" instruction — first frame the user sees is your app's own
  dark background, then `index.html` itself.
