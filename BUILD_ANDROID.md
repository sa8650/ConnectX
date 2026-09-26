# Build ConnectX in Android Studio

The Android source was compiled with Gradle/Android SDK in this workspace and its local unit tests ran, but **no release-signed APK was built, installed or published here**. Build/sign on your computer with Android Studio and the original app signing key.

ConnectX: Central Communication Gateway powered by Dexter Studio is a **native Kotlin + Jetpack Compose** app. SMS is delivered with Android `SmsManager` on the selected SIM; outgoing EMS email history is read-only over HTTPS for the paired shop. There is **no WebView or email provider login**. The APK contains no Supabase service-role key or privileged EMS/Brevo secret. After administrator sign-in, the phone stores a **revocable, shop-scoped device token**; existing administrator credentials may be retained for shop management, but not the password.

## 1. Install Android Studio

1. Download Android Studio from https://developer.android.com/studio
2. Install it and open it once so the **Android SDK** is installed.
3. In SDK Manager, install:
   - Android SDK Platform **35**
   - Android SDK Build-Tools
   - Android SDK Platform-Tools

## 2. Open this project

1. Copy the `ConnectX` folder to your computer (the folder that contains `settings.gradle.kts` and `app/`).
2. Android Studio → **File → Open** → select that `ConnectX` folder.
3. When prompted **“Gradle wrapper not found / Trust project”**, choose **Trust**.
4. Let Android Studio generate the Gradle wrapper if it asks (or use **File → New → New Module** is not needed — this is already an app module).
5. If the IDE says the Gradle wrapper JAR is missing:
   - **File → Settings → Build, Execution, Deployment → Gradle**
   - Or from a terminal in the project folder, if you have Gradle installed: `gradle wrapper --gradle-version 9.3.1`
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

For a release APK: **Build → Generate Signed Bundle / APK** → **APK** → choose the **same keystore/key alias that signed the app currently installed on this phone (build 15 or 16)** → `release` → generate a signed APK. If Build is hidden in Android Studio, open the **☰ main menu** or use **Ctrl+Shift+A / Cmd+Shift+A**, then search **Generate Signed**. The revised source declares **v1.6.0 / build 17**, package `com.ems.connectx`, in `app/build.gradle.kts`; the About screen reads these values from Gradle. Use the signed output located by Android Studio's **Locate** link, not `app-release-unsigned.apk`. Renaming an unsigned APK does not sign it. Verify its embedded package/build and signing certificate against the installed app before publication; a different key cannot update it. A phone already on build 17 needs a still-higher build code. Never publish metadata that disagrees with the actual binary. No build-17 signed APK was created in this workspace.

## 4. First run on the phone

1. Open ConnectX.
2. **Get started**
3. Enter:
   - **EMS website URL** — copy the exact working address of **your deployed EMS site** from the phone browser (Cloudflare Pages production URL or your custom domain). Example shape: `https://your-real-project.pages.dev`; this is **not** your actual hostname. Use its full `https://` + domain origin, not just `https:/`, `https://`, a GitHub repository, or an APK URL. A copied `/?page=app-store` or `/api` suffix will be removed by the new validator.
   - **Administrator email**
   - **Password**
4. Do **not** enter a Shop ID.
5. Select one or more shops.
6. Allow **SMS** (and phone / notifications) permission.
7. Choose the SIM that should send customer SMS.
8. Send a **test SMS**, or tap **Skip for now** and send it later from Settings.
9. **Dashboard** shows SMS and email sent/pending/failed counts and switches the active shop. **SMS** has sending-SIM switch, manual balance, and outgoing SMS details. **Email** has read-only, paginated outgoing EMS history and full details for the selected shop. Settings has **Log out**, administrator profile and **Send a Test SMS**.
10. Keep the app installed. Optional but recommended: allow **unrestricted battery** so queued SMS still send when the screen is off.

Forgot password uses the same EMS administrator recovery flow as the website.

**SIM Balance:** The SMS page shows the selected sending SIM's carrier and a masked number. **Refresh** fetches one matching, active balance dial code from EMS, requests `CALL_PHONE` permission when needed, and sends one USSD query to that SIM. No request is made automatically or through the wrong/default SIM. Until the owner configures a verified code and Android/the carrier supplies a parseable value, the card displays **“Balance unavailable.”** SMS quota has been removed. Administrator Profile and test SMS remain in **Settings**. See `EMS/SIM_BALANCE_SETUP.md` for schema upgrade and setup.

