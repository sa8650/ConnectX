# ConnectX Android — direct EMS gateway

ConnectX is the native Kotlin + Jetpack Compose Android app that polls EMS and sends authorized SMS messages through a selected physical SIM.

## Delivery path

```text
EMS persistent SMS queue ↔ HTTPS ↔ ConnectX Android → selected physical SIM → recipient
```

- EMS is the source of truth for job persistence, shop/device authorization, SIM selection, templates, limits, licensing/add-ons, automatic transaction triggers, due reminders, and message history.
- Android authenticates directly with EMS, registers against an authorized shop, atomically claims jobs, and reports send/delivery outcomes directly to EMS.
- ConnectX Control is separate from the SMS path: after registering a shop/device with EMS, the app automatically posts its device, shop/administrator, selected SIM, app version, and status to `https://connectxweb.pages.dev/api/telemetry/register`. Control issues a separate token for heartbeats and status-only SMS copies. Control also hosts APK releases; no enrollment code is required.
- Existing outgoing email history is read-only in the Android app; EMS email behavior is separate from SMS dispatch.

The default EMS origin is `https://ems-v1.pages.dev`. Set the build property `emsBaseUrl` for another EMS deployment. The independent Control release origin is `https://connectxweb.pages.dev` by default.

## Current app

- Package: `com.connectx.gateway`
- Version: `2.2.0` / version code `22`
- Min SDK 26; target SDK 35; compile SDK 35
- Direct EMS device tokens are `cxd_…` credentials; EMS stores their hashes.
- Legacy Control device/pairing tokens are not used. Users must sign in to EMS and register the phone after updating.

## Build and test

Requires JDK 17+ and Android SDK 35. A Gradle 9.3.1 wrapper is included:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease -PemsBaseUrl=https://your-ems-host.example
```

Full instructions and the physical-device checklist: [BUILD_ANDROID.md](BUILD_ANDROID.md).

## Related projects

- `../EMS` — EMS backend, persistent SMS queue, direct device API, and database migrations.
- `../ConnectXControl` — management site, email provider management, audit, and Android release hosting. No Control delivery service is required.

See the cross-project rollout instructions in [../MIGRATION_DEPLOYMENT.md](../MIGRATION_DEPLOYMENT.md).
