# ConnectX Android documentation

## Direct delivery architecture

```text
EMS persistent SMS queue ↔ HTTPS ↔ ConnectX Android → selected physical SIM → recipient
```

ConnectX Android does not use ConnectX Control as an SMS intermediary. EMS authenticates administrators and devices, enforces shop/device authorization and business rules, stores SMS jobs, and records each device result. The Android app polls EMS directly, atomically claims jobs, sends on the SIM assigned to that shop, and reports results directly to EMS.

## Status semantics

- `pending`: available in the EMS queue.
- `processing`: claimed by a device; the send phase is tracked separately.
- `sent`: Android/carrier accepted the send request; it does **not** mean the recipient received it.
- `delivered`: carrier delivery confirmation was received.
- `failed`: a definite pre-send/submission failure; retry is allowed only when EMS policy permits.
- `unknown`: delivery cannot be determined safely. Ambiguous post-send failures are not automatically resent.

## Authentication and shop isolation

1. The administrator signs in directly against EMS over HTTPS.
2. EMS returns only shops authorized for that administrator.
3. The phone registers to one selected shop and receives a random, revocable `cxd_…` device token. EMS stores the token hash rather than the plain secret.
4. Each heartbeat, claim, start, and report is authenticated by that token and scoped to the registered shop. A job claim token is required when reporting its result.
5. Existing ConnectX Control pairing/phone credentials are legacy and cannot authorize the EMS API; the updated app registers a new EMS device.

## Polling, retry, and background work

The foreground gateway service uses network callbacks and adaptive backoff, accelerates polling after connectivity returns, and reports through a durable local outbox. WorkManager provides a network-constrained recovery path after process/boot/package replacement. EMS safely returns stale pre-send claims to `pending`; a stale claim after send began becomes `unknown` to avoid duplicate SMS. EMS applies bounded retry/backoff and expires unconfirmed `sent` results to `unknown` rather than asserting delivery.

Android background limits, OEM battery policies, SIM removal, permissions, and actual carrier callbacks require physical-device testing. No cloud push service or paid messaging infrastructure is used.

## Email and updates

Email history stays read-only in Android and is loaded from EMS. Email sending/configuration remains in EMS and is separate from the SMS job path. Independently, the app can use a separate Control-issued token to send heartbeats and a status-only SMS monitoring copy to ConnectX Control; it never sends SMS bodies to Control. Control also serves public APK release checks and downloads. Control monitoring or release services can be unavailable without blocking SMS queue polling, delivery, or authoritative reporting to EMS.

## Build

See [BUILD_ANDROID.md](BUILD_ANDROID.md). Use the committed Gradle wrapper and pass `-PemsBaseUrl=...` when building for an EMS origin other than `https://ems-v1.pages.dev`.
