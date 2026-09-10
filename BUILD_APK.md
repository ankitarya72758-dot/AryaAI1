# Build the APK

Use the current stable Android Studio release. As of September 2026, Android Developers lists Android Studio Quail 4 / 2026.1.4 as stable.

1. Open this project in Android Studio.
2. Wait for Gradle Sync.
3. Connect your Android phone or start an emulator.
4. For a debug APK: **Build → Build APK(s)**.
5. For a signed release: **Build → Generate Signed App Bundle / APK → APK**, then create/select a keystore.
6. Install the generated APK on your phone.

Before testing video generation, start the `server` backend and save its URL under **Backend Settings** in Arya AI.

Never put the Runway API secret in the Android project.
