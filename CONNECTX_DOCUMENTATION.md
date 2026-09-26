# ConnectX: Central Communication Gateway powered by Dexter Studio

## Complete Technical Documentation & Architecture Reference — v2.1.0 (systems + shops)

---

## 1. Executive summary & architecture

ConnectX is an **independent communication platform**. It consists of:

1. **ConnectX Control** — a React website + Cloudflare Pages Functions API + D1 database
   (separate project: `connectx-control/`, deployed to its own repo/site — officially
   `https://connectxweb.pages.dev`). It is the single source of truth for **systems**
   (EMS, InfluenceOS, CareOS, PlugX, custom), their **shops**, federated **system
   administrators**, gateway devices, message jobs, API keys, releases and settings.
2. **ConnectX Android gateway** — this repository. A native Kotlin + Jetpack Compose app
   (package `com.connectx.gateway`) for **system administrators**. It signs in through
   ConnectX, pairs the phone to one of the administrator's shops, claims queued SMS jobs,
   dispatches them through the phone's selected SIM with `SmsManager`, reports results,
   and shows read-only outgoing email history for that shop — pushed by client systems or
   delivered by the platform's own email gateway (Brevo/Resend/SendGrid/Mailgun/Postmark
   configured in ConnectX Control).

**The phone talks only to ConnectX Control.** The app never reads a system URL, system
session or system database; the official ConnectX Control address is **built into the
app core** (`GatewayUrl.BUILT_IN`). The URL entry screen appears only when that address
cannot be reached. EMS — like CareOS, InfluenceOS and PlugX — is an external *client
system* that integrates through the ConnectX **Client API** with a revocable API key.
The EMS repository is not modified by this redesign.

```
Client systems (EMS, CareOS, InfluenceOS, PlugX, …)
    │  POST /api/client/v1/sms         X-ConnectX-Key: cxk_live_… + "shop"
    │  POST /api/client/v1/email/send  (ConnectX delivers via its configured provider)
    │  POST /api/client/v1/email       (or just log history when the system sends its own)
    │  ◄── webhooks: job.sent / job.failed / job.cancelled (HMAC-signed)
    ▼
ConnectX Control  (Cloudflare Pages: React SPA + Functions + D1 + R2)
    │  control API   /api/control/*    (owner/operator bearer sessions — the website)
    │  device API    /api/device/*     (admin sessions + revocable device tokens cxd_…)
    │  public API    /api/public/*     (release check/download — no auth)
    │  ── server-side only ──►  system APIs (owner-configured per system):
    │       POST {api_url}/{login_path}   federated administrator login
    │       GET  {api_url}/{shops_path}   administrator's shop list (sync)
    ▼
Android gateway phones (this app)
    system dropdown → admin login → shop select → SIM → claim → send via SIM → report
```

### Multi-product model

- **System** — an external product registered in ConnectX Control (`ems`, `careos`,
  `influenceos`, `plugx`, custom). The **owner** configures its `api_url` (federated
  admin login + shop sync), `login_path`, `shops_path` and `webhook_url`. Systems own
  API keys. The API URL is **never exposed to the phone** — `device/systems` returns only
  key/name/available.
- **Administrator** — a system admin (e.g. an EMS administrator) who signs in on a phone.
  Credentials are verified **server-side** by ConnectX against the system's own login
  endpoint; the returned system token is cached in `cx_admins` (short-lived, re-login on
  demand). No system password ever reaches the phone or ConnectX storage.
- **Shop** — a shop/branch inside a system (`cx_shops`, unique per system+external id).
  Shops sync automatically at administrator sign-in and can be auto-registered by the
  Client API (`shop` + `shop_name`). Devices pair to a shop; jobs are scoped to it.
- **API key** — `cxk_live_…`, SHA-256-hashed at rest, bound to one system, with a daily
  message limit. Shown exactly once at creation.
- **Job** — a unified message row (`cx_jobs`) with `channel` = `sms` or `email`,
  `system_id`, `shop_id`, opaque `reference_id` / `reference_number` passthrough fields
  and an idempotency key. Statuses: `queued → sending → sent | failed | cancelled`.
- **Device** — a paired phone with an opaque `cxd_…` token (hash stored), status
  `pending_test → active`, revocable at any time from the website.

### Security model

