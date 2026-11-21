# iOS Supabase Compatibility Audit

Date: 2026-11-21
Sources reviewed:
- User-supplied main SwiftUI app source: `Pasted markdown.md` (3,205 lines)
- User-supplied `SupabaseService` source: `Pasted markdown (2).md` (837 lines)
- Owner-supplied live Supabase schema, function, privilege and policy outputs in this project conversation
- Current Android implementation and Supabase Edge Function tree on `feature/functional-account-backend`

Scope: static source and repository review only. No live Supabase objects, balances, credentials, or deployments were changed.

## Confirmed iOS integration

- Supabase project URL: existing project `yybmhqviescjpplqfaok`; Android must continue using this same project.
- Auth uses Supabase Auth REST signup and password-token endpoints with the public anon key.
- Profile and wallet reads use PostgREST with the user's access token.
- Virtual-account and account-resolution workflows call Edge Functions:
  - `create-virtual-account`
  - `resolve-account`
  - `link-existing-virtual-account`
  - `sync-virtual-payments`
- The two supplied Swift files contain no direct calls to the database RPCs `add_money_to_wallet`, `send_money`, `swap_money`, `create_default_wallets`, or `create_default_card`.
- The signup path calls `createProfileRow()`; the Auth database trigger already creates the profile and seven default wallets.
- Send and swap call local methods and then `saveTransaction()`; these are not server-authoritative money operations.

## Complete two-file iOS call-site inventory

| Item searched | Result in the two supplied files |
|---|---|
| `add_money_to_wallet` | No call found |
| `send_money` | No RPC call found; local `sendMoney(...)` method only |
| `swap_money` | No RPC call found; local `swap(...)` method only |
| `create_default_wallets` | No call found; database signup trigger invokes provisioning |
| `create_default_card` | No call found |
| `createProfileRow` | Called after signup; redundant with the confirmed Auth trigger |
| `saveTransaction(` | Called by local send/swap; direct client insert to `transactions` |

This inventory covers the two Xcode source files the owner identified as the complete source set. It does not prove there are no additional callers in any separately maintained target, extension, test target, or older app release. No production grant revocation is authorized by this source review.

## High-priority findings

1. **Client-only money changes are not authoritative.** `addMoney` only increments the in-memory wallet balance. `sendMoney` and `swap` mutate local balances and then call `saveTransaction`, which inserts a transaction row directly. They do not atomically update the Supabase wallet balance or settle a transfer. Android must not port this behavior as a real money operation.
2. **Direct profile creation duplicates the database trigger.** Signup calls `createProfileRow()`, but the supplied database audit confirms an `auth.users` trigger already creates the profile and default wallets. The extra PostgREST insert is unnecessary and likely fails because authenticated INSERT permission on profiles was reported as false.
3. **KYC state is incorrectly inferred from account-number presence.** `fetchProfile()` sets `kycDone = true` whenever a virtual account number exists. Account issuance/linking is not proof of approved KYC. The create-account client path also sets the local KYC flag true as soon as it receives an account number. KYC must be based on server-verified compliance state.
4. **Virtual-account linking trusts client-supplied identity fields unless the Edge Function independently verifies them.** The request sends `user_id`, account number, and bank name. The server must derive the user from the validated JWT and verify account ownership with the provider; never trust the submitted user ID or a client assertion.
5. **Payment sync returns debug data to the app.** The client reads a `debug` array and displays it to the user. Production payment endpoints should return minimal structured results and never expose provider payloads, secrets, internal errors, or debug traces.
6. **Sensitive banking/KYC state is persisted in UserDefaults.** Account details and KYC flag are stored without per-user namespacing. This can show stale details after account switching on a shared device. Clear or isolate these values on sign-out and load account data from the authenticated server profile.
7. **Floating-point amounts are used in the UI.** Swift `Double` is used for balances, amounts and exchange-rate calculations. The server must use PostgreSQL `numeric` and authoritative pricing/rounding; client amounts are display/input only.
8. **The hard-coded rate table is not authoritative.** `rateToUSD` contains fixed rates. Server-side swaps must use validated current rates and record the applied rate/fee under an agreed policy.
9. **The two-file UI contains simulated crypto wallets.** USDT/USDC addresses are hard-coded strings, not derived from user-controlled keys. The displayed 12-word phrases are randomly selected from a small custom word list, not a standards-compliant BIP-39 mnemonic, and are not connected to the displayed addresses. Do not port these as real wallet addresses, recovery phrases, or non-custodial functionality.
10. **The card screen contains demo credentials and fake provisioning state.** Card number, expiry and CVV are fixed display strings; Apple Pay and card controls are local toggles, not issuer-backed actions. Do not display them as real credentials or claim a card is issued.
11. **Local passcode/2FA/biometrics/backup controls are not security enforcement.** The passcode screen accepts any four digits on first use; the passcode, 2FA, biometrics, card freeze and backup flags are local state. The iOS logout action only locks the UI and does not sign out of Supabase.
12. **Bank recipient verification is incomplete.** The NGN lookup has hard-coded demo-account/name fallbacks. Other currencies use routing/sort-code pattern guesses and manually entered recipient names rather than live account verification. Never treat these as verified bank identities.
13. **QR scanning is not implemented.** The scan action shows an alert; there is no camera capture/QR decoder in the supplied source.
14. **Foreign-currency account support is unverified.** The UI requests USD/EUR/GBP/CAD account details through the same function, but the source does not prove provider eligibility, issuance, or persistence for these currencies. Only show fields returned by a verified provider account record.
15. **The client falls back to the anon key as a bearer token for protected Edge Function calls when no access token exists.** Such calls must be rejected by the server; client code must not treat anon-key authorization as a signed-in session.

