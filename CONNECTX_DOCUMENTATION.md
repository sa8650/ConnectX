# ConnectX: Central Communication Gateway powered by Dexter Studio
## Complete Technical Documentation & Architecture Reference

---

## 1. Executive Summary & Architectural Overview

**ConnectX** is the EMS Android companion for local SIM-based SMS dispatch **and read-only access to the paired shop's outgoing EMS ConnectX email history**. The app is branded **ConnectX: Central Communication Gateway powered by Dexter Studio** (short launcher label: ConnectX). It does not fetch incoming mailbox mail or compose/send email on Android; use the EMS shop website to send email.

For SMS, ConnectX claims queued transaction messages from EMS and sends them using the selected physical SIM. For email, the paired device token permits **read-only, shop-scoped** access to existing `connectx_messages`; the EMS server retains Brevo credentials and rejects revoked/inactive devices. Email HTML is converted to inert text on Android, not executed in a WebView. SMS still works in the background while the Email page is closed.

```
┌────────────────────────────────────────────────────────────────────────┐
│                              EMS CLOUD / POS                           │
│  - Point of Sale Checkout (Sales, Dues, Returns, Exchanges, Payments)  │
│  - SMS queue + outgoing email history (connectx_sms_messages,           │
│    connectx_messages)                                                   │
│  - Official App Store & APK Release Distribution (Cloudflare R2)       │
│  - Administrator Console & Token Issuance                              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ HTTPS (Bearer Device / Admin Token)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                     CONNECTX ANDROID GATEWAY ENGINE                    │
│                                                                        │
│  ┌───────────────────────┐  ┌───────────────────────────────────────┐  │
│  │   UI & DESIGN SYSTEM  │  │         BACKGROUND SERVICES           │  │
│  │  - Pure White Light   │  │  - GatewayService (Foreground)        │  │
│  │  - Azure Blue Accent  │  │  - QueueProcessor (Periodic Drain)    │  │
│  │  - SMS & Email tabs   │  │  - QueueWorker (WorkManager 15m)      │  │
│  │  - BackHandler Nav    │  │  - BootReceiver (Auto-restart)        │  │
│  │  - Dedicated About    │  │  - SmsSentReceiver (Multipart ACK)    │  │
│  │  - In-App APK Modal   │  │  - FileProvider Package Installer     │  │
│  └───────────────────────┘  └───────────────────────────────────────┘  │
│                                   │                                    │
│  ┌────────────────────────────────┴─────────────────────────────────┐  │
│  │                HARDWARE TELEPHONY & TELECOM STACK                │  │
│  │  - SubscriptionManager (Multi-SIM Detection & Slot Mapping)      │  │
│  │  - SmsManager (Multipart SMS Division & Broadcast Intents)       │  │
│  │  - EncryptedSharedPreferences (AES-256-GCM Keystore Storage)     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ GSM / LTE / 5G Cellular Radio
                                    ▼
                      ┌───────────────────────────┐
                      │    CUSTOMER MOBILE PHONE  │
                      └───────────────────────────┘
```

---

## 2. Design System & UI Architecture (Mobbin Light Theme)

ConnectX utilizes a **Clean Modern Light Theme** built with Jetpack Compose and Material 3, inspired by top fintech and utility apps on Mobbin (Linear, Stripe, Revolut).

### 2.1 Color Palette

| Token | Hex / Value | Usage |
| :--- | :--- | :--- |
| **PureWhite** | `#FFFFFF` | Main background canvas, cards, modal sheets, status bar. |
| **PearlBg** | `#F8FAFC` | Secondary container surface, input field fill, skeleton base. |
| **PearlSurface** | `#F1F5F9` | Elevated badges, button hover states, divider backgrounds. |
| **PearlElevated** | `#E2E8F0` | Subtle elevation layers, interactive button containers. |
| **PrimaryBlue** | `rgb(35, 131, 226)` (`#2383E2`) | Primary brand accent, CTA buttons, active tab indicators, focus rings. |
| **PrimarySubtle** | `#EFF6FF` | Selected card container, subtle badge backgrounds. |
| **BorderSubtle** | `#E2E8F0` | 1dp clean card borders, list dividers. |
| **BorderMedium** | `#CBD5E1` | Enhanced card borders, inactive step indicators. |
| **TextPrimary** | `#0F172A` | Deep slate high-contrast headings, titles, active labels. |
| **TextSecondary** | `#475569` | Cool slate body text, subtitles, descriptions. |
| **TextMuted** | `#94A3B8` | Timestamps, placeholders, inactive hints. |
| **AccentEmerald** | `#059669` / `#ECFDF5` | "Sent" status, "Online" indicator, Sales Invoice badges. |
| **AccentAmber** | `#D97706` / `#FFFBEB` | "Queued" / "Pending" status, Due Invoice Reminder badges. |
| **AccentRose** | `#DC2626` / `#FEF2F2` | "Failed" / "Cancelled" status, destructive actions. |
| **AccentPurple** | `#7C3AED` / `#F5F3FF` | Exchange Invoice Confirmation badges. |
| **AccentCyan** | `#0891B2` / `#ECFEFF` | Return Invoice Confirmation badges. |

