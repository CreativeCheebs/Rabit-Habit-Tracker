# Security baseline

- Keep repository permissions minimal and workflows read-only by default. Protect default-branch changes and pin third-party GitHub Actions to full commit SHAs for a hardened production setup.
- Gitleaks checks repository history/content for common secret patterns. It is not a guarantee; revoke any exposed credential and rotate it.
- Dependency Review checks pull request dependency changes. Configure Dependabot and review upgrades against Kotlin/Compose/AGP compatibility.
- Dependency review is enabled for pull requests. Add OSV-Scanner after Gradle dependency locking and committed lockfiles are configured; the current repo does not yet provide resolved dependency lock state.
- For Android, protect signing keys and use Android platform security guidance. For the hosted browser app, serve over HTTPS, set CSP and security headers at the host, avoid secrets in client bundles, and review third-party JavaScript assets.
- A client-only scaffold has no backend API to scan. When a backend is added, document its OpenAPI contract and add authenticated/unauthenticated API tests plus a passive OWASP ZAP baseline against a disposable test environment; never point active security scans at production without authorization.
- Do not run untrusted PR code with repository secrets. Avoid granting write permissions to pull request workflows.
- Static analysis does not replace threat modeling, code review, runtime checks, or penetration testing for sensitive features.

- The sync server sets a CSP that allows scripts only from its own files (the web app uses event listeners, no inline handlers). Styles still allow inline attributes. It also sets `X-Content-Type-Options`, `X-Frame-Options` and `Referrer-Policy`. HSTS and HTTPS still belong to the reverse proxy.
- Sync rows are validated server-side (`isSafeSyncRow`): ids and `*_uid` fields use `[A-Za-z0-9._:@#+~-]` up to 200 characters, field names `_?[a-z][a-z_]*`. Clients must generate ids within that set.
- Sign-in failures are limited per client address (10 per 10 minutes) and per username (6 per 10 minutes); blocked requests get HTTP 429 and skip password hashing. Behind a reverse proxy all clients share the proxy's address, so the per-address limit then acts as a global one.
