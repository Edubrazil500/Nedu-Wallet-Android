# iOS virtual-account and payment-sync compatibility audit

## Purpose and current status

This document records the Edge Function contracts called by the existing iOS `SupabaseService` source supplied by the project owner. The Android app must reuse the same Supabase Auth identities and existing project; it must not introduce a second account system.

**Audit result:** the following four function directories/files were not present on branch `feature/supabase-live-schema-compatibility` when checked:

- `supabase/functions/create-virtual-account/index.ts`
- `supabase/functions/resolve-account/index.ts`
- `supabase/functions/link-existing-virtual-account/index.ts`
- `supabase/functions/sync-virtual-payments/index.ts`

No provider implementation has been invented. No function has been deployed, and no live Supabase data or configuration has been changed.

## Contract inventory from the iOS client

| Function | iOS request fields | iOS expected response | Status |
|---|---|---|---|
| `create-virtual-account` | email, first_name, last_name, phone, BVN, user_id; optional currency for non-NGN account requests | account_number, bank_name, account_name; possibly currency-specific banking fields | Missing; provider contract and persistence model need confirmation |
| `resolve-account` | account_number, account_bank | account_name at root or under data | Missing; provider and supported bank/country scope need confirmation |
| `link-existing-virtual-account` | user_id, account_number, bank_name | success or error | Missing; ownership verification is essential |
| `sync-virtual-payments` | user_id, virtual account number | credited count and a debug array | Missing; must not trust client-supplied identity/account or expose debug data |

The function names and request/response shapes above are taken from the supplied iOS call sites. They are not proof that a corresponding deployed function currently exists in the live Supabase project.

## Required shared security boundary

Every user-facing function must:

1. Require a valid Supabase access token and verify it server-side with Supabase Auth.
2. Derive the effective user ID from the verified token (`auth.uid()` / verified Auth user). A JSON `user_id` must never grant access to another account.
3. Use least-privilege database access and enforce ownership. Do not put the service-role key in iOS or Android.
4. Keep Flutterwave/provider credentials in Supabase Edge Function secrets only.
5. Validate HTTP method, request size, field formats and allowed currencies; return generic client errors without leaking provider credentials, internal exception details or personal data.
6. Avoid logging BVN, full account numbers, access tokens, provider secrets or payment payloads containing personal information.
7. Use request idempotency and database uniqueness/locking where an operation can create an account or credit a wallet.
8. Be tested in a separate staging Supabase project before any production rollout.

## Function-specific implementation requirements

### 1. create-virtual-account

- Authenticate the caller and ignore/reject a `user_id` that does not match the authenticated user.
- Validate required name, email, phone and country/currency fields.
- BVN is highly sensitive personal data. Collect it only where the selected provider flow genuinely requires it; transmit it over TLS directly to the trusted server-side provider integration; do not persist or log it unless a documented lawful/provider requirement requires this.
- Confirm the exact Flutterwave product/API, request schema, response schema, supported countries/currencies and account lifecycle before writing provider code.
- Make creation idempotent so retries do not accidentally issue multiple accounts.
- Persist only fields supported by a reviewed schema. The supplied existing `profiles` table has `virtual_account_number`, `virtual_bank_name` and `virtual_order_ref`; it does not establish storage columns for every foreign-currency account field. Do not add columns or tables without an additive migration review.
- Do not mark KYC as verified merely because an account number was returned. Preserve the actual provider/compliance KYC status.

### 2. resolve-account

- Confirm which provider and bank-transfer rails the iOS app expects.
- Validate account number and bank code formats and enforce request throttling to prevent enumeration/abuse.
- Authenticate where the provider's product permits it; if a public pre-transfer lookup is genuinely required, apply strict rate limits and return only the minimum account-name result.
- Never use account-resolution success as proof of account ownership or KYC completion.

### 3. link-existing-virtual-account

- Authenticate the caller; derive the target user from the token.
- Verify the supplied account belongs to that user through a trusted provider lookup, provider webhook record, or previously verified server-side mapping.
- Never allow a user to claim an arbitrary third-party account by submitting an account number and bank name.
- Use a unique provider/account reference and transactional update to prevent conflicting links.
- Do not overwrite an existing verified mapping without a deliberate, audited relinking flow.

### 4. sync-virtual-payments

- Authenticate the caller and derive the user from the verified token.
- Look up the virtual account number from the server-side verified profile/account mapping; do not trust the client-supplied account number.
- Verify each payment independently with Flutterwave using server-held credentials. Confirm provider transaction ID, account mapping, currency, amount, status and reference before crediting.
- Credit once only. Enforce database-level idempotency under concurrent webhook/sync retries; a prior `exists` check without a unique constraint is not sufficient.
- Use one database transaction for the idempotency claim, wallet update and transaction record. Do not permit direct client writes to financial balances.
- Return only a minimal result such as a count of newly credited payments. Never return a production `debug` array or raw provider responses.
- Do not assume the current `add_money_to_wallet` RPC is safe: the owner-provided privilege query showed it executable by `anon` and `authenticated`, and its `transactions.to_address` duplicate check has no confirmed unique constraint.

## Related confirmed compatibility issues

- The live project has one row per currency in `wallets` (`user_id, code, name, balance, is_crypto, address, updated_at`).
- The live `profiles` table already contains virtual-account and KYC fields. Do not replace or rename it.
- The iOS client currently treats the presence of a virtual account number as KYC completion in one local code path. Android and all new server code must not repeat that inference.
- The iOS client uses local state for some add/send/swap operations and writes transaction history separately. Those client-side flows are not a trusted financial ledger and must not be ported as real money movement.
- The compatibility-branch `account-me` function is read-only, validates the caller's JWT, uses the caller JWT for RLS-protected reads, and returns the existing multi-wallet model. It has not been deployed or live-tested.

## Safe implementation order

1. Confirm provider account-creation and account-resolution product contracts, credentials and supported jurisdictions.
2. Confirm the complete live table/index/constraint inventory and existing provider webhook path using read-only inspection.
3. Design any required additive account-mapping/idempotency schema changes and test them in staging.
4. Implement the four functions with authenticated ownership checks and provider-side verification.
5. Test duplicate requests, concurrent payment sync, invalid users, cross-user account-link attempts, provider timeouts and webhook retries.
6. Regression-test existing iOS login, profile, wallet display and virtual-account behaviour.
7. Only after explicit owner approval, plan a backed-up production rollout. This audit itself does not authorize production SQL or deployment.