| Concern | Mechanism |
|---|---|
| Website sessions | HMAC-SHA256 signed bearer tokens (HS256 JWT-like), 12 h, `SESSION_SECRET` |
| Admin (phone) sessions | ConnectX-issued tokens after **federated** verification through the system API; the phone never contacts the system |
| System credentials | Verified server-side only; the cached system token (`cx_admins.system_token`) expires with the system's own session; no passwords stored |
| Passwords (console) | PBKDF2-SHA256, 100 000 iterations, random salt (`pbkdf2$…` format) |
| Device tokens | Opaque 256-bit random `cxd_…`; only SHA-256 hash stored; instant revoke |
| API keys | Opaque `cxk_live_…`; only SHA-256 hash stored; per-key daily limit; system-bound |
| Pairing | Short-lived ambiguous-character-free codes (`4F7K-9Q2M`), single use, per shop |
| Client API aliases | snake_case + EMS-style camelCase (`storeId`, `toPhone`, `messageBody`, `idempotencyKey`, …); `POST client/v1/sms/send` alias; 5-second duplicate guard |
| Claim races | Conditional `UPDATE … WHERE status='queued'`; stale `sending` jobs (10 min) re-queue |
| Webhooks | HTTPS-only, private-IP blocked, optional `x-connectx-signature` HMAC |
| Email privacy | Device reads its shop's history only; HTML shown as inert text; no disk cache |
| APK updates | ZIP-magic + package/build verification before install; Android checks signature |
| Built-in control URL | `https://connectxweb.pages.dev` compiled into the app; custom URLs only via the explicit connect screen |

---

## 2. Android app structure (this repository)

```
app/src/main/java/com/connectx/gateway/
├── MainActivity.kt          # Compose UI: system dropdown + federated login, shop select,
│                            # SIM setup, test SMS, dashboard, SMS/Email pages, settings,
│                            # About & Updates + OTA wizard, pairing-code flow
├── ConnectXApp.kt           # Application: notification channels, workers scheduling
├── data/
│   ├── Api.kt               # HTTPS client for the ConnectX device API (OkHttp + JSON)
│   ├── GatewayUrl.kt        # Built-in control URL + strict validator/normalizer
│   ├── Prefs.kt             # EncryptedSharedPreferences: base URL, system, tokens, connections
│   ├── Models.kt            # Data classes (SystemOption, Shop, Connection, SmsJob, EmailItem…)
│   ├── SimBalance.kt        # Owner-managed carrier balance config + reply parsing
│   └── UpdateCheckWorker.kt # Periodic background update check (WorkManager)
├── sms/
│   ├── GatewayService.kt    # Foreground service keeping dispatch alive
│   ├── QueueProcessor.kt    # claim → send → report loop
│   ├── QueueWorker.kt       # WorkManager fallback pump
│   ├── SmsSender.kt         # SmsManager send on the selected SIM subscription
│   ├── SmsSentReceiver.kt   # delivery result → report to ConnectX
│   ├── SimUssdClient.kt     # single manual USSD balance request (CALL_PHONE)
│   └── BootReceiver.kt      # restart gateway after reboot
└── ui/Theme.kt              # Mobbin-light design system
```

Gradle identity: `namespace`/`applicationId` = `com.connectx.gateway`, versionName `2.1.0`,
versionCode `19`. The About screen reads these from `BuildConfig`.

### Design system (Mobbin Light)

Unchanged: light surfaces (`#F7F8FA` background, white cards, 16–28 dp radii),
indigo primary `#4F46E5`-family accents, `Inter`-style system typography, bottom navigation
with Dashboard / SMS / Email / Settings, predictive back handling, inert-HTML email viewer.
The Control website mirrors the same brand (indigo→cyan gradient, dark navigation rail).

---

## 3. Device API (used by this app)

Base URL = the built-in `https://connectxweb.pages.dev` (or the saved custom address).
All paths are under `/api/`.

### 3.1 System list, federated sign-in & pairing

| Endpoint | Auth | Purpose |
|---|---|---|
| `GET  device/systems` | — | `{systems:[{id,key,name,available}]}` — the sign-in dropdown. Never exposes API URLs. |
| `POST device/auth/login` | — | `{system:"ems", email, password}` → ConnectX verifies against the system's API → `{token, admin, administrator, system, shops:[…]}` |
| `GET  device/shops` | admin | Re-sync + `{administrator, system, shops:[{id,external_id,name,shop_code,address,phone,category,status,connected,devices}]}` |
| `POST device/register` | admin | `{shopId, deviceName, androidVersion, appVersion, simSubscriptionId, simCarrier, phoneNumber}` → `{device, deviceToken, shop, system, administrator}` |
| `POST device/pair` | — | Pairing-code flow `{code, deviceName, …}` → same shape (administrator may be null) |

