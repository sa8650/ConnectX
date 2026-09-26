# ConnectX: Central Communication Gateway powered by Dexter Studio

## Complete Technical Documentation & Architecture Reference — v2.0.0 (independent platform)

---

## 1. Executive summary & architecture

ConnectX is an **independent communication platform**. It consists of:

1. **ConnectX Control** — a React website + Cloudflare Pages Functions API + D1 database
   (separate project: `connectx-control/`, deployed to its own repo/site). It is the single
   source of truth for workspaces, gateway devices, message jobs, connected client apps,
   API keys, releases and settings.
2. **ConnectX Android gateway** — this repository. A native Kotlin + Jetpack Compose app
   (package `com.connectx.gateway`) that pairs to ConnectX Control, claims queued SMS jobs,
   dispatches them through the phone's selected SIM with `SmsManager`, reports results, and
   shows read-only outgoing email history — pushed by client apps or delivered by the
   platform's own email gateway (Brevo/Resend/SendGrid/Mailgun/Postmark configured in
   ConnectX Control).

**There is no connection to EMS.** The app never reads an EMS URL, EMS session, EMS database
or the EMS App Store. EMS — like CareOS, InfluenceOS and PlugX — is an external *client app*
that integrates through the ConnectX **Client API** with a revocable API key. The EMS
repository is not modified by this redesign.

```
Client apps (EMS, CareOS, InfluenceOS, PlugX, …)
    │  POST /api/client/v1/sms         X-ConnectX-Key: cxk_live_…
    │  POST /api/client/v1/email/send  (ConnectX delivers via its configured provider)
    │  POST /api/client/v1/email       (or just log history when the app sends its own)
    │  ◄── webhooks: job.sent / job.failed / job.cancelled (HMAC-signed)
    ▼
ConnectX Control  (Cloudflare Pages: React SPA + Functions + D1 + R2)
    │  control API   /api/control/*    (owner/operator bearer sessions — the website)
    │  device API    /api/device/*     (operator tokens + revocable device tokens cxd_…)
    │  public API    /api/public/*     (release check/download — no auth)
    ▼
Android gateway phones (this app)
    claim → send via SIM → report
```

### Multi-product model

- **Workspace** — one tenant (shop, branch, organization or product environment). Devices
  pair to a workspace; jobs are scoped to it. (The device API still emits legacy `shop`/
  `shops`/`shop_id` JSON keys so the phone UI and older parsers keep working.)
- **Client** — a product registered in ConnectX Control (`ems`, `careos`, `influenceos`,
  `plugx`, custom). Each client owns API keys and an optional webhook URL.
- **API key** — `cxk_live_…`, SHA-256-hashed at rest, optionally scoped to one workspace,
  with a daily message limit. Shown exactly once at creation.
- **Job** — a unified message row (`cx_jobs`) with `channel` = `sms` or `email`,
  `client_id`, opaque `reference_id` / `reference_number` passthrough fields and an
  idempotency key. Statuses: `queued → sending → sent | failed | cancelled`.
- **Device** — a paired phone with an opaque `cxd_…` token (hash stored), status
  `pending_test → active`, revocable at any time from the website.

### Security model

| Concern | Mechanism |
|---|---|
| Website sessions | HMAC-SHA256 signed bearer tokens (HS256 JWT-like), 12 h, `SESSION_SECRET` |
| Passwords | PBKDF2-SHA256, 100 000 iterations, random salt (`pbkdf2$…` format) |
| Device tokens | Opaque 256-bit random `cxd_…`; only SHA-256 hash stored; instant revoke |
| API keys | Opaque `cxk_live_…`; only SHA-256 hash stored; per-key daily limit; scope |
| Pairing | Short-lived ambiguous-character-free codes (`4F7K-9Q2M`), single use |
| Client API aliases | snake_case + EMS-style camelCase (`toPhone`, `messageBody`, `idempotencyKey`, …); `POST client/v1/sms/send` alias; 5-second duplicate guard |
| Claim races | Conditional `UPDATE … WHERE status='queued'`; stale `sending` jobs (10 min) re-queue |
| Webhooks | HTTPS-only, private-IP blocked, optional `x-connectx-signature` HMAC |
| Email privacy | Device reads workspace-scoped history only; HTML shown as inert text; no disk cache |
| APK updates | ZIP-magic + package/build verification before install; Android checks signature |

---

## 2. Android app structure (this repository)

```
app/src/main/java/com/connectx/gateway/
├── MainActivity.kt          # Compose UI: login, workspace select, SIM setup, dashboard,
│                            # SMS/Email pages, settings, About & Updates + OTA wizard
├── ConnectXApp.kt           # Application: notification channels, workers scheduling
├── data/
│   ├── Api.kt               # HTTPS client for the ConnectX device API (OkHttp + JSON)
│   ├── GatewayUrl.kt        # Strict validator/normalizer for the Control website URL
│   ├── Prefs.kt             # EncryptedSharedPreferences: base URL, tokens, connections
│   ├── Models.kt            # Data classes (Connection, SmsJob, EmailItem, AppUpdateInfo…)
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

Gradle identity: `namespace`/`applicationId` = `com.connectx.gateway`, versionName `2.0.0`,
versionCode `18`. The About screen reads these from `BuildConfig`.

### Design system (Mobbin Light)

Unchanged from v1.6: light surfaces (`#F7F8FA` background, white cards, 16–28 dp radii),
indigo primary `#4F46E5`-family accents, `Inter`-style system typography, bottom navigation
with Dashboard / SMS / Email / Settings, predictive back handling, inert-HTML email viewer.
The Control website mirrors the same brand (indigo→cyan gradient, dark navigation rail).

