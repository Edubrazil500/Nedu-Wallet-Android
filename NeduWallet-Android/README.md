# Nedu Wallet — Android UI starter

Android implementation of the supplied Nedu Wallet screen designs using Kotlin and Jetpack Compose.

## Included screens
- Splash / branding
- Welcome and account entry
- Login form
- Passcode screen
- Wallet dashboard with currency rows and balance visibility toggle
- Card screen with card controls
- Profile screen
- Bottom navigation

## Build an APK locally
Requirements: JDK 17 and Gradle 8.9.

```bash
gradle assembleDebug
```

APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## Build with GitHub
1. Create a new GitHub repository.
2. Upload the contents of this project to the repository root.
3. Push to `main` (or `master`).
4. Open **Actions** → **Build Nedu Wallet APK**.
5. Download the `Nedu-Wallet-debug-apk` artifact after the workflow succeeds.

You can also run the workflow manually from the Actions tab using **Run workflow**.

## Important: prototype only
This repository recreates the visible UI and provides demo navigation. It does **not** connect to a bank, payment processor, blockchain, card issuer, identity-verification provider, or authentication backend. Login and passcode actions are demonstrations only; balances and account details are placeholders. Do not use it to hold funds or collect real passwords, passcodes, recovery phrases, card numbers, or CVVs.

Before a production launch, integrate audited authentication, secure key management, a compliant wallet/payment backend, real card-issuer APIs, transaction signing, fraud controls, privacy policy, and required regulatory approvals. Never store recovery phrases or secrets in plain text. Apple Pay is iOS-specific; an Android release would need a supported Android payment integration (for example, Google Wallet, subject to issuer and eligibility support).
