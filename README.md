# ConnectX: Central Communication Gateway powered by Dexter Studio

**Independent communication platform · Android gateway v2.1.0 / build 19**

ConnectX is a **fully independent software platform** — it never connects to EMS (or any
other product) directly from the phone. Two pieces ship together:

| Piece | Where | What it is |
|---|---|---|
| **ConnectX Control** (website + API + database) | separate project folder `connectx-control/` (deploy to its own GitHub repo → Cloudflare Pages at `https://connectxweb.pages.dev`) | The React control website that manages **systems** (EMS, InfluenceOS, CareOS, PlugX…), their **shops**, gateway phones, message jobs, API keys and app releases. It is the ONLY server the Android app talks to. |
| **ConnectX Android gateway** | this repository (`app/`) | Native Kotlin + Jetpack Compose app for **system administrators**. Signs in through ConnectX, pairs a phone to one of the administrator's **shops**, claims queued SMS jobs and dispatches them through the phone's own SIM, reports results, and reads that shop's outgoing email history. |

```
┌────────────┐  API key (cxk_live_) + shop  ┌───────────────────────┐  device token (cxd_)  ┌────────────────┐
│ EMS        │ ───────────────────────────► │                       │ ◄──────────────────── │ Android phone  │
│ CareOS     │         client API v1        │   ConnectX Control    │      device API       │ (this repo)    │
│ InfluenceOS│ ───────────────────────────► │  website + API + D1   │ ────────────────────► │ SIM dispatch   │
│ PlugX      │  ◄─── webhooks (results)     │                       │  admin login proxy    │                │
└────────────┘        ▲                     └───────────┬───────────┘                       └────────────────┘
      ▲               │                                 │  federated admin login + shop sync
      └───────────────┴── system API (owner-configured) ┘  (server-side only)
```

- **The phone talks ONLY to ConnectX Control.** The official address
  `https://connectxweb.pages.dev` is **built into the app**; a URL entry screen appears
  only if that address cannot be reached (self-hosted deployments).
- **Federated sign-in.** The app shows a **system dropdown** (EMS now; InfluenceOS,
  CareOS, PlugX as the owner connects them). The administrator signs in with their
  **system account** (e.g. their EMS admin email/password). ConnectX verifies it against
  the system's own API **server-side** and returns the administrator's **shops** — system
  credentials and URLs are never stored on the phone.
- **Flow:** system dropdown → admin login → shop selection → SIM selection → test SMS
  (skippable) → dashboard. Alternatively: a **pairing code** from ConnectX Control →
  Gateways binds a phone to a shop with no account at all.
- **EMS is not modified.** Like the other products, EMS is an ordinary *client system*:
  it pushes SMS/email jobs through the ConnectX **Client API** with an API key and a
  `shop` reference, and receives **webhook** callbacks with delivery results.

## What changed in v2.1.0 (build 19)

- **Systems + shops replace workspaces.** A device belongs to a *shop of a system*; the
  system dropdown + federated administrator login replace the old ConnectX-account login.
- **Built-in control URL** (`https://connectxweb.pages.dev`): no URL entry on first run;
  Settings → *ConnectX Control Address* (and the sign-in screen's “Can't connect?”) allow
  reconfiguring for self-hosted sites.
- New device API surface: `device/systems`, `device/auth/login {system,email,password}`,
  `device/shops`, `device/register {shopId,…}`.
- Settings now covers: change/add connected shop, change SIM, update/reconfigure app,
  send test SMS, disconnect shop, battery restrictions, logout.

## App navigation

| Page | What it does |
|---|---|
| Dashboard | Active shop + system, SMS dispatch state, sent/pending/failed counts for SMS and email, and the latest outgoing email. Switch the active shop here. |
| SMS | Outgoing SMS history and details, confirmed cancellation of queued jobs, sending-SIM switch, and manual selected-SIM balance Refresh. Delivery continues in the background. |
| Email | Paginated outgoing email history for the selected shop (pushed by connected systems or sent by the ConnectX email gateway). Tap one for recipients, status/error and the message in a safe plain-text view. |
| Settings | Administrator profile, SMS service toggle, test SMS, connected shops, ConnectX Control address, battery restrictions, and About & Updates (ConnectX Releases channel). |

**Privacy:** the phone holds a revocable per-device token issued by ConnectX Control —
never a system password, database key or email-provider key. Email HTML renders as inert
text (no WebView) and bodies are not cached on disk.

## Deploying the platform

1. **Deploy ConnectX Control** (the `connectx-control/` project) to Cloudflare Pages:
   create the D1 database + R2 bucket, set `SESSION_SECRET`, apply
   `schema/connectx_schema.sql`, create your owner account on first run. Full steps: its
   `DEPLOY.md`.
2. **Connect a system** in ConnectX Control → **Systems & API Keys**: set the system's
   **API URL** (used for federated admin sign-in) and **webhook URL**, then issue it an
   **API key**. Shops sync automatically when an administrator signs in on a phone.
3. **Build this app** with JDK 17 / Android SDK 35 and sign it with your release key:
   [BUILD_ANDROID.md](BUILD_ANDROID.md).
4. **Publish the APK** in ConnectX Control → **App Releases** (package
   `com.connectx.gateway`, version `2.1.0`, build `19`). Phones update from your own
   website.
5. **On the phone:** install the app → pick the **system** → sign in with your system
   administrator account → choose a **shop** → grant SMS/phone permissions → choose the
   sending SIM → run (or skip) the test SMS. Or use a **pairing code** generated in
   ConnectX Control → Gateways for a specific shop.
6. **In your product backend** (EMS etc.): call the ConnectX Client API with your key and
   the `shop` reference on every message — reference and examples: the control website's
   **API Docs** page and `connectx-control/API.md`.

Architecture and endpoint reference: [CONNECTX_DOCUMENTATION.md](CONNECTX_DOCUMENTATION.md).

Built and maintained by **Dexter Studio**.