## Virtual-account Edge Functions missing from the Android branch

The current branch tree contains `account-me` and `profile-update`, but does not contain:
- `supabase/functions/create-virtual-account/index.ts`
- `supabase/functions/resolve-account/index.ts`
- `supabase/functions/link-existing-virtual-account/index.ts`
- `supabase/functions/sync-virtual-payments/index.ts`

The iOS references prove only the client request contracts, not that these server functions are deployed or implemented. The four implementations must not be guessed. They require confirmed provider API contracts, server-held credentials, account ownership rules, idempotency, and staging tests.

## Existing live database findings supplied by the owner

- The live project has a multi-row wallet model: NGN, USD, EUR, GBP, CAD, USDT, USDC.
- The `auth.users` signup trigger creates the profile and default wallets. Keep this behaviour.
- The owner-provided function grant output showed anon/authenticated execution grants on `add_money_to_wallet`, `send_money`, `swap_money`, `create_default_wallets`, and `create_default_card`. The financial functions accept caller-supplied user IDs and have integrity/security gaps described in `SECURE_MONEY_OPERATIONS_PLAN.md`.
- The owner-provided table-grant output showed no authenticated INSERT privilege on `transactions`; therefore the iOS direct transaction insert is not a valid ledger implementation.
- The owner-provided table-grant output also showed TRUNCATE privileges for anon/authenticated/service_role on several financial tables. Treat this as high priority, but verify effective grants and dependencies before any change.
- RLS policies do not replace table privileges. Both must be reviewed together.

## Android implementation status

The current Android branch intentionally provides:
- Supabase Auth signup/sign-in against the existing project.
- Authenticated reads of the signed-in user's profile and seven wallet rows.
- Profile updates through the authenticated `profile-update` Edge Function.
- Read-only wallet presentation with balances retained as decimal strings.
- A card information screen that explicitly states card details and controls are not connected.
- Send/receive/swap/transfer tiles that are not connected to financial execution.

The current branch has successful Android CI and Supabase Backend CI for commit `ba6359467605e200a807656542c1d9d2c25f4347`. This verifies the current code's CI workflows only; it does not verify live Supabase deployment, provider operations, or financial settlement.

## Required secure implementation sequence

1. Preserve the existing Supabase project, schema, signup trigger, and seven-wallet model.
2. Keep the Android app read-only for balances until secure server-side financial contracts exist.
3. Confirm the provider's exact account-creation, account-resolution, webhook and payment-verification APIs and eligibility.
4. Implement authenticated Edge Functions that verify JWTs and derive the user ID from the token, not request JSON.
5. Implement provider-side ownership verification for account linking and provider-side payment verification for funding.
6. Enforce payment idempotency with database uniqueness and atomic wallet/transaction updates.
7. Design send/transfer settlement, recipient credit, pending/failure/reversal states, limits and idempotency before enabling sends.
8. Design swap pricing, quote expiry, fees, numeric rounding, deterministic wallet locking and atomic debit/credit before enabling swaps.
9. Implement real card issuance only through an approved issuer/processor; never generate or display invented card credentials.
10. Implement a real audited crypto key-management/wallet architecture before offering addresses or seed phrases.
11. Implement real Auth logout/session clearing, MFA and secure local authentication separately from display toggles.
12. Test on a separate staging Supabase project with synthetic users and provider sandbox transactions. Regression-test iOS before any production rollout.
13. Obtain explicit approval, a production backup, and a rollback plan before live migrations, grant changes, RPC replacements, or Edge Function deployment.

## Compatibility and rollout decisions

- Preserve the existing seven-wallet model: NGN, USD, EUR, GBP, CAD, USDT, USDC.
- Preserve the existing `profiles`, `wallets`, `transactions`, and `rates` tables. Do not deploy the incompatible foundation migration.
- Keep signup trigger provisioning. `create_default_wallets` should be internal trigger/provisioning functionality, not callable by anonymous or arbitrary authenticated clients.
- Do not revoke RPC/table grants until all actual clients and deployed functions have been reviewed and a staging migration has been tested.
- Do not test funding, transfers, or swaps against real customer balances.

## Credential note

The supplied Supabase value is the anon/public key, not the service-role key. It is designed to be included in client apps, but authorization must be enforced by RLS and server-side validation. Never share or embed the service-role key, Flutterwave secret key, webhook secret, passwords, or BVN in source control or chat.

## Deployment status

This is documentation and repository review only. No live Supabase SQL, grants, functions, secrets, wallet balances, provider configuration, or deployments were changed. PR #6 remains a draft and is not merged.
