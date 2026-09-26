# ConnectX: Central Communication Gateway powered by Dexter Studio

**Independent communication platform · Android gateway v2.0.0 / build 18**

ConnectX is now a **fully independent software platform** — it no longer connects to EMS in any way. Two pieces ship together:

| Piece | Where | What it is |
|---|---|---|
| **ConnectX Control** (website + API + database) | separate project folder `connectx-control/` (deploy to its own GitHub repo → Cloudflare Pages) | The React control website that manages gateway phones, workspaces, message jobs, connected apps and app releases. It is the ONLY server the Android app talks to. |
| **ConnectX Android gateway** | this repository (`app/`) | Native Kotlin + Jetpack Compose app. Pairs to ConnectX Control, claims queued SMS jobs and dispatches them through the phone's own SIM, reports results, and reads outgoing email history — pushed by connected apps or sent through the ConnectX email gateway. |

```
┌────────────┐   API key (cxk_live_…)   ┌───────────────────────┐   device token (cxd_…)   ┌────────────────┐
│ EMS        │ ───────────────────────► │                       │ ◄─────────────────────── │ Android phone  │
│ CareOS     │      client API v1       │   ConnectX Control    │      device API          │ (this repo)    │
│ InfluenceOS│ ───────────────────────► │  website + API + D1   │ ───────────────────────► │ SIM dispatch   │
│ PlugX      │      webhooks back       │                       │      update channel      │                │
└────────────┘                          └───────────────────────┘                          └────────────────┘
```

- **No direct EMS connection.** The app signs in with a **ConnectX Control account**, pairs to a **workspace**, and updates from **ConnectX Releases** — all served by the ConnectX Control website.
- **EMS is not modified.** Like CareOS, InfluenceOS and PlugX, EMS becomes an ordinary *client app*: it pushes SMS/email jobs through the ConnectX **Client API** with an API key and receives **webhook** callbacks. Nothing else.
- **One gateway, many products.** Jobs carry `client_key`, so a single phone can serve messages from several products at once; every job stays scoped to its workspace.

## What changed in v2.0.0 (build 18)

- Package renamed `com.ems.connectx` → **`com.connectx.gateway`** (uninstall the old EMS-linked build first; it cannot update in place to a new package id).
- All endpoints re-pointed from the EMS site to the ConnectX Control website (`/api/device/*`, `/api/public/releases/*`).
- “Shops” are now **workspaces**; the EMS App Store update channel is replaced by **ConnectX Releases** (managed in ConnectX Control → App Releases).
- Email page now shows outgoing email history that **client apps** push into ConnectX or that **ConnectX itself delivers** through its email gateway (Brevo and other providers, configured once on the control website — read-only, same privacy model: inert text, no WebView, no on-disk caching).
- SIM balance USSD codes come from the **ConnectX Control carrier catalog** (owner-managed, nothing hard-coded in the APK).

## App navigation

| Page | What it does |
|---|---|
| Dashboard | Active workspace, SMS dispatch state, sent/pending/failed counts for SMS and email, and the latest outgoing email. Switch the selected workspace here. |
| SMS | Outgoing SMS history and details, confirmed cancellation of queued jobs, sending-SIM switch, and manual selected-SIM balance Refresh. Delivery continues in the background. |
| Email | Paginated outgoing email history for the selected workspace (pushed by connected apps or sent by the ConnectX email gateway). Tap one for recipients, status/error and the message in a safe plain-text view. |
| Settings | Administrator profile, SMS service toggle, test SMS, connected workspaces, and About & Updates (ConnectX Releases channel). |

**Privacy:** the phone holds a revocable per-device token issued by ConnectX Control — never a database or email-provider key. Email HTML renders as inert text and is never loaded in a WebView; bodies are not cached on disk.

## Deploying the platform

1. **Deploy ConnectX Control** (the `connectx-control/` project) to Cloudflare Pages: create the D1 database + R2 bucket, set `SESSION_SECRET`, apply `schema/connectx_schema.sql`, create your owner account on first run. Full steps: its `DEPLOY.md`.
2. **Build this app** with JDK 17 / Android SDK 35 and sign it with your release key: [BUILD_ANDROID.md](BUILD_ANDROID.md).
3. **Publish the APK** in ConnectX Control → **App Releases** (package `com.connectx.gateway`, version `2.0.0`, build `18`). Phones now update from your own website.
4. **On the phone:** install the app, enter the full HTTPS URL of your deployed ConnectX Control website, then either sign in with a ConnectX Control account (Settings → Platform accounts) and pick a workspace, or use a **pairing code** generated in ConnectX Control → Gateways. Grant SMS/phone permissions, choose the sending SIM, run the test.
5. **Connect your products:** in ConnectX Control → Apps & API Keys, issue an API key per product (EMS, CareOS, InfluenceOS, PlugX…) and integrate with the Client API — reference and examples: the control website's **API Docs** page and `connectx-control/API.md`.

Architecture and endpoint reference: [CONNECTX_DOCUMENTATION.md](CONNECTX_DOCUMENTATION.md).

Built and maintained by **Dexter Studio**.
