# Existing Supabase Project Compatibility Plan

## Objective

Reuse the Supabase project already connected to the working Nedu Wallet iOS app. The Android implementation must use that same project without replacing existing tables, resetting data, or breaking iOS.

This branch is a compatibility/audit workstream. It is not permission to deploy or change the live database.

## Current decision

**Do not apply `20261120150000_supabase_foundation.sql` to the existing project.**

The current foundation migration assumes a different data model:

- Its `profiles` definition expects fields such as `first_name`, `last_name`, and `is_active`; the existing table instead has `name NOT NULL`, `email NOT NULL`, `full_name`, `avatar_url`, and other existing fields.
- Its `wallets` definition assumes one wallet row per user with a `currency/status` model. The existing table stores multiple currency rows per user using `code`, `name`, `balance`, and `is_crypto`, with a unique constraint on `(user_id, code)`.
- Its signup trigger and account endpoint rely on the incompatible schema.

A successful local CI run only proves that the migration works against the clean local schema created by this repository. It does **not** prove compatibility with the existing iOS project's live schema.

## Existing live schema facts supplied during the audit

Existing tables include `profiles`, `wallets`, `transactions`, `rates`, `user_security`, `user_backups`, `cards`, `nedu_cards`, `purchase_orders`, `purchase_transactions`, `gift_card_orders`, `esim_orders`, and `bill_validations`.

Important existing structures:

- `profiles.id` references `auth.users(id)`; existing profile columns must be preserved.
- `wallets` has `id`, `user_id`, `code`, `name`, `balance`, `is_crypto`, `address`, and `updated_at`.
- `wallets` has a non-negative balance check and a unique constraint on `(user_id, code)`.
- `transactions` is the existing transaction history table and references `auth.users(id)`.
- Existing RLS policies generally scope rows to the authenticated user's own `user_id` (or `profiles.id`).

The existing iOS app is reported to be using this project. Do not rename, drop, recreate, or repurpose these tables as part of the Android migration.

## High-priority security finding

The supplied privilege check showed that these four existing `SECURITY DEFINER` functions are executable by `anon`, `authenticated`, and `service_role`:

- `public.add_money_to_wallet(uuid, text, numeric, text, text)`
- `public.create_default_wallets(uuid)`
- `public.send_money(uuid, text, numeric, text, text, text, text, text)`
- `public.swap_money(uuid, text, text, numeric)`

This is a serious authorization exposure. It does not by itself prove that anyone exploited the functions.

Do not revoke or replace these functions blindly: the iOS app or signup triggers may depend on them. First establish all call sites and current client RPC usage. Then prepare a narrowly scoped hardening migration that preserves legitimate iOS behavior, verifies the caller identity for user-facing RPCs, restricts internal provisioning functions, sets a fixed safe `search_path`, validates positive amounts, and makes funding idempotency race-safe. Any live hardening migration must be reviewed and explicitly approved before execution.

## Required read-only dependency audit

Before changing the wallet functions, run this query in the Supabase SQL Editor and review the complete output:

```sql
SELECT
    'TRIGGER' AS source_type,
    event_object_schema || '.' || event_object_table AS source_name,
    trigger_name AS reference_name,
    action_statement AS definition
FROM information_schema.triggers
WHERE action_statement ILIKE ANY (ARRAY[
    '%create_default_wallets%',
    '%add_money_to_wallet%',
    '%send_money%',
    '%swap_money%'
])
UNION ALL
SELECT
    'FUNCTION',
    n.nspname || '.' || p.proname,
    'FUNCTION BODY',
    p.prosrc
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE p.prosrc ILIKE ANY (ARRAY[
    '%create_default_wallets%',
    '%add_money_to_wallet%',
    '%send_money%',
    '%swap_money%'
])
AND p.proname NOT IN (
    'create_default_wallets',
    'add_money_to_wallet',
    'send_money',
    'swap_money'
);
```

Also inspect the iOS source/configuration or its RPC call list, if available, to determine which functions are called directly by the client. Never share API secrets, service-role keys, transaction PINs, or passwords in chat.

## Compatibility implementation sequence

1. **Freeze live deployment.** Keep the deployment workflow manual and do not run it against the existing project yet.
2. **Record the existing schema.** Preserve the current columns, constraints, indexes, RLS policies, triggers, and function definitions as a baseline.
3. **Map the iOS contract.** Identify current RPC calls, Edge Function calls, auth/session behavior, table reads/writes, and expected response shapes.
4. **Redesign migrations as additive.** Use new, clearly named tables only where required. Do not create incompatible replacements for `profiles`, `wallets`, or `transactions`. Any alteration to existing tables must be justified by a reviewed compatibility requirement.
5. **Redesign Edge Functions.** Derive the user from the verified Supabase JWT; never trust a caller-supplied `user_id` as authorization. Keep privileged provider credentials server-side. Use database transactions/RPCs for atomic balance changes and ledger operations.
6. **Preserve money precision.** Do not convert currency balances to JavaScript floating-point numbers. Use PostgreSQL numeric arithmetic and return decimal values as strings where appropriate.
7. **Test on a schema-faithful staging copy.** A clean local database is not enough. Tests must include the existing schema and representative iOS flows.
8. **Deploy additively and verify.** Only after explicit approval, deploy one reviewed migration/function at a time. Verify login, profile, wallet balances, history, funding, send, swap, and existing iOS behavior before enabling Android production traffic.

## Account endpoint contract

The current `account-me` Edge Function is not compatible with the existing schema because it selects non-existent profile and wallet fields and assumes one wallet per user.

The replacement should be designed around the existing profile fields and a **collection of currency wallets**. Before fixing a public response shape, inspect the Android client screens and current iOS response contract. Do not silently change the client-facing JSON contract.

## Deployment prerequisites

The deployment workflow expects GitHub Actions secrets:

- `SUPABASE_ACCESS_TOKEN`
- `SUPABASE_PROJECT_REF`
- `SUPABASE_DB_PASSWORD`

These must be configured by the repository owner through GitHub settings. Never commit them or paste them into source code or chat. The deployment workflow must remain unused until the migration compatibility review is complete.

## Regulatory and payment safety

Provider API credentials or company registration do not alone establish authorization to hold customer funds, execute transfers/withdrawals, or issue cards. Before enabling live money movement, confirm the relevant Nigerian regulatory, banking, payment-provider, and card-program approvals and operational controls.

## Current status

- [x] Existing schema facts recorded from user-provided read-only SQL output.
- [x] Incompatible foundation migration identified.
- [x] Privilege exposure recorded for four existing SECURITY DEFINER functions.
- [x] Local CI run 44747289879 reported successful by GitHub job results.
- [ ] Trigger/function call-site audit reviewed.
- [ ] iOS RPC/client call map completed.
- [ ] Existing-schema-compatible migrations and Edge Functions implemented.
- [ ] Schema-faithful integration tests passed.
- [ ] Live deployment explicitly approved and completed.
