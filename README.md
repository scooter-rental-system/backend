# Backend Repository

Deployed link: http://65.0.5.214

## Demo accounts

**Admin**
- Email: admin@scooterrentkandy.lk
- Password: Admin@12345

**Customer**
- Email: demo@example.com
- Password: Demo@12345

These credentials are for local/demo use only and must not be used for production accounts.

The accounts are created by the development seeder (`DataSeeder`) on an empty database. It never runs under the
`prod` profile, and it creates no bookings, payments or other history.

## Running tests

From `spring/`:

| Shell | Command |
|---|---|
| Git Bash, macOS, Linux | `./gradlew test` |
| PowerShell | `.\gradlew test` |
| Command Prompt | `gradlew.bat test` |

Integration tests run against a temporary embedded PostgreSQL 16.15 (no Docker, no credentials). It is started
on a free localhost port with a temporary data directory, which is deleted when the tests finish. The PostgreSQL
binaries are extracted once and stay cached in the system temp folder (`%TEMP%\embedded-pg` on Windows, about
100 MB) so later runs start faster; that folder can be deleted at any time and is recreated on the next run.
Tests never use a real database:

- `src/test/resources/config/application.properties` activates the `test` profile and points the datasource at
  an unreachable placeholder, so a misconfigured test fails instead of reaching a real database.
- Database tests extend `PostgresIntegrationTest`, which supplies the embedded database.
- `TestDatabaseGuard` rejects any other database URL (including `scooter_rent_db`) before a connection is made.
- The Gradle `test` task removes `SPRING_DATASOURCE*`, `SPRING_FLYWAY*`, `PG*` and `DATABASE_URL` from the test
  environment.

Run tests through Gradle (or the IDE's Gradle runner) so these protections apply.

## Local Stripe test mode

The checkout uses Stripe's hosted Payment Element. To enable real Stripe test-mode payments locally, configure the
Spring backend with Stripe's **test-mode** keys; do not put the secret key in the frontend or commit it to a file.
All three settings are required:

- `PAYMENTS_PROVIDER=stripe`
- `STRIPE_SECRET_KEY=sk_test_...`
- `STRIPE_PUBLISHABLE_KEY=pk_test_...`
- `STRIPE_WEBHOOK_SECRET=whsec_...`

For local webhook delivery, start Stripe CLI in one terminal and forward test events to the backend:

```powershell
stripe listen --forward-to localhost:8080/api/webhooks/stripe
```

Copy the `whsec_...` value printed by the CLI into `STRIPE_WEBHOOK_SECRET`. In the terminal where the Spring backend
will run, set the environment values and start it from `spring/`:

```powershell
$env:PAYMENTS_PROVIDER = "stripe"
$env:STRIPE_SECRET_KEY = "sk_test_..."
$env:STRIPE_PUBLISHABLE_KEY = "pk_test_..."
$env:STRIPE_WEBHOOK_SECRET = "whsec_..."
.\gradlew bootRun
```

If you run Spring Boot from IntelliJ, add the same four variables under **Run → Edit Configurations → Environment
variables** for the Spring Boot run configuration, then stop and restart the backend. Editing `application.properties`
or setting variables in a different terminal does not update an already-running IntelliJ process.

Use the test cards listed in Stripe's official testing documentation. A successful checkout is recorded only after the
backend receives and verifies Stripe's webhook. The checkout labels test mode, and Stripe test transactions do not move
real money. Existing accounts without a NIC can add their valid Sri Lankan NIC once under Profile before checkout.
For a deployed test site, configure the same test keys as backend environment variables and create a
Stripe webhook endpoint pointing to the publicly reachable backend's `/api/webhooks/stripe` URL; do not use the local
Stripe CLI forwarding URL for a remote backend.
