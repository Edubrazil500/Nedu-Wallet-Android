# iOS V13 Feature and Security Audit

## Scope
Reviewed the V13 SwiftUI source supplied by the user, together with the previously reviewed `SupabaseService.swift` and the live Supabase schema/function/grant outputs supplied in this project conversation.

This is a source-code review only. It does not establish that any provider integration is live, that any financial feature has settled funds, or that the current production database is safe.

## Critical findings: do not port these as real functionality

### 1. Crypto wallet addresses are hard-coded demo strings
`CryptoStore.addressFor` returns fixed USDT/USDC addresses for TRC20, ERC20, BEP20 and Binance networks. These are not generated from a private key, are not tied to the signed-in user, and are not shown to be controlled by the user. Some values are syntactically suspicious. The displayed addresses must never be used to receive real assets.

### 2. Seed phrases are not valid wallet recovery phrases
`generatePhrase()` randomly chooses 12 words from a 47-word custom list. This is not BIP-39, has no entropy/checksum implementation, and is not used to derive the displayed addresses. The app labels the resulting words as USDT/USDC phrases, which could cause irreversible loss if users trust them. Do not port phrase generation, phrase backup, or "non-custodial" claims. A real wallet requires audited key generation, standards-compliant mnemonic derivation, secure key storage, recovery testing, and a carefully designed custody model.

### 3. Send/transfer UI reports success without settlement
The SendView and TransferView call the existing local `store.sendMoney(...)` method and immediately display "Sent". The previously reviewed SupabaseService implementation only mutates local balances and attempts a transaction insert; it does not execute a secure transfer/settlement flow. Bank and blockchain transfers are not implemented by these UI actions. Android must not display success or mark transactions completed until a trusted backend/provider confirms settlement.

### 4. Swap UI uses a client-side estimate and local mutation
SwapView computes estimates from client-visible rates and then calls local `store.swap(...)`. The previously reviewed service mutates local balances and writes a transaction record, rather than invoking a safe atomic server-side swap. Treat the displayed rate, fee, preview, and result as demo-only. Production swaps need server-calculated quotes, expiry, positive amount/currency validation, numeric precision, deterministic wallet locking, atomic debit/credit, recorded rate/fee, and idempotency.

### 5. Bank lookup includes hard-coded demo identities
`lookupNGNName` returns names for two fixed account numbers. This is not bank verification and can disclose/associate a fabricated identity. Remove this fallback from any production build. A recipient name must only be displayed when returned by a trusted bank-resolution provider for the exact bank/account pair.

### 6. Card details and Apple Pay are simulated
CardView displays a fixed card number, expiry and CVV, and lets the user toggle an in-memory "Added to Apple Pay" state. These are not issued card credentials or an Apple Pay provisioning integration. Card controls and spend-wallet selection are also local state. Do not present this as a real card or claim Apple Pay is enabled. Real cards require an issuer/processor integration, tokenized credential retrieval, PCI-aware handling, and issuer-backed card controls/provisioning.

### 7. Security controls are local UI state, not security enforcement
Passcode, 2FA, biometrics, iCloud backup, card freeze and spending toggles are held in local ObservableObjects and are not persisted or enforced by the backend. AuthView accepts any four or more digits on first use. Profile logout only locks the local screen; it does not sign out of Supabase or revoke/clear the authenticated session. These controls must not be represented as enabled security features until implemented and tested properly.

### 8. KYC and virtual account display need server authority
The UI submits BVN to Edge Functions for NGN and other fiat account requests and offers linking of an existing NGN account. The UI's `kycDone` and account fields must be sourced from authenticated server state. Previously reviewed SupabaseService logic treated the presence of a virtual account number as evidence of KYC verification; that is unsafe. Account linking must prove provider-side ownership and must not trust a user-supplied account number alone. Never persist BVN in client preferences, logs, analytics or transaction metadata.

