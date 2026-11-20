# Android Supabase setup

The Android app is designed to connect to the **same Supabase project** as the existing iOS app.

## Existing project

The project URL supplied from the iOS app is:

`https://yybmhqviescjpplqfaok.supabase.co`

Use this same URL. Do not create a new Supabase project.

## Local Android configuration

In `NeduWallet-Android/local.properties` (the Android project root), add:

```properties
supabase.url=https://yybmhqviescjpplqfaok.supabase.co
supabase.publishableKey=YOUR_EXISTING_PUBLISHABLE_OR_ANON_KEY
```

Copy the **publishable key** (or legacy anon public key) from Supabase Dashboard → Project Settings → API Keys. Do not use the secret key or service-role key. Do not paste any key into a chat or commit it to GitHub.

Then build from the `NeduWallet-Android` directory:

```sh
gradle --no-daemon :app:assembleDebug
```

The APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`

## Optional configured APK through GitHub Actions

A manual workflow named **Build Configured Android APK** is provided separately. To use it, add these GitHub Actions repository secrets:

- `SUPABASE_URL`: `https://yybmhqviescjpplqfaok.supabase.co`
- `SUPABASE_PUBLISHABLE_KEY`: the project's publishable/anon public key (never the service-role key)

Then open GitHub → Actions → Build Configured Android APK → Run workflow and enter `BUILD_CONFIGURED_APK` when prompted. Download the artifact named `nedu-wallet-android-configured`. The workflow only builds the Android APK; it does not deploy database migrations or change Supabase.

## Current integration scope

- Uses Supabase Auth email/password sign-in.
- Uses the caller's Supabase session for PostgREST requests.
- Reads only the signed-in user's existing `profiles` and `wallets` rows.
- Keeps balances as decimal text to avoid floating-point money rounding.
- Does not create or alter database tables, policies, triggers, functions, or data.
- Does not yet enable funding, transfers, swaps, card operations, or purchase actions.

## Verification still required

A successful APK build is not proof of a live connection. Test sign-in using an existing iOS account, then verify that the signed-in user's profile and wallet rows load correctly. Do not use another person's account for testing.

The current iOS Auth signup/profile provisioning path still needs regression testing before Android signup is enabled. Android must use the same Supabase Auth identity and must not create a second account system.

Wallet-changing RPCs currently have overly broad execute grants in the live project. Do not call those RPCs from Android until their privileges and server-side authorization have been safely corrected and the iOS call paths have been checked.

Virtual-account provider functions are not yet implemented in this repository branch. Funding, transfers, swaps and other real-money operations remain disabled until provider contracts, idempotency, webhook verification, staging tests and applicable approvals are complete.