The administrator's shops sync from the system on every login and on `device/shops`;
unknown shops seen by the Client API are auto-registered and appear here too.

### 3.2 Paired-device routes (bearer `cxd_…`)

| Endpoint | Purpose |
|---|---|
| `GET  device/me` | Device + shop + system + administrator + connected shop ids |
| `POST device/heartbeat` | Liveness (`last_seen`), optional name/version patch, `smsEnabled` flag |
| `POST device/jobs/claim` | Claim up to N queued SMS **of this device's shop** (race-safe; re-queues stale sends) |
| `POST device/jobs/report` | `{jobId, status:"sent"|"failed", error?}` → fires the system's webhook |
| `POST device/jobs/cancel` | Cancel a still-queued job (409 when already claimed) |
| `DELETE device/jobs/{id}` | Same as cancel (id in path) |
| `POST device/test` | Mark setup test passed/failed; optionally queue a real test SMS |
| `PATCH device/sim` | Update selected SIM subscription/carrier/phone |
| `GET  device/sim-carrier?mccMnc&carrierName` | Owner-managed balance USSD lookup → `{supported, carrier}` |
| `GET  device/stats?utcOffsetMinutes` | Today's sent/failed/pending + last activity + shop/system/administrator blocks |
| `GET  device/activity?range=today|7d|30d` | Last 250 SMS rows with reference numbers |
| `GET  device/emails?page&snapshot` | Paginated (30/page) outgoing email history of the shop, snapshot-stable |
| `GET  device/emails/stats` | Email counters + latest item |
| `GET  device/emails/{uuid}` | Full detail incl. bcc/custom_body/body_html (inert rendering) |
| `POST device/disconnect` | Self-revoke this device |

Job payload keys returned by `claim`: `id, shop_id, shop_external_id, system_key,
phone_number, message, event_type, message_type, recipient_name, invoice_id,
reference_id, reference_number, created_at, attempts`.

### 3.3 Public update channel (no auth)

| Endpoint | Purpose |
|---|---|
| `GET public/releases/check?package&versionCode` | `{ok, hasUpdate, latestVersion, versionCode, mandatory, downloadUrl, apk_filename, apk_size_bytes, releaseNotes, updated_at}` |
| `GET public/releases/download/{package}` | Signed APK from ConnectX R2 (or 302 to a vetted external HTTPS URL) |
| `GET public/releases` / `GET public/health` | Listing / liveness |

Update flow: launch + resume + hourly while open + ~6-hourly WorkManager check. Mandatory
releases block the UI until installed. Downloaded APKs are verified for size, ZIP magic,
package name and advertised build code before handing to the package installer; Android
still enforces the signing certificate.

---

## 4. Client API (for EMS, CareOS, InfluenceOS, PlugX, …)

