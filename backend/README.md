# Nedu Wallet API prototype — NOT the production wallet backend

## Critical architecture notice

The Android application in this repository is being integrated with the existing Supabase project used by the iOS app. This NestJS/Prisma service is a separate prototype with its own `User`, `Wallet`, `LedgerAccount`, and PostgreSQL schema. It does **not** authenticate against Supabase Auth, does **not** read the existing seven-row-per-user Supabase wallet model, and is **not** connected to the live Nedu Wallet project.

**Do not deploy this service as the production wallet backend, point the Android app at it, or create customer accounts in it.** Doing so would create a second, disconnected account and balance system. The Prisma schema and migrations are not migrations for the existing Supabase project.

## Payment safety

- The Flutterwave adapter is sandbox-only. It requires `FLW_ENVIRONMENT=test` and a secret beginning with `FLWSECK_TEST-`.
- The adapter uses a fixed Flutterwave API host and cannot be redirected to an arbitrary host.
- A Live key is deliberately rejected by this branch. Do not paste provider secrets into source control, Android configuration, chat, or build logs.
- Transfers/withdrawals remain disabled.
- A successful build does not establish provider, webhook, ledger, compliance, reconciliation, or production readiness.

## Known prototype limitations

The standalone Prisma model has one wallet per user and is incompatible with the existing Supabase multi-currency wallet model. Its separate password/JWT authentication system would create identities unrelated to existing Supabase Auth users. Its payment/webhook implementation has not passed the required concurrency, retry, reconciliation, fraud, and end-to-end tests. It must not be used to hold or move customer funds.

## Local sandbox-only development

Use synthetic users and a dedicated disposable PostgreSQL database only. Configure a Flutterwave Test key in a local secret store and set `FLW_ENVIRONMENT=test`. Never use the existing production Live key for local or CI work.

## Correct production architecture

The production Android app must continue to use the existing Supabase Auth project and its existing `profiles`, `wallets`, `transactions`, and related tables. Provider credentials belong only in reviewed server-side Supabase Edge Function secrets. Database migrations must be additive, reviewed against the actual live schema, and tested in a separate staging Supabase project before any production deployment.

See:
- `../supabase/IOS_SUPABASE_COMPATIBILITY_AUDIT.md`
- `../supabase/IOS_VIRTUAL_ACCOUNT_FUNCTION_AUDIT.md`
- `../supabase/SECURE_MONEY_OPERATIONS_PLAN.md`
- `../supabase/REAL_MONEY_RELEASE_GATE.md`