### 9. "Real virtual accounts for all fiat" is an unverified product claim
The UI offers USD, EUR, GBP and CAD account fields, but the source supplied here does not prove that a provider actually issues these accounts for this user, country, KYC tier or currency. Do not fabricate account details or infer support from an empty field. Display each currency's account only after a provider has issued it and the backend has verified and stored the provider response. Provider coverage, fees, payout rails and eligibility must be confirmed before launch.

### 10. Payment sync must be provider-verified and idempotent
The app calls `syncVirtualPayments` and expects a credited result. A client-triggered sync must not itself be trusted to assert a payment. The Edge Function must authenticate the caller, query/verify the payment directly with the provider, validate beneficiary/account/currency/amount/status, and credit the correct wallet atomically with a database-enforced unique provider transaction reference. Client-supplied user IDs and transaction statuses are not authoritative. Do not return raw provider debug payloads to the app.

### 11. QR scanner is not implemented
The Send and Transfer screens show an alert that says to point the camera at a QR code, but there is no camera capture/QR decoding implementation in this source. Do not claim scanning works.

### 12. Profile photo and phrase backup are local-only
Profile photo is stored in un-namespaced UserDefaults. Phrase data is only held in memory in this source and is not backed up to iCloud. Local preferences need per-user isolation and secure storage where appropriate; seed phrases must not be stored in ordinary preferences.

## Supabase compatibility and implementation notes

- Keep the existing Supabase project and the existing seven-wallet model: NGN, USD, EUR, GBP, CAD, USDT and USDC.
- The existing signup trigger `handle_new_auth_user()` creates the profile and default wallets. The app's separate direct profile insert is redundant and conflicts with the supplied table-grant output.
- The supplied database audit showed client execution grants for `add_money_to_wallet`, `send_money`, `swap_money`, `create_default_wallets` and `create_default_card`. These functions trust caller-supplied user IDs or have other financial integrity gaps. Keep them out of client-callable paths until redesigned and reviewed.
- The supplied table-grant output showed no authenticated INSERT privilege on transactions, so direct client transaction inserts are not a valid ledger implementation.
- The supplied table grants also showed TRUNCATE privileges for anon/authenticated/service_role on several financial tables. Treat this as a high-priority privilege audit item; do not revoke blindly before reviewing dependencies and effective grants.
- Do not deploy the incompatible foundation migration previously identified in `EXISTING_PROJECT_COMPATIBILITY.md`. Preserve the live schema and use reviewed additive changes only.

## Android parity classification

### Safe to implement as presentation/read-only features
- Nedu red visual branding and navigation.
- Sign-up/sign-in against the existing Supabase Auth project.
- Authenticated profile and wallet reads subject to the existing RLS policies.
- Wallet balance visibility toggle and currency labels.
- Transaction history only when read from an authorized backend source.
- QR code rendering for a verified address/account detail (rendering only).

### Requires secure backend/provider work before being advertised as live
- KYC status and BVN submission.
- Existing virtual account linking.
- NGN/USD/EUR/GBP/CAD virtual account issuance.
- Provider payment sync and wallet credit.
- Bank account resolution.
- Internal send, bank transfer, crypto withdrawal and swap.
- Cards, card controls and Apple Pay.
- Real crypto wallet creation, address derivation and recovery.
- 2FA, biometric/passcode enforcement, secure backup and real logout.
- QR camera scanning.

## Required next audit input
Before changing any live database grants or financial functions, inspect the complete iOS project call sites for:
- `add_money_to_wallet`
- `send_money`
- `swap_money`
- `create_default_wallets`
- `create_default_card`
- `createProfileRow`
- `saveTransaction(`

Also inspect the deployed/source implementations of these Edge Functions:
- `create-virtual-account`
- `resolve-account`
- `link-existing-virtual-account`
- `sync-virtual-payments`
- `account-me`
- `profile-update`

## Safety / deployment status
This audit file is documentation only. No Supabase SQL, grants, functions, secrets, wallet balances, provider configuration, or deployments were changed. Do not merge PR #6 or deploy financial changes without explicit approval, backup, and staging verification.
