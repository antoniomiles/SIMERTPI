# CP24.1 Android FCM setup

For the Windows/PowerShell Firebase DEV steps, V34 Render checklist and
physical test matrix, see [CP24.1 Firebase DEV runbook](CP24_1_FIREBASE_DEV_RUNBOOK.md).

CP24.1 uses the existing notification outbox, delivery rows, preferences,
device registry and retry dispatcher. It does not create a second notification
pipeline. FCM is selected only when explicitly enabled.

## Firebase project setup

Create or select the SIMERTPI Firebase project, enable Firebase Cloud
Messaging, and register the Android application using the exact package name
`ec.gob.simertpi.simertpi_citizen_app`. The Flutter application initializes
Firebase from public Android client configuration supplied as build defines;
it does not require `google-services.json` or the Google Services Gradle
plugin. Client identifiers are not server credentials and must still be
restricted to the Android application where Firebase supports such
restrictions.

The client build accepts these defines from the Firebase Android app:

- `FCM_FIREBASE_API_KEY`
- `FCM_FIREBASE_APP_ID`
- `FCM_FIREBASE_SENDER_ID`
- `FCM_FIREBASE_PROJECT_ID`

When any value is absent, the optional FCM runtime is unavailable and the app
continues to work. Firebase is not initialized at app startup. Initialization
and permission prompting occur only after the citizen explicitly chooses to
enable mobile notifications. Android manifest defaults disable Messaging
auto-initialization and Analytics collection; token acquisition and backend
registration also require OS permission, stored citizen consent, an enabled
backend preference, and an authenticated session. No values are committed to
this repository.

## Backend DEV configuration

Set the following only after Firebase project setup and credential validation:

- `SIMERTPI_NOTIFICATION_FCM_ENABLED=true`
- `SIMERTPI_NOTIFICATION_PUSH_PROVIDER=FCM`
- `SIMERTPI_FCM_PROJECT_ID=<Firebase project id>`
- `GOOGLE_APPLICATION_CREDENTIALS=<path to mounted service-account file>`

The service-account JSON must be provided as a protected secret file or
workload identity outside Git. Do not put a private key in a regular
environment variable, source file, log, or build artifact. When FCM is enabled
without project ID or Application Default Credentials, backend startup fails
closed. With the default `FCM_ENABLED=false`, existing IN_APP behavior remains
available and no Firebase credentials are loaded (`SIMERTPI_NOTIFICATION_FCM_ENABLED=false`).

The dispatcher retries retryable FCM failures using its existing bounded
policy. FCM does not provide an idempotency key for a single-device send; an
uncertain in-flight result is therefore not blindly resent by stale-delivery
recovery. An `UNREGISTERED` token is marked invalid and its device is disabled.

## Android permission and manual physical test

Android 13+ requires the citizen's `POST_NOTIFICATIONS` permission. Rejection
does not disable app features. The citizen can later open Android notification
settings from the notification preferences screen. For a real-device test,
install a build carrying the four client defines, sign in, enable the SIMERTPI
preference and Android permission, then send a real business event through the
normal backend workflow. Do not create a second inbox event from the client.

`PARKING_ENDING_SOON` and `PARKING_EXTENSION_CONFIRMED` pushes carry only a
notification id, event type, resource type and parking-session id. On tap the
app routes to Home's active-parking panel and reloads the authenticated
citizen's sessions; the existing Extend action performs its normal backend
eligibility, quote and payment flow. A missing, invalid, stale, or unauthorized
target falls back to the inbox. Other events retain the inbox destination.
The client never starts payment or changes a session from a tap. All states,
quotes and payment outcomes are re-read from the backend.

An extension-confirmed event is added to the existing payment outbox in the
same transaction that commits an APPROVED payment and applies the extension.
Its IN_APP rule always remains available. A PUSH notification/delivery is
created only when the citizen's PUSH preference is enabled; Android permission
and consent are additional device-side conditions. The message uses the
server-computed `America/Guayaquil` end time. Pending, failed, rejected, or
unknown payments do not produce this event. V34 adds the event type/rules and
logical inbox projection without editing applied migrations.

An operating-system permission denial/revocation does not block app features.
Revocation deactivates the owned backend device when authenticated. If remote
deactivation cannot be confirmed (for example after an expired session), the
device id and owner id are retained in secure local storage as a pending
revocation and retried after that same owner authenticates. The local FCM token
is deleted where Firebase was initialized. A different account cannot replay
another user's revocation; backend ownership remains authoritative.

## Scope and physical verification

No email provider or WhatsApp provider is configured by CP24.1. Email delivery
remains CP24.2; WhatsApp remains out of scope.

No FCM credentials or Render settings are supplied by this change. End-to-end
delivery still requires a Firebase project/Android app, the four public app
defines, a protected backend service-account/ADC configuration, enabling the
FCM provider and PUSH rules/preferences, and a physical Android test. Provider
acceptance is not proof that the operating system displayed the notification.
