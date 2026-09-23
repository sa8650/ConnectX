# ConnectX

Native Android SMS companion for **EMS**. This is not a second EMS app.

**ConnectX — Android SMS Gateway. Powered by Dexter Studio.**

Administrators sign in with the same email and password used on the EMS website (no Shop ID). The phone registers as an SMS gateway for one or more shops. Customer messages are sent from the selected SIM. EMS never controls the SIM and never stores SIM credentials.

## What this app does

- Sign in as EMS administrator
- Connect one phone to multiple shops
- Request SMS permission and choose a SIM
- Register a device token (password is not kept after setup)
- Send a test SMS, or skip and do it later from Settings
- Pull queued jobs in the background and send them
- Home / Activity / Settings per shop
- Switch shop and sending number in-app
- View the full SMS body from Activity
- Log out without creating a duplicate device

## What this app does not do

- Inventory, sales, customers, reports, or invoices
- WebView of EMS
- Embed Supabase service-role keys or other privileged secrets

## Build

See **[BUILD_ANDROID.md](BUILD_ANDROID.md)** for step-by-step Android Studio instructions. An APK cannot be compiled in this workspace (no Android SDK).

## EMS side

Apply migration `041_connectx_sms_gateway.sql` (or the D1 equivalent) and deploy the updated EMS API + Settings → Communication UI.
