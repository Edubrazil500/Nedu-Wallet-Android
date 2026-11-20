# Nedu Wallet API

NestJS, PostgreSQL and Prisma backend for the Nedu Wallet Android application.

## Safety status
This is the initial backend foundation, not approval to accept or custody customer funds. Flutterwave live calls are disabled by default (FLW_LIVE_ENABLED=false), and bank transfers are deliberately unavailable until withdrawal ledger reservations and risk controls are completed. Do not enable live payments until legal/regulatory review, Flutterwave production approval, security review, reconciliation testing and incident procedures are signed off.

## Local setup
1. Install Node.js 20+ and PostgreSQL 15+.
2. Copy .env.example to .env and set a local database URL and random JWT secret.
3. Run npm install.
4. Run npm run prisma:generate.
5. Run npm run prisma:dev -- --name init.
6. Run npm run start:dev.

## Initial endpoints
- GET /api/v1/health
- POST /api/v1/auth/register
- POST /api/v1/auth/login
- GET /api/v1/wallet/me (Bearer token)
- POST /api/v1/payments/deposits/checkout (Bearer token; provider calls require explicit enablement)
- POST /api/v1/payments/virtual-accounts/dynamic (Bearer token; amount-specific NGN account)
- POST /api/v1/webhooks/flutterwave

## Render deployment
The repository-level render.yaml defines a managed PostgreSQL database and Node web service. Create a Blueprint from this repository. Set FLW_SECRET_KEY, FLW_WEBHOOK_SECRET and CORS_ORIGINS in the Render dashboard. Keep FLW_LIVE_ENABLED=false while validating the integration.

## Important notes
- Store password hashes only; never store plaintext passwords.
- Never put Flutterwave secret keys in Android, source control, build variables or client requests.
- A webhook is not sufficient to credit a wallet. The handler re-verifies the transaction with Flutterwave and compares reference, currency and amount.
- Ledger journal rows are append-only by application convention. Do not expose update/delete operations for ledger entries.
- Wallet balance is derived from ledger entries, not a client-supplied balance.
- Dynamic NGN virtual accounts are implemented. Static virtual accounts require BVN/NIN handling and a separate privacy/KYC design; they are intentionally not exposed yet.
- Transfers/withdrawals remain disabled until a complete reserve/settle/release workflow, beneficiary verification, limits, fraud screening, reconciliation and regulatory sign-off are in place.

Flutterwave documentation: https://developer.flutterwave.com/docs/virtual-accounts/ngn and https://developer.flutterwave.com/docs/webhooks
