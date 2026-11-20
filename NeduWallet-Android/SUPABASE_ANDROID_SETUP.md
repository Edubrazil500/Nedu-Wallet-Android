# Android Supabase setup

The Android app is designed to connect to the **same Supabase project** as the existing iOS app.

## Local configuration

Do not put project credentials in Kotlin source or commit them.

In the Android project root, add these entries to your local `local.properties` file:

```properties
supabase.url=https://YOUR_EXISTING_PROJECT_REF.supabase.co
supabase.publishableKey=YOUR_EXISTING_PUBLISHABLE_OR_ANON_KEY
```

Alternatively, pass Gradle properties named `supabaseUrl` and `supabasePublishableKey` in your private build environment.

The publishable/anon key is intended for client applications. **Never use the Supabase service-role key in Android.**

## Current integration scope

- Uses Supabase Auth email/password sign-in.
- Uses the caller's Supabase session for PostgREST requests.
- Reads only the signed-in user's existing `wallets` rows.
- Keeps balances as decimal text to avoid floating-point money rounding.
- Does not create or alter database tables, policies, triggers, functions, or data.
- Does not yet enable funding, transfers, swaps, card operations, or purchase actions.

## Before enabling signup or financial actions

The existing iOS Auth signup flow and profile/wallet provisioning triggers must be inspected first. Android must use the same user identity system and must not create a second account system.

Wallet-changing RPCs currently have overly broad execute grants in the live project. Do not call those RPCs from Android until their privileges and server-side authorization have been safely corrected and the iOS call paths have been checked.