**Updates:** After entering the EMS URL and signing in, ConnectX checks the public EMS App Store on launch, after login, on resume, and every hour while open. WorkManager checks approximately every six hours in the background (subject to Android scheduling/network/notification permission); a new build triggers a notification where permitted. The About & Updates screen has a manual **Check for Updates** button and shows a real error, not “up to date,” when no signed APK has been published or the server cannot be reached. Update downloads are checked for size, ZIP header, ConnectX package name, and advertised build code before Android's installer checks the signing certificate. The public EMS endpoint does not require an administrator token. Older builds cannot acquire the revised URL validation/background worker until updated. First correct the saved URL even on the old build; when the public EMS API and a real *higher-build* signed APK are published, supported builds can check for it via their existing update screen. See `EMS/APP_STORE_RELEASE.md` for the owner upload and verification steps.

## 5. EMS server (required before the app can log in)

On the EMS deployment:

1. Apply SQL migration `EMS/supabase/migrations/041_connectx_sms_gateway.sql` (Supabase) **or** `EMS/supabase/d1/migration_connectx_gateway.sql` (Cloudflare D1).
2. Deploy the updated EMS API (`functions/_lib/connectx_sms.js`, `functions/api/[[path]].js` plus App Store functions) and `assets/js/app.js`. Android build 17 needs the new **device email read** routes before its Email page works. Existing `connectx_messages` already stores outgoing EMS email; there is **no v1.6 email database migration**. Apply the App Store schema/migration and configure R2 as described in `EMS/APP_STORE_RELEASE.md`.
3. If your deployed EMS already had the two-code carrier catalog, **back up** its database and run Supabase migration **044** or the existing-D1 `supabase/d1/migration_connectx_balance_only.sql` **once**. Fresh D1 `schema.sql` is balance-only; new Supabase installs run 043 then 044. Quota values are deleted, but balance codes and carrier rows remain. Sign in as **EMS platform owner → SIM balance** and keep only verified active balance codes. See `EMS/SIM_BALANCE_SETUP.md`. No dial codes are built into the APK.
4. In the shop: **Settings → Communication** to enable SMS, templates, and device revoke.

Sales, payments, returns, and exchanges still **complete if the phone is offline**. Those SMS jobs stay **pending** until this device claims them.

## Troubleshooting

| Problem | What to do |
|---|---|
| “Unable to resolve host https” / login and updates fail | `https:/` is missing the domain. Copy your **full production EMS URL** from a browser, e.g. `https://your-real-project.pages.dev`, into **EMS Website URL**. Log out and correct it if already saved. Build 17 rejects incomplete URLs before requesting the network. |
| “Please sign in” / 401 | Confirm the EMS URL is the public site origin and deploy EMS's public app-store routes; administrative endpoints still require sign-in. |
| “APK not available” / 503 | The EMS owner must upload a real signed APK to R2; the old GitHub v1.4.0 link returns 404. |
| “Balance unavailable” | Check the selected SIM's MCC/MNC, EMS owner → SIM balance catalog (active + one verified code), connectivity, and CALL_PHONE permission. Carrier/network failures or unparseable replies also show unavailable; see `EMS/SIM_BALANCE_SETUP.md`. |
| Email history unavailable | Deploy the new EMS API first. Confirm the selected shop, active device pairing and administrator/shop status; these endpoints require the shop-scoped device token. No inbound mailbox is provided. |
| Update won't install | Check the APK's `com.ems.connectx` package, build code and signing certificate against the installed app. |
| “Wrong email or password” | Use **administrator** email, not staff User ID or Shop ID. |
| Gradle / SDK errors | Install SDK 35 and JDK 17 in Android Studio. |
| SMS permission denied | Android Settings → Apps → ConnectX → Permissions → SMS. |
| Messages stay pending | Open ConnectX, confirm gateway switch is on, disable battery optimisation, confirm the correct shop is selected. |
| Duplicate SMS | Confirm the job and provider logs before retrying; conditional server claims prevent unclaimed jobs being dispatched, but a network/telephony timeout can still be ambiguous. Revoke unwanted devices in EMS Communication settings. |