### 2.2 Core UI Components

- **`AppCard`**: Solid white/pearl surface card with 1dp border (`BorderSubtle`), rounded corners (14–18dp), and optional ripple click handling. Completely eliminates frosted glass / blur artifacts.
- **`StatusPill`**: Compact pill chip with colored status dot (Emerald, Amber, Rose) and semantic typography.
- **`MessageTypeBadge`**: Distinct category tag styled specifically for transaction types.
- **`ConnectXLoader`**: High-performance dual-ring circular progress spinner in primary Azure Blue (`rgb(35, 131, 226)`).
- **`ShimmerBox`**: Linear gradient animated shimmer skeleton for smooth loading states on activity feeds and shop lists.
- **`modernFieldColors()`**: Outlined text field styling with pearl background (`#F8FAFC`), crisp focus border (`#2383E2`), and clear labels.

---

## 3. Navigation & System Back Gesture Engine (`BackHandler`)

ConnectX integrates Jetpack Compose `BackHandler` at the root application level (`AppRoot`), ensuring the native Android system back gesture and navigation buttons navigate hierarchical sub-screens safely rather than immediately terminating the app:

### 3.1 Screen Hierarchy & Back Stack Rules

```
Dashboard / Login (Tab 0) ───[Back]───► Requires 2nd back press within 2s to exit app
       │
       ├──► SMS (Tab 1) ────────────[Back]───► Dashboard (Tab 0)
       │     (SIM switch/balance and SMS history)
       │
       ├──► Email (Tab 2) ─────────[Back]───► Dashboard (Tab 0)
       │     (selected shop's outgoing EMS history, read-only)
       │
       ├──► Settings (Tab 3) ───────[Back]───► Dashboard (Tab 0)
       │         │
       │         └──► About & Updates Screen ───[Back]───► Settings (Tab 3)
       │
       └──► Shop Pairing Wizard
                 ├──► Shop List Screen ───[Back]───► Settings / Login
                 ├──► Permissions Screen ───[Back]───► Shop List Screen
                 ├──► SIM Setup Screen ───[Back]───► Permissions Screen
                 ├──► Device Registration ───[Back]───► SIM Setup Screen
                 └──► Test SMS Screen ───[Back]───► Dashboard (Tab 0)
```

### 3.2 Modal & Lock Screen Dismissal
- If the **In-App Installation Wizard** modal is currently open, pressing back dismisses the modal without closing the current activity.
- If a **Mandatory Update Lock** is active, normal screen navigation is blocked and the user is prompted to press back twice if they intend to exit.

---

## 3.3 Dashboard, SMS balance and Email history

The dashboard shows the active shop and **both SMS and email sent/pending/failed figures** with the latest outgoing email; it has no SIM switch or USSD button. Administrator Profile and Send a Test SMS remain in **Settings**. The **SMS page** holds the sending-SIM switch and SIM Balance card. The card detects the selected sending SIM's MCC/MNC (fallback: exact Android carrier name) and displays a masked number. On **Refresh**, it reads one owner-managed EMS balance code, requests `CALL_PHONE` permission if necessary, and makes **one** USSD request through `TelephonyManager.createForSubscriptionId(...).sendUssdRequest(...)` on the selected SIM, not the default calling SIM. Unknown, disabled, denied, timed-out, negative, ambiguous, or unparseable results display **“Balance unavailable”**. No dial codes or live balances are hard-coded, no raw replies are sent to EMS, and there is no SMS-quota query or counter. The authenticated device route remains `GET /api/connectx/gateway/sim-carrier`. See `EMS/SIM_BALANCE_SETUP.md`.

The **Email page** loads 30 outgoing messages at a time for the active shop and provides **Load older emails**. Records show status, recipient, subject, date and provider error; opening one fetches its full body and To/CC/BCC details. The Android app does not load remote images or scripts from email HTML.


