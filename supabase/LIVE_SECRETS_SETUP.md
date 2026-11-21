# Nedu Wallet — required Supabase Edge Function configuration

Do not paste secret values into chat, source files, GitHub, or the Android app.

In Supabase Dashboard → Project Settings → Edge Functions → Secrets, verify the existing `FLUTTERWAVE_SECRET_KEY` is the owner's Live key and leave its value unchanged. Then set:

- `FLW_ENVIRONMENT` = `live`
- `FLW_LIVE_ENABLED` = `true`
- `FLW_WEBHOOK_SECRET_HASH` = the exact secret hash configured in Flutterwave Dashboard → Settings → Webhooks

In Flutterwave Dashboard, configure the webhook URL:

`https://yybmhqviescjpplqfaok.supabase.co/functions/v1/flutterwave-webhook`

Use the same secret hash for the webhook. Enable retries. The webhook is intentionally configured with Supabase JWT verification disabled because Flutterwave does not send a Supabase access token; it authenticates each request using Flutterwave's `verif-hash` header and then independently verifies the transaction through Flutterwave's API.

Do not enable this until the webhook hash is set in both systems. The Edge Function will reject requests if the hash is absent or mismatched.

These settings enable account resolution, static virtual-account creation and verified deposit reconciliation only. They do not authorize withdrawals, swaps at stale rates, card issuance, or production release.
