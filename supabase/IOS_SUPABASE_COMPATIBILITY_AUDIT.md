# iOS Supabase Compatibility Audit

Date: 2026-11-20
Source reviewed: user-supplied `SupabaseService.swift`
Scope: static code review only. No live Supabase objects, balances, credentials, or deployments were changed.

## Confirmed iOS integration

- Supabase project URL: existing project `yybmhqviescjpplqfaok`; Android should continue using this same project.
- Auth uses Supabase Auth REST signup and password-token endpoints with the public anon key.
- Profile and wallet reads use PostgREST with the user's access token.
- Virtual-account and account-resolution workflows call Edge Functions:
  - `create-virtual-account`
  - `resolve-account`
  - `link-existing-virtual-account`
  - `sync-virtual-payments`
- The supplied file does not call the database RPCs `add_money_to_wallet`, `send_money`, `swap_money`, `create_default_wallets`, or `create_default_card` directly.

## High-priority findings

1. **Client-only money changes are not authoritative.** `addMoney` only increments the in-memory wallet balance. `sendMoney` and `swap` mutate local balances and then call `saveTransaction`, which inserts a transaction row directly. They do not atomically update the Supabase wallet balance or settle a transfer. Android must not port this behavior as a real money operation.
2. **Direct profile creation duplicates the database trigger.** Signup calls `createProfileRow()`, but the supplied database audit confirms an `auth.users` trigger already creates the profile and default wallets. The extra PostgREST insert is unnecessary and likely fails under the observed missing authenticated INSERT grant. Remove/disable this client insert only after confirming the signup UI does not rely on its response.
3. **KYC state is incorrectly inferred from account-number presence.** `fetchProfile()` sets `kycDone = true` whenever a virtual account number exists. A virtual account is not proof that KYC is approved. KYC must be based on a server-verified status.
4. **Virtual-account linking trusts client-supplied identity fields unless the Edge Function independently verifies them.** The request sends `user_id`, account number, and bank name. The server must derive the user from the validated JWT and verify account ownership with the provider; never trust the submitted user ID or a client assertion.
5. **Payment sync returns debug data to the app.** The client reads a `debug` array and displays it to the user. Production payment endpoints should return minimal structured results and never expose provider payloads, secrets, internal errors, or debug traces.
6. **Sensitive banking/KYC state is persisted in UserDefaults.** Account details and KYC flag are stored without per-user namespacing. This can show stale details after account switching on a shared device. Clear or isolate these values on sign-out and load account data from the authenticated server profile.
7. **Floating-point amounts are used in the UI.** Swift `Double` is used for balances, amounts and exchange-rate calculations. The server must use PostgreSQL `numeric` and authoritative pricing/rounding; client amounts are display/input only.
8. **The hard-coded rate table is not authoritative.** `rateToUSD` contains fixed rates. Server-side swaps must use validated current rates and record the applied rate/fee under an agreed policy.

## Compatibility and rollout decisions

- Preserve the existing seven-wallet model: NGN, USD, EUR, GBP, CAD, USDT, USDC.
- Preserve the existing `profiles`, `wallets`, `transactions`, and `rates` tables. Do not deploy the incompatible foundation migration.
- Keep signup trigger provisioning. Do not expose `create_default_wallets` to anon/authenticated callers.
- The supplied file does not prove whether other iOS files call the exposed financial RPCs. Search the entire iOS project for each RPC name before revoking grants or replacing functions.
- Before changing table grants, review all iOS PostgREST calls and confirm whether profile edits use direct PostgREST or the existing `profile-update` Edge Function.
- Secure financial operations behind authenticated Edge Functions or carefully authenticated database functions. Derive user identity from the verified JWT, validate positive amounts/currency codes, use atomic transactions/row locks, and enforce idempotency for provider payment credits.
- Do not test funding, transfers, or swaps against real customer balances. Use staging and test provider transactions.

## Immediate next audit needed

Search the entire iOS Xcode project (not just this file) for:
- `add_money_to_wallet`
- `send_money`
- `swap_money`
- `create_default_wallets`
- `create_default_card`
- `createProfileRow`
- `saveTransaction(`

Also provide the implementations of the four Edge Functions listed above, if they exist. Their names are referenced by the iOS client, but their server-side code was not included in the supplied source.

## Credential note

The supplied value is the Supabase anon/public key, not the service-role key. It is designed to be included in client apps, but authorization must be enforced by RLS and server-side validation. Never share or embed the service-role key, Flutterwave secret key, or BVN in source control or chat.
