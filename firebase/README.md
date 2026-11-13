# Casha cloud ledger

This folder contains Firestore security rules, index configuration, and emulator tests. No live project configuration or deployment credentials are included.

## Local rules tests

With Node.js 24.12+ and JDK 21+ installed:

```sh
npm ci
npm test
```

The test command starts a local Firestore emulator with the synthetic `demo-casha` project. Tests cover owner access, cross-account denial, ledger validation, payments, reversals, and revision behavior. They do not deploy anything.

## Data model

Loan documents live at `users/{uid}/loans/{loanId}`. Access is restricted to the signed-in owner; undeclared paths are denied. Each write represents a create, edit, payment, or reversal, with a monotonic revision and matching financial deltas. Histories are append-only. Limits include 120 installments and 500 lifetime payments per loan. Review `firestore.rules` for the exact constraints.

Cloud sync is opt-in and account-scoped. Device imports require explicit confirmation. The client maintains durable pending commands, detects conflicting cloud revisions, and asks the user to resolve conflicts rather than overwriting them silently. Budget and receipt records remain device-local.

## Your own Firebase project

1. Create or select your own Firebase project; register the Android package `com.example.casha`.
2. Enable Google sign-in and create a Firestore database. Register the local signing certificate used for your build.
3. Download the generated configuration into the ignored `app/google-services.json`.
4. Inspect your database and existing rules before deployment. Run the local tests first. Use the Firebase CLI with an explicit project selection when deliberately deploying.

Do not apply these rules blindly to a shared database. Existing broad allow rules can bypass narrower restrictions, and a deployment may affect unrelated applications. Nothing in this repository configures a production backend automatically.

For Android integration testing, use only a dedicated emulator and local Auth/Firestore emulators; see [development notes](../docs/DEVELOPMENT.md).
