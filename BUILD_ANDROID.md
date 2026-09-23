# Build ConnectX in Android Studio

This environment cannot compile an APK (no Android SDK / Gradle here). Build on a computer with Android Studio.

ConnectX is a **native Kotlin + Jetpack Compose** app. It uses the Android SMS APIs (`SmsManager` + SIM subscription). There is **no WebView**. The APK does **not** contain a Supabase service-role key or any EMS privileged secret. After administrator sign-in, the phone stores only a **revocable device token** for the SMS gateway.

## 1. Install Android Studio

1. Download Android Studio from https://developer.android.com/studio
2. Install it and open it once so the **Android SDK** is installed.
3. In SDK Manager, install:
   - Android SDK Platform **34**
   - Android SDK Build-Tools
   - Android SDK Platform-Tools

## 2. Open this project

1. Copy the `ConnectX` folder to your computer (the folder that contains `settings.gradle.kts` and `app/`).
2. Android Studio → **File → Open** → select that `ConnectX` folder.
3. When prompted **“Gradle wrapper not found / Trust project”**, choose **Trust**.
4. Let Android Studio generate the Gradle wrapper if it asks (or use **File → New → New Module** is not needed — this is already an app module).
5. If the IDE says the Gradle wrapper JAR is missing:
   - **File → Settings → Build, Execution, Deployment → Gradle**
   - Or from a terminal in the project folder, if you have Gradle installed: `gradle wrapper --gradle-version 8.7`
   - Android Studio Ladybug / Koala will usually offer **Create Gradle wrapper**.

6. Create `local.properties` if it is not generated automatically:

```
sdk.dir=C:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
```

On macOS / Linux:

```
sdk.dir=/Users/YOU/Library/Android/sdk
```

## 3. Sync and build

1. **File → Sync Project with Gradle Files**
2. Wait until the status bar shows Gradle sync succeeded.
3. Connect a phone with **USB debugging**, or start an emulator that has a SIM / SMS capability (a **physical phone with a SIM is required** for real SMS).
4. Select the `app` run configuration.
5. Click **Run** (green triangle) or **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. The debug APK will be at:

```
app/build/outputs/apk/debug/app-debug.apk
```

For a release APK: **Build → Generate Signed Bundle / APK**. You must create your own keystore. Do not reuse EMS secrets as the signing key.

## 4. First run on the phone

1. Open ConnectX.
2. **Get started**
3. Enter:
   - **EMS website URL** — the same URL you use in the browser, for example `https://your-project.pages.dev` (no trailing `/api`)
   - **Administrator email**
   - **Password**
4. Do **not** enter a Shop ID.
5. Select one or more shops.
6. Allow **SMS** (and phone / notifications) permission.
7. Choose the SIM that should send customer SMS.
8. Send a **test SMS**, or tap **Skip for now** and send it later from Settings.
9. From Home you can **switch shop** and **switch sending number**. Activity shows the SMS body when you tap a row. Settings has **Log out**.
10. Keep the app installed. Optional but recommended: allow **unrestricted battery** so queued SMS still send when the screen is off.

Forgot password uses the same EMS administrator recovery flow as the website.

## 5. EMS server (required before the app can log in)

On the EMS deployment:

1. Apply SQL migration `EMS/supabase/migrations/041_connectx_sms_gateway.sql` (Supabase) **or** `EMS/supabase/d1/migration_connectx_gateway.sql` (Cloudflare D1).
2. Deploy the updated EMS functions (`functions/_lib/connectx_sms.js`, `functions/api/[[path]].js`, `functions/_lib/db.js`) and `assets/js/app.js`.
3. In the shop: **Settings → Communication** to enable SMS, templates, and device revoke.

Sales, payments, returns, and exchanges still **complete if the phone is offline**. Those SMS jobs stay **pending** until this device claims them.

## Troubleshooting

| Problem | What to do |
|---|---|
| “Please sign in” / 401 | Confirm the EMS URL is the public site origin, not `/api` alone. |
| “Wrong email or password” | Use **administrator** email, not staff User ID or Shop ID. |
| Gradle / SDK errors | Install SDK 34 and JDK 17 in Android Studio. |
| SMS permission denied | Android Settings → Apps → ConnectX → Permissions → SMS. |
| Messages stay pending | Open ConnectX, confirm gateway switch is on, disable battery optimisation, confirm the correct shop is selected. |
| Duplicate SMS | Each job has a unique id and is claimed by one device. Revoke extra devices in EMS Communication settings. |
