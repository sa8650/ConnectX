# Building the ConnectX Android Gateway (v2.1.0 / build 19)

## 1. What this app is

ConnectX: Central Communication Gateway powered by Dexter Studio is a **native Kotlin +
Jetpack Compose** app. SMS is delivered with Android `SmsManager` on the selected SIM;
outgoing email history (pushed into ConnectX by client systems) is read-only over HTTPS for
the paired shop. There is **no WebView and no email provider login**. The APK contains
no database key, no email-provider secret, no system API URL and no hard-coded USSD codes.
After the federated administrator sign-in (or pairing-code entry), the phone stores a
**revocable, shop-scoped device token**; the administrator password is never persisted.

The app talks **only** to the **ConnectX Control** website — the official address
`https://connectxweb.pages.dev` is built into the app core; a custom (self-hosted) address
can be set on the sign-in screen's “Can't connect?” flow or in Settings → **ConnectX
Control Address**. It has no EMS dependency of any kind.

## 2. Prerequisites

- Android Studio (latest stable) with **JDK 17** and **Android SDK 35**
- A deployed ConnectX Control website (Cloudflare Pages + D1 + R2 — see its `DEPLOY.md`)
- Your Android release keystore

## 3. Build steps

1. Open this directory in Android Studio; let Gradle sync.
2. Debug build: **Build → Build Bundle(s)/APK(s) → Build APK(s)**, or:
   ```bash
   ./gradlew assembleDebug
   ```
3. Release build: **Build → Generate Signed Bundle / APK → APK** → choose your keystore/key
   alias → `release`. The source declares **v2.1.0 / build 19**, package
   **`com.connectx.gateway`**, in `app/build.gradle.kts`; the About screen reads these
   values from Gradle. Use Android Studio's **Locate** link for the signed output, not
   `app-release-unsigned.apk` (renaming an unsigned APK does not sign it).
4. Unit tests / lint:
   ```bash
   ./gradlew test lint
   ```

> **Package rename note:** v2.x changes the application id from the legacy
> `com.ems.connectx` to `com.connectx.gateway`. Phones running the old EMS-linked build
> must **uninstall it and install the new APK** — Android cannot update across package ids.
> After that, all future updates flow through ConnectX Releases (OTA in-app).

## 4. First-run configuration on the phone

1. **Nothing to enter** — the app connects to the built-in official address
   `https://connectxweb.pages.dev` automatically. Only if that address is unreachable
   (self-hosted ConnectX Control) does the sign-in screen show a **ConnectX Control URL**
   field: use the full `https://` + domain origin, not `https:/`, not a GitHub repository
   link, not an APK URL. A copied `/api` suffix, query string or fragment is removed by
   the validator.
2. **Pick the system** from the dropdown (EMS; others appear as the platform owner
   connects them) and **sign in with your system administrator account** — ConnectX
   verifies it with that system, the phone never talks to the system directly. **Or** use
   **Pair with code** with a pairing code generated in ConnectX Control → Gateways.
3. Pick the **shop** this phone serves (synced from your system account), grant
   SMS/telephony permissions, select the **sending SIM**, then run the **test SMS** (or
   skip and test later from Settings).

Dashboard shows SMS and email sent/pending/failed counts and switches the active shop.
SMS has sending-SIM switch, manual balance and outgoing SMS details. Email has read-only,
paginated outgoing history for the selected shop. Settings has the administrator profile,
change/add connected shop, change SIM, ConnectX Control address (reconfigure), battery
restrictions, disconnect shop, Log out and Send a Test SMS. Administrator password
recovery happens in the administrator's own system (e.g. EMS), not in this app.

**SIM Balance:** the SMS page shows the selected sending SIM's carrier and a masked number.
**Refresh** fetches one matching, active balance dial code from the ConnectX Control
carrier catalog (SIM Carriers page), requests `CALL_PHONE` when needed, and sends one USSD
query to that SIM. No request is made automatically or through the wrong/default SIM.
Until the owner configures a verified code and the carrier supplies a parseable reply, the
card displays **“Balance unavailable.”** No dial codes are built into the APK.

**Updates:** after the URL is saved (and on sign-in, resume, hourly while open, ~6-hourly
in background via WorkManager), the app checks the **public ConnectX release endpoint** —
`GET /api/public/releases/check?package=com.connectx.gateway&versionCode=18` — on your
Control website. A newer published build triggers a notification where permitted; the
About & Updates screen has a manual **Check for Updates** button and shows a real error,
never a false “up to date”. Downloads are verified for size, ZIP header, package name and
advertised build code before Android's installer checks the signing certificate. Publish
APKs in ConnectX Control → **App Releases** (R2 upload or vetted external HTTPS URL).

## 5. Server side (required before the app can sign in)

Deploy the `connectx-control/` project first — its `DEPLOY.md` covers D1/R2 creation,
`SESSION_SECRET`, schema application and first-run owner setup. No EMS deployment,
migration or configuration is involved anywhere in this flow.

## 6. Troubleshooting

| Symptom | Fix |
|---|---|
| “Could not reach …” / login and updates fail | The app uses the built-in address `https://connectxweb.pages.dev` automatically. If your deployment lives elsewhere, open **Can't connect?** on the sign-in screen (or Settings → **ConnectX Control Address**) and enter the **full production URL**. Incomplete URLs are rejected before any network request. |
| “Please sign in” / 401 | Confirm the URL is the public site origin; device routes need a valid `cxd_…` token — re-pair if it was revoked on the website. |
| “APK not available” / 503 | The owner must upload a real signed APK in App Releases (or fix the external HTTPS URL). |
| “Balance unavailable” | Check the SIM's MCC/MNC against the Control → SIM Carriers catalog (active + one verified code), connectivity, and CALL_PHONE permission. |
| Email history unavailable | Confirm the selected shop, an active device pairing, and that client systems actually push email records via `POST /api/client/v1/email` with the same `shop` reference. |
| Update won't install | Check the APK's `com.connectx.gateway` package, build code and signing certificate against the installed app; older `com.ems.connectx` installs must be replaced manually once. |
| Duplicate SMS | Conditional server-side claims prevent double dispatch; a network/telephony timeout can still be ambiguous. Revoke unwanted devices in Control → Gateways. |
