# Nedu Wallet — real-money release gate

**Status: NOT APPROVED FOR LIVE CUSTOMER FUNDS.** This document is a release checklist, not authorization to deploy. Current branch work is development-only. The existing production Supabase project and iOS app must be preserved.

## What is confirmed

- Android branch: `feature/functional-account-backend`.
- Existing Supabase project used by iOS: `https://yybmhqviescjpplqfaok.supabase.co`.
- Existing data model: one profile per Supabase Auth user and multiple wallet rows per user (NGN, USD, EUR, GBP, CAD, USDT, USDC).
- The Auth signup trigger creates the profile and default wallets.
- Flutterwave Live secret has been configured by the owner in Supabase secrets. It must remain server-side and must not be used for development tests.
- Current Android and backend CI success proves build/static/local checks only. It does not prove live provider operations.

## Release blockers

### 1. Architecture and identity

- [ ] Keep Supabase Auth as the only customer identity system for Android and iOS.
- [ ] Do not deploy the standalone `backend/` NestJS/Prisma prototype as the wallet API. Its independent `User` and single-wallet schema are incompatible with the existing Supabase identity and multi-currency model.
- [ ] Complete regression tests for signup, login, session refresh, sign-out, profile reads/updates, and wallet reads using the same Supabase project.
- [ ] Verify every privileged Edge Function validates the Supabase access token and derives the user ID from the verified token.

### 2. Flutterwave NGN virtual accounts and deposits

- [ ] Implement the iOS-compatible `create-virtual-account` endpoint only after validating the exact current Flutterwave NGN static/dynamic account product contract and required KYC fields.
- [ ] Implement `link-existing-virtual-account` with provider-side ownership verification. Never trust a submitted user ID, account number, or bank name as proof of ownership.
- [ ] Implement a webhook endpoint that verifies Flutterwave's configured webhook hash, then independently verifies each transaction with Flutterwave.
- [ ] Match provider transaction ID, reference, beneficiary/account mapping, status, currency, and amount.
- [ ] Add database-enforced idempotency and concurrency-safe crediting. Webhook retries and simultaneous sync requests must never credit twice.
- [ ] Implement `sync-virtual-payments` as a server-side reconciliation path that uses the verified account mapping and returns no raw provider/debug data.
- [ ] Never credit a wallet from a client assertion or an unverified webhook body.
- [ ] Do not mark KYC complete because an account number exists.
- [ ] Flutterwave's documented virtual-account product does not establish that USD/EUR/GBP/CAD accounts are supported for this project. Do not advertise those as real bank accounts without a separately confirmed provider/issuer agreement.

### 3. Ledger and money movement

- [ ] Review the exact live schema, indexes, constraints, triggers, grants, and policies again immediately before any migration.
- [ ] Design an append-only, auditable transaction/ledger flow compatible with the existing schema.
- [ ] Fix and test unsafe existing SECURITY DEFINER RPCs before any mobile client can invoke them. In particular, remove caller-controlled user ownership, validate positive amounts/currencies/rates, and make updates atomic.
- [ ] Remove public execution rights from internal provisioning and financial functions only after a complete caller/dependency review and iOS compatibility plan.
- [ ] Do not expose direct client writes to wallet balances or transaction history.
- [ ] Implement internal transfers with atomic debit/credit and external bank transfers with pending reservations, provider settlement verification, failure release/reversal, limits, and reconciliation.
- [ ] Implement swaps using authoritative server rates, deterministic wallet locking, decimal arithmetic, fees, and auditable applied-rate records.
- [ ] Do not enable withdrawals, transfers, swaps, or card issuing until each has a separately tested provider and operational workflow.

### 4. Mobile app and user protection

- [ ] Remove or clearly label simulated card details, fake crypto addresses/mnemonics, local-only security toggles, demo recipient resolution, and placeholder QR actions.
- [ ] Do not store KYC/account state in unscoped local preferences; clear sensitive cached state on sign-out and account switching.
- [ ] Use decimal strings for amounts at API boundaries; do not calculate authoritative money values with floating-point types.
- [ ] Add loading, failure, pending, completed, reversed, and reconciliation states to all financial screens.
- [ ] Confirm red Nedu Wallet branding and app icon in the built APK and on-device launcher.
- [ ] Add privacy policy, terms, support contacts, complaint handling, transaction receipts, and account closure/data-retention procedures.

### 5. Security, operations, and compliance

- [ ] Separate staging and production Supabase projects and use only Flutterwave Test credentials in staging.
- [ ] Add request throttling, origin allowlists where applicable, strict input limits, safe audit logging, secret rotation, and monitoring.
- [ ] Never log BVN/NIN, full bank-account numbers, bearer tokens, provider secrets, or raw sensitive webhook payloads.
- [ ] Test unauthenticated calls, cross-user access, invalid/zero/negative amounts, overflows, duplicate/concurrent webhook events, provider timeouts, retries, reversals, and transaction rollback.
- [ ] Establish daily provider-to-ledger reconciliation, exception handling, support escalation, incident response, backups, restore tests, and a tested rollback plan.
- [ ] Obtain qualified Nigerian legal/compliance review and all required regulatory, banking, and Flutterwave approvals for the actual wallet, custody, payment, FX, and transfer activities.

## Live-mode code preparation (not deployment authorization)

- The `resolve-account` Edge Function now recognizes an explicitly selected Live environment only when `FLW_ENVIRONMENT=live`, the configured secret is a non-test Flutterwave secret, and `FLW_LIVE_ENABLED=true`.
- `FLW_LIVE_ENABLED` must remain unset or false during development and review. This is a bank-account name-resolution endpoint only; it does not create accounts, charge customers, transfer funds, or credit wallets.
- This code change has not been deployed to Supabase and has not made a Live API request.
- Before any production enablement, verify the exact Flutterwave account-resolution contract, rate limits, costs, KYC/consent requirements, monitoring, and rollback procedure. Require a separate explicit owner approval.
- This gate does not authorize enabling any other Live payment, virtual-account, webhook, deposit, withdrawal, transfer, swap, or card operation.

## Unsafe migration explicitly excluded

Do **not** deploy `supabase/migrations/20261120150000_supabase_foundation.sql` to the existing project. It assumes an incompatible schema and could damage compatibility with the live iOS app. Only additive, reviewed migrations may be considered after staging tests and a production backup.

## Test credential requirement

The owner has confirmed the configured `FLW_SECRET_KEY` is a Live key. A separate Flutterwave Test key is required in a separate staging/test secret store before provider integration tests can be performed. Do not replace, expose, or use the production Live key for those tests.

## Approval boundary

No production migration, Edge Function deployment, grant change, PR merge, Live Flutterwave request, or real-money operation is authorized by this checklist. Those steps require an explicit owner approval after the above release gates have evidence of passing.
