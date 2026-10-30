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