---

## 4. Dedicated "About & Updates" Screen

ConnectX features a clean, dedicated **About & Updates** screen accessible from Settings:

- **Installed identity and server update metadata**: local resources provide the installed brand/description; EMS App Store provides the available update details:
  - Installed app identity from local resources: **ConnectX: Central Communication Gateway powered by Dexter Studio** (never overwritten by an older App Store listing).
  - Installed Version & Build from Gradle (`v1.6.0`, `Build 17`).
  - Target Android Platform (`Android 8.0+`)
  - Latest App Store Version & Build (for example, `v1.6.0`, `Build 17`, when that signed APK is published)
  - Binary Package Size (`8.2 MB`)
  - Release / Update Date
  - Formatted Release Notes / Changelog
- **Package Name Isolation**: Technical package names (e.g., `com.ems.connectx`) are completely hidden from the user interface to ensure a polished consumer presentation.
- **On-Demand Update Checker**: Tapping "Check for Updates" queries the EMS App Store endpoint (`/api/app-store/check-update?package=com.ems.connectx`) in real time with loader animations and Toast feedback.

---

## 5. In-App Automatic Installation Wizard (OTA Updates)

ConnectX includes an automated, step-by-step Over-The-Air (OTA) APK download and installation wizard:

### 5.1 Update Flow Architecture

```
┌────────────────────────────────────────────────────────┐
│ 1. VERSION DETECTION                                   │
│    App queries /api/app-store/check-update             │
│    Compares server versionCode (e.g., 15) > 14         │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ 2. IN-APP DOWNLOAD WIZARD MODAL                        │
│    Streams APK byte buffer via OkHttpClient            │
│    Calculates downloaded bytes & percentage progress   │
│    Saves binary safely to Context.cacheDir/updates/    │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ 3. FILEPROVIDER & PERMISSIONS                          │
│    Verifies REQUEST_INSTALL_PACKAGES permission        │
│    Generates content:// URI via FileProvider           │
│    Grants FLAG_GRANT_READ_URI_PERMISSION to Installer  │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────┐
│ 4. ANDROID SYSTEM PACKAGE INSTALLER                    │
│    Dispatches Intent.ACTION_VIEW with APK MIME type    │
│    Prompts standard Android "Update this app?" UI      │
└────────────────────────────────────────────────────────┘
```

### 5.2 Mandatory Update Screen Enforcement
When an EMS Owner marks a release as `mandatory = true`:
- The application displays a fullscreen, blocking **Mandatory Update Screen**.
- Normal tabs and features are locked until the user installs the update.
- Users can click **Install Update Now** to immediately initiate the automated download wizard.

---

## 6. Message Types & Smart Formatting

ConnectX parses and formats all incoming SMS jobs into clear human-readable titles, replacing any missing or `"null"` strings:

```kotlin
fun formatMessageType(type: String?, eventType: String?, messageBody: String?): String
```

| Message Type | Badge Color | Triggering Event in EMS |
| :--- | :--- | :--- |
| **Sales Invoice Confirmation** | Emerald (`#059669`) | Customer makes a purchase at POS checkout. |
| **Due Invoice Reminder** | Amber (`#D97706`) | Outstanding credit/due invoice balance notification. |
| **Exchange Invoice Confirmation** | Purple (`#7C3AED`) | Item exchange processed with net settlement. |
| **Return Invoice Confirmation** | Cyan (`#0891B2`) | Item return and cash/account refund issued. |
| **Payment Confirmation** | Azure Blue (`#2383E2`) | Due recovery or partial payment received. |
| **Gateway Test Message** | Slate (`#475569`) | Hardware verification test triggered from app. |
| **Custom Message** | Azure Blue (`#2383E2`) | Manual customer or supplier communication. |

---

## 7. Queued SMS Management & Cancellation Engine

Users can monitor the live outgoing SMS queue and cancel pending messages before they are transmitted:

1. **Visual Cue**: Messages with status `queued`, `pending`, or `sending` display a prominent red **Cancel** button on the card and in the detail modal sheet.
2. **Confirmation Safety**: Clicking Cancel triggers a confirmation `AlertDialog` detailing recipient name, phone number, and message type.
3. **API Execution**: Calls `Api.cancelJob(shopId, jobId)`:
   - Primary: `POST /api/connectx/gateway/cancel` (with device token)
   - Fallback: `DELETE /api/connectx/sms/messages/:id` (with admin token)
