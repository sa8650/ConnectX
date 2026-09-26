# ConnectX: Central Communication Gateway powered by Dexter Studio

**Android companion app for EMS · v1.6.0 / build 17**

ConnectX pairs an Android phone to an EMS shop. It **dispatches queued SMS through that shop's selected SIM** and **reads the same shop's outgoing EMS ConnectX email history**. Email sending remains on the EMS website: the Android Email page is **read-only**, not an incoming mailbox or a new email-sending client.

## App navigation

| Page | What it does |
|---|---|
| Dashboard | Active shop, SMS dispatch state, sent/pending/failed counts for **both SMS and email**, and the latest outgoing email. Switch the selected shop here. |
| SMS | Outgoing SMS history and details, confirmed cancellation of **queued** jobs, sending-SIM switch, and manual selected-SIM balance Refresh. SMS delivery continues in the background. |
| Email | Paginated **outgoing** EMS history for the selected shop, including sent, failed, queued, and sending records. Tap one to see recipients, status/error, and the message/document in a safe plain-text view. No external inbox or composition. |
| Settings | Administrator profile, SMS service toggle, test SMS, connected shops, and About & Updates. |

**Privacy:** Android uses a revocable paired-device token, not a Supabase service-role key or an email provider key. Email list/detail requests are limited by EMS to that active device's shop; revoked/inactive devices cannot view them. Email HTML is shown as inert text and is not loaded in a WebView. The app does not cache email bodies on disk. The launcher icon stays **ConnectX** so it remains legible; the full product name appears in the app and EMS App Store preset. Package ID `com.ems.connectx` remains unchanged to preserve update compatibility.

**Balance:** A USSD request happens only after tapping **SMS → SIM Balance → Refresh**, using an EMS owner-configured carrier code on the selected SIM. There are no hard-coded carrier codes or SMS-quota checks. See [EMS/SIM_BALANCE_SETUP.md](../EMS/SIM_BALANCE_SETUP.md).

## Build and deployment

1. Deploy the updated **EMS Pages Functions** (`functions/_lib/connectx_sms.js` and `functions/api/[[path]].js`) before using the Android Email page. EMS already has the `connectx_messages` table; **no new email migration or provider secret on the phone is required**. Shop email sending and permissions stay on the EMS website.
2. Open this directory in Android Studio with JDK 17 and Android SDK 35. Build a signed **v1.6.0 / build 17** release APK using the **same key** as the app installed on your phone. Debug or renamed unsigned APKs cannot replace that installation. No signed release APK has been created or published in this workspace. Details: [BUILD_ANDROID.md](BUILD_ANDROID.md).
3. As EMS platform owner, update the App Store listing to the full brand name, upload the actual signed build-17 APK, and test an optional update before making it mandatory. The quick preset fills text only. See [EMS/APP_STORE_RELEASE.md](../EMS/APP_STORE_RELEASE.md).
4. Sign in with an administrator account and the **full HTTPS URL of the deployed EMS site**; choose the shop and SMS-sending SIM. The SMS gateway needs phone/SMS permissions. Selecting another shop changes **both** SMS and Email page data; it does not grant cross-shop access.

Local verification: **22/22 EMS tests passed** (including real SQLite-backed D1 email reads and conditional SMS cancellation); Android debug compilation, unit tests and lint passed (lint reports non-blocking warnings). Generated debug build files were removed after checking; **no release-signed APK, live deployment, provider account, device installation, or physical SIM was tested here.**

Further architecture and API details: [CONNECTX_DOCUMENTATION.md](CONNECTX_DOCUMENTATION.md). SMS queue deployment: [EMS/CONNECTX_SMS.md](../EMS/CONNECTX_SMS.md).

Built and maintained by **Dexter Studio** for **EMS**.