---

## 3. Device API (used by this app)

Base URL = the deployed ConnectX Control website. All paths are under `/api/`.

### 3.1 Sign-in & pairing

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST device/auth/login` | — | Operator sign-in `{email,password}` → `{token, user, role}` |
| `GET  device/auth/profile` | operator | Refresh the signed-in profile |
| `GET  device/workspaces` | operator | `{administrator, shops:[{id,name,address,phone,shop_code,connected}]}` |
| `POST device/register` | operator | `{storeId, deviceName, androidVersion, simSubscriptionId, simCarrier, phoneNumber}` → `{device, deviceToken, shop, administrator}` |
| `POST device/pair` | — | Pairing-code flow `{code, deviceName, …}` → same shape as register |

### 3.2 Paired-device routes (bearer `cxd_…`)

| Endpoint | Purpose |
|---|---|
| `GET  device/me` | Device + workspace + administrator + connected workspace ids |
| `POST device/heartbeat` | Liveness (`last_seen`), optional name/version patch, `smsEnabled` flag |
| `POST device/jobs/claim` | Claim up to N queued SMS (race-safe; re-queues stale sends) |
| `POST device/jobs/report` | `{jobId, status:"sent"|"failed", error?}` → fires client webhooks |
| `POST device/jobs/cancel` | Cancel a still-queued job (409 when already claimed) |
| `DELETE device/jobs/{id}` | Same as cancel (id in path) |
| `POST device/test` | Mark setup test passed/failed; optionally queue a real test SMS |
| `PATCH device/sim` | Update selected SIM subscription/carrier/phone |
| `GET  device/sim-carrier?mccMnc&carrierName` | Owner-managed balance USSD lookup → `{supported, carrier}` |
| `GET  device/stats?utcOffsetMinutes` | Today's sent/failed/pending + last activity + profile blocks |
| `GET  device/activity?range=today|7d|30d` | Last 250 SMS rows with reference numbers |
| `GET  device/emails?page&snapshot` | Paginated (30/page) outgoing email history, snapshot-stable |
| `GET  device/emails/stats` | Email counters + latest item |
| `GET  device/emails/{uuid}` | Full detail incl. bcc/custom_body/body_html (inert rendering) |
| `POST device/disconnect` | Self-revoke this device |

Job payload keys returned by `claim` (legacy-compatible): `id, shop_id, phone_number,
message, event_type, message_type, recipient_name, invoice_id, created_at, attempts` plus
new `workspace_id, reference_id, reference_number, client_key, client_name`.

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

Header: `X-ConnectX-Key: cxk_live_…`. Full reference with examples lives in the Control
website's **API Docs** page and `connectx-control/API.md`.

| Endpoint | Purpose |
|---|---|
| `GET  client/v1/ping` | Validate key, list reachable gateways |
| `POST client/v1/sms` | Queue one SMS (body or template `event_type` + amounts) |
| `POST client/v1/sms/bulk` | ≤100 messages per call |
| `GET  client/v1/sms[/{id}]` | Status/list; `POST …/{id}/cancel` while queued |
| `POST client/v1/email/send` | ConnectX delivers the email through the provider configured in Settings → Email (no SMTP setup needed in the app) |
| `POST client/v1/email` | Log an outgoing email record (status passthrough) |
| `PATCH client/v1/email/{id}` | Update email status/error/provider id |
| `GET  client/v1/stats` / `client/v1/devices` | Today counters / online gateways |
| webhooks | `job.sent`, `job.failed`, `job.cancelled` POSTed to the app's HTTPS webhook |

Templates: workspaces can define `SALE, PAYMENT, DUE_REMINDER, RETURN, EXCHANGE, REFUND,
TEST` templates (placeholders `{name} {shop} {invoice} {total} {paid} {due} {amount}
{currency}`); clients may send only `event_type` + amount fields and ConnectX renders them.

---

## 5. Control website (connectx-control project)

React 18 + TypeScript + Vite SPA served by the same Cloudflare Pages deployment as the API.
Pages: Dashboard · Gateways (pairing codes, revoke, primary) · Messages (filters, cancel,
retry, manual test send) · Workspaces · Apps & API Keys (keys shown once, webhooks, limits) ·
App Releases (APK upload to R2 or external URL, publish/mandatory toggles) · SIM Carriers ·
API Docs · Activity (audit trail) · Settings (profile, password, per-workspace SMS toggles +
templates, platform accounts).

Roles: **owner** (everything: apps, keys, releases, accounts) and **operator** (day-to-day:
workspaces, gateways, messages; can sign in on phones to register devices).

First run: the login page detects an uninitialized platform and creates the owner account,
seeds the four known clients (EMS, CareOS, InfluenceOS, PlugX) and a `MAIN` workspace.

---

## 6. Queued SMS management & cancellation

- Claiming is conditional: `UPDATE … SET status='sending' WHERE id=? AND status='queued'`;
  a job is dispatched only when this device won the update (prevents double-send across
  multiple gateways in one workspace).
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
or any descriptive custom label a client sends.

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
publish the APK under App Releases.

### 8.3 Connecting a product (e.g. EMS) later

No ConnectX-side code changes are needed: register/confirm the client, issue an API key,
put the ConnectX base URL + key into that product's configuration, and call
`POST /api/client/v1/sms` where it previously wrote to its own internal queue. Set the
webhook URL to receive delivery results.

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