4. **Local Exclusion**: `QueueProcessor` immediately checks `prefs.isCancelled(job.id)` before invoking `SmsSender`, preventing race conditions.
5. **Confirmed UI feedback**: only an EMS-confirmed deletion removes the job from the SMS page. A network error or a job already being sent is shown as a cancellation failure; it must never be reported as successfully cancelled.

---

## 8. Administrator Profile Section & Sync

Settings includes a dedicated **Administrator Profile Hero Card** and **Profile Details Modal**:

- **Administrator Short ID**: Displays the authentic Short ID / Admin Code (e.g., `#1001` or `ADMIN-1001`) issued by EMS.
- **Full Name & Verification**: Shows administrator name with verified badge.
- **Contact Details**: Real registered phone number and business address.
- **Account Status**: Real-time status (`Active Administrator`).
- **Server & Infrastructure**: EMS URL, active connected shops count, hardware device public ID (`CX-XXXXXXXXXX`), and active SIM details.
- **Live Sync Button**: Top-right refresh button calls `Api.fetchAdminProfile()` to update credentials from EMS on demand.

---

## 9. Official EMS App Store Integration

### 9.1 App Store Card Aesthetic
The EMS Administrator App Store displays all ecosystem applications with a clean, focused card presentation:
- Clean 64px rounded squircle application icons with soft drop shadows.
- Direct display of App Title, Release Version, Package Size, and Published Date.
- Zero extraneous star rating pills or arbitrary category tags.
- Dynamic SVG and CSS gradient fallback badges preventing empty card headers.

### 9.2 Owner Console Release Publisher
The EMS Owner Console includes complete tools for publishing and managing ecosystem applications:
- **1-Click Presets**: Pre-configured templates for *ConnectX: Central Communication Gateway powered by Dexter Studio*, *EMS Mobile POS Terminal*, and *EMS Inventory Scanner*.
- **Quick Version Bumpers**: 1-click `[+0.0.1 Patch]`, `[+0.1.0 Minor]`, and `[+1.0.0 Major]` buttons that automatically recalculate semantic versions and increment `version_code`.
- **Cloudflare R2 Binary Storage**: Direct binary APK and icon uploads to Cloudflare R2 bucket storage with live upload progress tracking.
- **Mandatory Update Toggle**: One-click switch to flag security-critical releases as required across the entire Android fleet.

---

## 10. Complete API Endpoint Reference

ConnectX communicates with EMS via standard REST JSON APIs:

### 10.1 App Store & In-App Update API
| Method | Endpoint | Description | Payload / Response |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/app-store/check-update?package=...` | Checks latest published version. | Responds: `{ ok: true, hasUpdate: true, latestVersion: "1.6.0", versionCode: 17, mandatory: false, downloadUrl: "https://YOUR-EMS-SITE/api/app-store/download/com.ems.connectx", apk_size_bytes: 8645200, releaseNotes: "..." }` |
| `GET` | `/api/app-store/download/:id` | Downloads signed APK binary. | Returns APK file binary with `application/vnd.android.package-archive` MIME. |

### 10.2 Device Token Routes (Used by Gateway)
Header: `Authorization: Bearer <deviceToken>`

| Method | Endpoint | Description | Payload / Response |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/connectx/gateway/heartbeat` | Updates `last_seen` timestamp. | Responds with `{ ok: true, device, shop, administrator, smsEnabled }` |
| `POST` | `/api/connectx/gateway/claim` | Claims up to `limit` queued SMS jobs. | Payload: `{"limit": 8}`<br>Responds: `{"jobs": [...]}` |
| `POST` | `/api/connectx/gateway/report` | Reports dispatch success/failure. | Payload: `{"jobId": "...", "status": "sent" \| "failed", "error": null}` |
| `POST` | `/api/connectx/gateway/cancel` | Atomically cancels **only queued** SMS jobs; already claimed jobs return 409. | Payload: `{"jobId": "..."}`<br>Responds: `{"ok": true, "cancelled": true}` |
| `GET` | `/api/connectx/gateway/stats?utcOffsetMinutes=360` | Fetches SMS counters for the phone-local day. | Responds: `{"sent": 12, "failed": 0, "pending": 1, "administrator": {...}}` |
| `GET` | `/api/connectx/gateway/activity?range=today&utcOffsetMinutes=360` | Fetches shop SMS activity for the phone-local day (or 7d/30d). | Responds: `{"items": [...]}` |
| `GET` | `/api/connectx/gateway/me` | Fetches full pairing & admin profile. | Responds: `{"device": {...}, "shop": {...}, "administrator": {...}}` |
| `PATCH`| `/api/connectx/gateway/sim` | Updates SIM slot, carrier, and phone. | Payload: `{"simSubscriptionId": 1, "simCarrier": "Grameenphone", "phoneNumber": "..."}` |
| `POST` | `/api/connectx/gateway/test` | Triggers hardware self-test. | Payload: `{"ok": true, "record": false}` |
| `POST` | `/api/connectx/gateway/disconnect` | Revokes device gateway token. | Responds: `{"ok": true}` |
| `GET` | `/api/connectx/gateway/sim-carrier?mccMnc=...&carrierName=...` | Finds only the active carrier profile for an authenticated, non-revoked ConnectX device. | `{ supported: false }` or `{ supported: true, carrier: { carrier_name, balance_ussd_code, balance_pattern } }`. No USSD is sent by EMS. |