Header: `X-ConnectX-Key: cxk_live_…`. Every message carries a **`shop`** reference (the
shop's id inside the system, its ConnectX uuid, or its shop_code); unknown shops are
auto-registered (pass `shop_name`). Full reference with examples lives in the Control
website's **API Docs** page and `connectx-control/API.md`.

| Endpoint | Purpose |
|---|---|
| `GET  client/v1/ping` | Validate key, list reachable gateways |
| `POST client/v1/sms` | Queue one SMS for a shop (body or template `event_type` + amounts) |
| `POST client/v1/sms/bulk` | ≤100 messages per call |
| `GET  client/v1/sms[/{id}]` | Status/list; `POST …/{id}/cancel` while queued |
| `POST client/v1/email/send` | ConnectX delivers the email through the provider configured in Settings → Email (no SMTP setup needed in the system) |
| `POST client/v1/email` | Log an outgoing email record (status passthrough) |
| `PATCH client/v1/email/{id}` | Update email status/error/provider id |
| `GET  client/v1/stats` / `client/v1/devices` | Today counters / online gateways (per shop) |
| webhooks | `job.sent`, `job.failed`, `job.cancelled` POSTed to the system's HTTPS webhook with `system_key` + `shop_external_id` |

Templates: the platform defines `SALE, PAYMENT, DUE_REMINDER, RETURN, EXCHANGE, REFUND,
TEST` templates (placeholders `{name} {shop} {invoice} {total} {paid} {due} {amount}
{currency}`; `{shop}` renders the shop's name); systems may send only `event_type` +
amount fields and ConnectX renders them.

---

## 5. Control website (connectx-control project)

React 18 + TypeScript + Vite SPA served by the same Cloudflare Pages deployment as the API.
Pages: Dashboard · Gateways (per-shop pairing codes, revoke, primary) · Messages (shop/system
filters, cancel, retry, manual test send) · **Shops** (synced per system; pause/resume) ·
**Systems & API Keys** (System | API URL | Status table, configure login/shops paths +
webhook, keys shown once, limits) · App Releases (APK upload to R2 or external URL,
publish/mandatory toggles) · SIM Carriers · API Docs · Activity (audit trail) · Settings
(profile, password, global SMS toggle + templates, email provider, platform accounts).

Roles: **owner** (everything: systems, API URLs, keys, releases, accounts) and **operator**
(day-to-day: shops, gateways, messages, carriers).

First run: the login page detects an uninitialized platform and creates the owner account
and seeds the four known systems (EMS, CareOS, InfluenceOS, PlugX) — all *unconfigured*
until the owner sets their API URLs. There is no workspace concept.

---

## 6. Queued SMS management & cancellation

- Claiming is conditional: `UPDATE … SET status='sending' WHERE id=? AND status='queued'`;
  a job is dispatched only when this device won the update (prevents double-send across
  multiple gateways in one shop).
- Jobs stuck `sending` for >10 minutes are re-queued automatically on the next claim.
- Cancellation (phone, website or client API) only succeeds while `queued`; the guarded
  update returns 409 if a gateway claimed it meanwhile.
- `report` is idempotent-friendly: a `sent` job owned by another device returns
  `{ok, duplicate:true}` instead of overwriting.
- Retries: failed/cancelled SMS can be re-queued from the website (raises `max_attempts`).

## 7. Message types & smart formatting

`formatMessageType()` in `Models.kt` maps raw `message_type`/`event_type` (and body
heuristics) to display titles: Sales Invoice Confirmation, Due Invoice Reminder, Exchange/
Return Invoice Confirmation, Payment Confirmation, Gateway Test Message, Custom Message —
or any descriptive custom label a system sends.

---

## 8. Building & deploying

### 8.1 Android (this repo)

Prerequisites: Android Studio (JDK 17, Android SDK 35). Commands and signing rules:
[BUILD_ANDROID.md](BUILD_ANDROID.md). Permissions: `SEND_SMS`, `READ_PHONE_STATE`,
`READ_PHONE_NUMBERS`, `CALL_PHONE` (manual USSD only), foreground service (dataSync),
notifications, boot-completed, `REQUEST_INSTALL_PACKAGES` (OTA), FileProvider
(`${applicationId}.fileprovider`) for the installer.

### 8.2 ConnectX Control

See `connectx-control/DEPLOY.md`: push the project to GitHub → Cloudflare Pages →
create D1 `connectx-control` + R2 `connectx-releases` → put `SESSION_SECRET` →
apply `schema/connectx_schema.sql` → open the site and create the owner account →
configure systems → publish the APK under App Releases.

### 8.3 Connecting a product (e.g. EMS)

No ConnectX-side code changes are needed. In ConnectX Control → **Systems & API Keys**:
1. **Configure** the system: set its **API URL** (federated admin login + shop sync —
   defaults match the EMS contract: `api/auth/admin/login`, `api/connectx/gateway/shops`)
   and its **webhook URL** for delivery results.
2. **Issue an API key** and put the ConnectX base URL + key into that product's
   configuration; it calls `POST /api/client/v1/sms` with its `shop` reference.
3. On a phone: pick the system, sign in with a system administrator account, choose a
   shop, pair the SIM. Shops sync automatically.

---

## 9. Maintenance & support

- Rotate `SESSION_SECRET` only during a maintenance window (invalidates website/device
  sessions; device tokens themselves survive — they are hashed independently).
- Revoke lost/stolen phones immediately (Gateways → Revoke); the device token dies at once.
- Keep the carrier catalog minimal and verified (SIM Carriers page) — USSD codes are
  owner-managed by design; the APK contains none.
- Database: single D1 `connectx-control`; audit trail in `cx_activity_log`; nightly D1
  backups recommended once in production (`wrangler d1 export`).

Built and maintained by **Dexter Studio**.
