# Existing Supabase project compatibility assessment

## Current decision

**Do not deploy the current foundation migration or account-me function to the existing iOS Supabase project.** The live schema inspected by the project owner is materially different from the schema assumed by this branch. The existing NestJS and Android code remain unchanged.

This branch is an isolated compatibility workstream. No live Supabase database has been changed.

## Confirmed live schema

The existing project already contains:
- `profiles`: `id, name, email, phone, country, kyc_status, kyc_level, created_at, virtual_account_number, virtual_bank_name, virtual_order_ref, full_name, avatar_url`.
- `wallets`: one row per user and currency, with `id, user_id, code, name, balance, is_crypto, address, updated_at`; unique key is `(user_id, code)`.
- `transactions`: existing wallet activity table.
- `rates`: currency conversion rates.
- Existing purchase, card, gift-card, eSIM, security and backup tables.
- Existing RPCs: `add_money_to_wallet`, `create_default_wallets`, `send_money`, `swap_money`.

The owner confirmed that the four RPCs are executable by `anon`, `authenticated` and `service_role`. That is an excessive privilege grant for wallet-changing functions.

## Incompatibilities found in the proposed replacement

| Area | Existing project | Proposed foundation | Risk |
|---|---|---|---|
| Profiles | `name NOT NULL`, `email NOT NULL`, existing KYC and virtual-account fields | new first/last-name and active fields; email nullable | conflicts with existing table definition and existing app expectations |
| Wallets | multiple currency rows per user; `code/name/balance/is_crypto` | one wallet per user; `currency/status` | incompatible unique key and data model |
| Ledger | existing `transactions` and balances | new journal/ledger tables | no verified migration/reconciliation plan |
| Signup | existing profile/wallet provisioning not yet fully traced | new Auth trigger inserts new profile and wallet model | could conflict with existing signup trigger and schema |
| account-me | must return existing profile and multiple currency wallets | queries first/last name, is_active, one wallet and ledger_accounts | will fail against current schema |
| Android | currently demo UI; no Supabase SDK dependency | expects a future Supabase integration | no production auth or data flow yet |

## Existing NestJS behavior found in repository

- Registration creates an application user, a single NGN wallet and a ledger account.
- Login uses application-managed bcrypt password hashes and JWTs.
- Wallet read calculates available balance from ledger entries.
- Deposit initialization creates a pending payment reference and requests Flutterwave checkout.
- Dynamic virtual-account creation calls Flutterwave and stores account details.
- Webhook handler validates the configured webhook hash, verifies a transaction with Flutterwave, then posts a balanced journal.
- Transfer endpoint is intentionally disabled in NestJS until controls and approvals exist.

The NestJS Prisma schema describes a third schema model (one NGN wallet per user and new ledger tables); it is not the same as the inspected live iOS Supabase schema. It must not be applied to the existing project as-is.

## Security observations and unresolved compatibility

The wallet RPC grants were confirmed too broad. However, it is not yet known whether the working iOS app calls these RPCs directly, calls them through another trusted service, or uses an alternate path. The SQL hardening draft is therefore review-only and must not be deployed until the iOS call sites and any external integrations are identified.

The earlier read-only query found no database trigger/function bodies calling the four wallet RPCs. That does **not** rule out calls from iOS clients, Edge Functions, external services, or scheduled jobs.

The existing `add_money_to_wallet` idempotency check uses `transactions.to_address` without a confirmed unique constraint. A concurrent duplicate funding event could race. Do not add a unique constraint until production duplicates and existing semantics have been checked.

## Safe implementation plan

1. Obtain and inspect the iOS Xcode project or at minimum its Supabase client/RPC call sites. Search for the four RPC names, `supabase.rpc`, direct writes to `wallets` and `transactions`, Auth signup hooks and funding webhook paths.
2. Inventory all live triggers, policies, function signatures/definitions, grants, indexes, constraints and existing data counts. Keep this read-only.
3. Design an additive migration that preserves the existing tables and columns. Do not rename or replace `profiles`, `wallets`, `transactions` or existing RPC signatures.
4. Decide how to introduce a double-entry ledger alongside the existing balances, including reconciliation and rollback. Never silently migrate or recalculate balances.
5. Harden each RPC based on verified call paths. Enforce `auth.uid() = p_user_id` for user-originated actions. Keep provider-confirmed funding server-only. Use a fixed search_path. Handle Auth-trigger provisioning separately if it exists.
6. Implement Supabase Edge Functions using caller JWT validation and least-privilege database access. Provider secrets remain in Supabase secrets; never in Android.
7. Make Android use Supabase Auth and the actual existing multi-wallet schema; only enable financial actions after server authorization and end-to-end tests.
8. Test against a separate Supabase staging project restored from a reviewed schema/data-safe snapshot. Verify existing iOS login, wallet reads, funding, send, swap, purchases and card flows before any production release.
9. Back up production and require explicit owner approval before applying any production migration or changing live RPC privileges.

## What cannot be completed from this repository alone

The iOS source is not present in this GitHub repository. Repository search cannot establish whether the iOS app invokes these RPCs. The connected Supabase project is not accessible through the available GitHub repository tools. Therefore no live SQL, permission changes, Edge Function deployment, or iOS regression test has been performed.

## Required artifacts before production rollout

- iOS call-site inventory
- live schema/function/policy export (read-only)
- existing-project-compatible migration
- tested Edge Functions and Flutterwave webhook path
- Android Supabase Auth/data integration
- staging test report and production backup/rollback plan
- explicit approval for production changes