### 10.3 Administrator Token Routes (Used during Login & Setup)
Header: `Authorization: Bearer <adminToken>`

| Method | Endpoint | Description | Payload / Response |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/admin/login` | Authenticates administrator. | Payload: `{"email": "...", "password": "..."}`<br>Responds: `{"token": "...", "user": {...}}` |
| `GET` | `/api/connectx/gateway/shops` | Lists all shops for administrator. | Responds: `{"shops": [...], "administrator": {...}}` |
| `POST` | `/api/connectx/gateway/register` | Pairs device & generates device token. | Payload: `{"storeId": "...", "deviceName": "...", "simSubscriptionId": 1, ...}` |
| `GET` | `/api/admin/profile` | Fetches full administrator profile. | Responds: `{"id": "...", "admin_code": "1001", "name": "...", "phone": "...", "address": "..."}` |

---

### 10.4 Read-only outgoing email API (new in v1.6)

The **existing** EMS `connectx_messages` table records outgoing email. The email routes are authenticated with the paired-device token and scope every query to the device's `store_id`, additionally checking active pairing and administrator/shop status. Shop-hidden rows (`shop_deleted_at`) stay hidden. The phone is not granted provider credentials, incoming mail or send/delete permissions. No new database migration is needed for this feature.

| Method | Endpoint | Response |
| :--- | :--- | :--- |
| `GET` | `/api/connectx/gateway/emails/stats?utcOffsetMinutes=360` | Local-day sent/failed/pending counts plus latest **outgoing** email metadata; no body or BCC. |
| `GET` | `/api/connectx/gateway/emails?page=0` | Up to 30 visible records plus `hasMore` and `snapshot`. Pass `page=1&snapshot=...` for older items; each list row contains recipients, subject, status, error and timestamps, **not** its body. |
| `GET` | `/api/connectx/gateway/emails/:uuid` | One message in the paired shop, with body, To/CC/BCC and status for safe plain-text Android viewing. Other-shop/deleted messages return 404. |

Phone offsets are signed integers in minutes east of UTC (`360` for Bangladesh Standard Time); missing offset defaults to UTC for older clients. Sent-today counts use `sent_at` when present, rather than when the job was queued. Pending means `queued` or `sending`. Email pagination uses a server-side snapshot anchor to avoid newly inserted records shifting older pages.

---

## 11. Building & Deploying ConnectX

### 11.1 Prerequisites
- **Android Studio Ladybug (2024.2.1+)** or IntelliJ IDEA
- **JDK 17** (configured in Gradle JVM)
- **Android SDK Platform 35** (Build Tools 35.0.0)

### 11.2 Required Android Permissions & FileProvider
In `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

### 11.3 Gradle Build Commands
```bash
# Clean project
./gradlew clean

# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```
**`assembleRelease` without signing configuration creates `app-release-unsigned.apk`, which cannot update an installed app.** Use Android Studio **Generate Signed Bundle / APK → APK** with the installed app's original signing key, verify package `com.ems.connectx` and embedded **1.6.0/build 17**, then upload the signed file as documented in [`EMS/APP_STORE_RELEASE.md`](../EMS/APP_STORE_RELEASE.md). This source checkout may lack the `gradlew` launcher/wrapper JAR; Android Studio can generate it or use an installed Gradle distribution.

---

## 12. Maintenance & Support

ConnectX is actively maintained by **Dexter Studio**. For inquiries, custom gateway drivers, or EMS POS feature integrations, refer to the official repository at [https://github.com/sa8650/ConnectX](https://github.com/sa8650/ConnectX).
