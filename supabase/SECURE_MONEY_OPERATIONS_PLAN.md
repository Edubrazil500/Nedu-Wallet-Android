# Secure money operations: implementation specification

## Status

Design-only checkpoint. This document does not change the live Supabase project, alter existing RPCs, deploy Edge Functions, or move funds. Implementation is blocked on the complete read-only database audit and the existing iOS RPC call sites.

## Existing system that must be preserved

The production project is the existing iOS Supabase project. Its wallet model is multiple rows per user, keyed by `(user_id, code)`, with `balance` stored as PostgreSQL numeric. Existing tables and RPC signatures must not be replaced by the incompatible foundation migration.

The currently supplied RPC bodies are unsafe for direct mobile invocation:

- `add_money_to_wallet` trusts a caller-supplied user ID and payment reference; its `exists` check is not concurrency-safe and does not verify Flutterwave.
- `send_money` trusts a caller-supplied user ID, deducts funds, and marks non-bank sends completed without a demonstrated recipient-credit or settlement process.
- `swap_money` trusts a caller-supplied user ID and does not validate positive amounts, distinct currencies, or valid nonzero rates.
- `create_default_wallets` trusts a caller-supplied user ID.

All four are `SECURITY DEFINER`. Prior owner-provided diagnostics reported execution grants to `anon` and `authenticated`; the complete current grants and function settings still need confirmation.

## Required security boundary

1. Every user-initiated operation must require a valid Supabase access token.
2. The server must verify the token and derive the effective user ID from the verified identity. A request-body `user_id` must never authorize access to an account.
3. Android must never contain the service-role key, Flutterwave secret key, or webhook secret.
4. The service-role key may be used only inside a narrowly scoped Edge Function after authentication and authorization, with all target rows constrained to the verified user. Prefer caller-JWT/RLS access wherever feasible.
5. Client-facing errors must not expose SQL, provider responses, secrets, or personal data.
6. Amounts remain decimal strings at API boundaries and PostgreSQL `numeric` in storage. Do not convert money to JavaScript or Kotlin floating-point types.
7. Financial writes, transaction records, idempotency claims, and any ledger entries must commit or roll back together.
8. Provider deposits are credited only after server-side Flutterwave verification. A client assertion or an unverified webhook payload is never proof of payment.

## Operation contracts to implement after audit

### Deposit / add funds

- Create or receive a server-generated payment reference.
- Verify the transaction directly with Flutterwave using server-held credentials.
- Match provider transaction ID/reference, amount, currency, status, and the beneficiary/account mapping.
- Enforce a database unique key for the provider transaction identity after checking existing duplicate data.
- In one database transaction, claim the idempotency key, update the correct wallet, and insert the transaction record.
- Retries return the prior result and never credit twice.
- The old `add_money_to_wallet(p_user_id,...)` must not remain callable by anonymous or arbitrary authenticated clients.

### Send / bank transfer

- Authenticate the sender and derive sender ID from the token.
- Validate a strictly positive amount, supported currency, recipient details, limits, and sufficient available balance.
- Lock the sender wallet row before checking and deducting funds.
- Use a server-generated idempotency key and persist a pending transaction/reservation.
- Do not label an external bank transfer completed until provider settlement is verified.
- For internal wallet transfers, verify recipient identity and credit the recipient in the same database transaction as the sender debit.
- Define reversal/refund handling for failed or timed-out provider transfers before enabling the feature.

### Currency swap

- Authenticate and derive the owner ID from the token.
- Reject non-positive amounts, identical currencies, unsupported currency pairs, missing/zero/negative rates, and insufficient funds.
- Lock both wallet rows in deterministic currency-code order to reduce deadlock risk.
- Calculate using PostgreSQL numeric and a documented rounding rule per currency.
- Record the exact rate, fee/spread, source amount, destination amount, and transaction reference so the result is auditable.
- Debit, credit, and transaction record must be atomic.

### Default wallet provisioning

- Confirm the existing Auth signup trigger and all callers first.
- Prefer a trigger that uses `NEW.id` or a server-side function that derives the authenticated identity; do not expose a public function that accepts an arbitrary user ID.
- Preserve the seven existing currency rows and the unique `(user_id, code)` key.
- Make provisioning safe to retry without creating duplicate wallets.

## Required database review before writing migrations

Obtain and review the remaining read-only audit output:

- Exact columns and types for `transactions` and `rates` (plus related financial tables).
- Trigger definitions and trigger-function bodies.
- Constraints and indexes, including uniqueness of provider references.
- RLS enabled/forced state and all policies.
- Direct table grants for `anon`, `authenticated`, and `service_role`.
- Other database functions that read or write wallets/transactions.

No production grant revocation, constraint creation, table change, or RPC replacement is authorized by this specification.

## Required iOS compatibility review

The iOS source is not in this Android repository. Before changing grants or RPC signatures, search the iOS Xcode project for:

- `add_money_to_wallet`, `send_money`, `swap_money`, and `create_default_wallets`
- `supabase.rpc` and direct writes to `wallets` or `transactions`
- Flutterwave initialization, webhook/payment sync, and virtual-account funding
- signup triggers or client-side provisioning

Existing iOS flows must be tested against staging before any production permission changes.

## Delivery and release gates

1. Complete schema and iOS call-site audit.
2. Agree on the operation semantics, fees, limits, recipient settlement, and reversal rules.
3. Write additive, reversible migrations and Edge Functions on the feature branch.
4. Add automated tests for unauthenticated access, cross-user attempts, negative/zero amounts, insufficient funds, duplicate/concurrent requests, invalid rates, provider failures, and transaction rollback.
5. Test against a separate staging Supabase project with synthetic accounts and no real funds.
6. Run Android CI and regression-test iOS.
7. Obtain an explicit approval, production backup, and rollback plan before any live deployment.

## Current release state

The implementation PR remains a draft and is not merged. No new financial operation is enabled in Android. No live database change or deployment has been performed.
