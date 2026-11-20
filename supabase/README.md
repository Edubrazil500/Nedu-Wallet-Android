# Nedu Wallet — Supabase backend

This directory is the replacement backend foundation. The existing NestJS backend under `backend/` is intentionally retained and is not modified by this migration branch.

## Target architecture

- **Supabase Auth** owns passwords, sessions, email confirmation and supported phone OTP flows. The app must never implement password storage itself.
- **Supabase Postgres** stores profiles, NGN wallets, ledger accounts, journals, payment records, virtual accounts and webhook event records.
- **Supabase Edge Functions** are the trusted server boundary for account-specific operations and, in later stages, Flutterwave calls, webhook verification, transfers and withdrawals.
- **Android** remains the existing Kotlin/Jetpack Compose application. No Android files or API wiring are changed in Stages 1–2.

## Stage 1 — project and database foundation

1. Create a Supabase project in the Dashboard.
2. Apply migrations from this repository using the Supabase CLI:
   ```sh
   supabase login
   supabase link --project-ref YOUR_PROJECT_REF
   supabase db push
   ```
3. Confirm the migration in the Supabase SQL editor or by inspecting the resulting tables and policies.
4. Keep the project URL and publishable/anon key in the Android app only when Android integration is scheduled. Keep service-role/secret keys exclusively in Edge Function secrets.
5. Do not enable Flutterwave live operations or transfers at this stage.

The migration attaches application profiles to `auth.users`. On signup, a database trigger creates the profile, one NGN wallet and its user-wallet ledger account in the same database transaction. System ledger accounts are created for Flutterwave clearing, fee revenue and withdrawal payable.

Financial data is protected by RLS. Users can read only their own profile, wallet, wallet ledger entries, payments and virtual accounts. Clients cannot write wallet balances, ledger rows, payments, virtual accounts or webhook records. Ledger entries are append-only, and a deferred constraint trigger rejects journals that do not contain at least two entries with equal debit and credit totals.

## Stage 2 — authentication and account bootstrap

Use Supabase Auth directly from the Android client in a later integration change:
- email/password signup and login;
- email confirmation / OTP according to the Auth project settings;
- phone OTP only after an SMS provider is configured and tested;
- session refresh and logout through the Supabase Auth SDK.

After a successful signup, the database trigger provisions the account records. Call the authenticated `account-me` Edge Function to fetch the profile and wallet summary. The function validates the bearer token with Supabase Auth and uses the caller's JWT so database RLS remains in force.

The current `account-me` response is a migration contract for the future Android adapter; the existing Android app is not yet pointed at it.

### Transaction PIN

A transaction PIN is separate from the Supabase login password and must not be stored as plaintext or as reversible encryption. PIN creation/verification is deliberately not exposed in this foundation migration. Before implementing it, Stage 2 must add a dedicated server-side PIN verifier with a slow password hash, attempt counters, cooldown/lockout, audit events and no PIN value in logs or responses. High-risk actions must require fresh authentication and server-side authorization.

## Edge Functions

Run locally:
```sh
supabase start
supabase functions serve --env-file supabase/.env.local
```

Deploy individually after linking a project:
```sh
supabase functions deploy health
supabase functions deploy account-me
```

Required Edge Function platform variables `SUPABASE_URL` and `SUPABASE_ANON_KEY` are supplied by the Supabase runtime. Do not commit secrets or place the service-role key in Android.

## GitHub Actions deployment (manual)

The repository includes `.github/workflows/supabase-deploy.yml`. It is a manual workflow only; it does not deploy on ordinary pushes or pull requests. Before running it, add these three repository or environment secrets in GitHub **Settings → Secrets and variables → Actions**:

- `SUPABASE_ACCESS_TOKEN`: a Supabase personal access token.
- `SUPABASE_PROJECT_REF`: the project reference shown in the Supabase Dashboard project settings.
- `SUPABASE_DB_PASSWORD`: the database password for that project.

Never put these values in source code, commit them, or send them in chat. Then open **Actions → Deploy Supabase Backend → Run workflow**, type `DEPLOY` in the confirmation field, and run it. The workflow links the selected project, applies committed migrations, deploys the two current Edge Functions, and checks the public health endpoint.

The workflow will fail safely if any secret is missing. Do not run it against a production project containing user data until a reviewed backup and explicit deployment plan exist. The successful local CI tests are not a substitute for reviewing the target project and its existing schema before applying migrations.

## Important migration boundaries

- Do not delete, rename or rewrite `backend/`, Prisma migrations, Render config, or existing Android files while this replacement is being built.
- Do not run this migration against a production project containing user data until a reviewed backup and explicit deployment plan exist.
- Do not assume CAC registration or possession of Flutterwave live credentials alone authorizes custody of customer funds or every payment product. Confirm provider product approval and applicable Nigerian regulatory/partner requirements before enabling wallet funding, transfers or withdrawals.
