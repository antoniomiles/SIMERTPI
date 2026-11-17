# DEV simulated checkout

The `dev` Spring profile now selects `SANDBOX_STUB`, enables the sandbox and
sets its outcome to `APPROVED`. The provider returns deterministic technical
references. It does not contact an external payment service.

Checkout continues through `PaymentIntegrationService`: authenticate the citizen,
validate session ownership and the idempotency key, persist the payment and its
attempt, dispatch the simulator, then apply approval through `PaymentService`.
Approval, the ACTIVE session, the approved attempt and the outbox event commit
in the same database transaction. Flutter must use the returned persisted IDs.

Production keeps the default UNCONFIGURED provider and sandbox disabled. The
sandbox bean requires exclusively dev/test profiles and rejects mixed production
profiles. No client flag or request field enables approval.

For an existing DEV deployment, review `SPRING_PROFILES_ACTIVE=dev` and any
explicit `SIMERTPI_PAYMENT_PROVIDER` / `SIMERTPI_PAYMENT_SANDBOX_ENABLED`
overrides: existing UNCONFIGURED/false overrides take precedence over defaults.
The expected DEV values are SANDBOX_STUB/true. Configuration changes and deployment
require the deployment owner's authorization; this change does not modify Render
configuration or existing payments/sessions.

An explicit alternative sandbox outcome remains available through the existing
Spring `simertpi.payments.sandbox.outcome` property for controlled failure testing.
