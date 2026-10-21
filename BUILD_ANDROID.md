# Building ConnectX Android (v2.2.0 / build 22)

## Architecture

ConnectX Android signs in and registers directly with EMS. The app calls EMS over HTTPS for administrator authentication, shop/device registration, heartbeats, SMS claims, acknowledgements, SMS history, and email history. SMS is sent by Android's `SmsManager` through the shop-selected physical SIM, and authoritative delivery results go directly to EMS. Separately, after registering the shop/device with EMS, the app automatically posts the same device, shop/administrator, selected SIM, app version, and status details to `https://connectxweb.pages.dev/api/telemetry/register`. Control returns a separate revocable token for monitoring heartbeats and a status-only SMS copy; no enrollment code is used, and SMS bodies are never uploaded. Control also serves the independent public APK update check/download and is not an SMS intermediary or delivery dependency.

The EMS origin is compiled into the app. Default: `https://ems-v1.pages.dev`. Override it for a deployment with the Gradle property `emsBaseUrl`; do not use the Control origin. The Control release origin is independently compiled as `https://connectxweb.pages.dev` by default.

## Prerequisites

- JDK 17 or later (the committed Gradle daemon criteria use JDK 25)
- Android SDK Platform 35 and Android build tools
- Gradle Wrapper 9.3.1 (`./gradlew` is included)
- Existing Android signing key for releases, kept outside source control

## Build and test

```bash
# JVM unit tests, then build each APK variant serially
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease -PemsBaseUrl=https://your-ems-host.example

# Optional full lint
./gradlew lint
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`. The release build can be signed through Android Studio or a local Gradle signing configuration. Do not commit a keystore, passwords, or production tokens.

The repository's Gradle memory settings limit parallelism for smaller build hosts. EMS sets the baseline polling interval with `CONNECTX_POLL_INTERVAL_SECONDS` (10–300 seconds; default 30). Android adapts to queue activity and network failures, with immediate polling when connectivity returns. Deprecation warnings about existing Compose icon/Android telephony APIs are non-fatal.

## Upgrade and first run

The package remains `com.connectx.gateway`; `versionCode` is 22 and `versionName` is 2.2.0. A new direct-EMS install/update requires an APK signed by the same key as the existing installation. After updating, the operator signs in directly to EMS and registers the phone against each authorized shop. Legacy Control device tokens are not reused; the EMS registration creates a new hashed/revocable EMS device token. Select the correct SIM per shop and run a local test SMS before enabling automatic delivery.

SMS statuses distinguish `pending`, `processing`, Android `sent` acceptance, carrier-confirmed `delivered`, `failed`, and `unknown`. An Android send-acceptance callback is not proof of recipient delivery. Ambiguous post-send outcomes are not automatically resent.

## Publishing updates

Publish the signed APK through the existing ConnectX Control **App Releases** page. Keep `https://connectxweb.pages.dev/api/public/releases/*` available for app update checks/downloads. That channel is separate from EMS authentication and SMS delivery. Increase `versionCode` for each update and retain the same Android signing certificate.

## Device verification checklist

- Sign in using an EMS administrator account; confirm the EMS URL is correct in the build.
- Verify only that administrator's EMS shops are shown and each registration is shop-scoped.
- Select a SIM, send a test message, then test an EMS queue job and check its EMS result state.
- Disable network while a job is queued, reconnect, and confirm polling resumes without Control availability.
- Remove/disable the selected SIM and confirm the app does not silently use the phone's default SIM.
- Verify carrier delivery callbacks are reported separately from SMS submission callbacks.
- Read outgoing email history; the app's email pages are read-only and remain distinct from SMS dispatch.

Carrier and OEM background-execution behavior still requires testing on physical Android devices and SIMs. Local builds and unit tests do not prove recipient delivery.
