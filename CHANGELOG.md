# Changelog

All notable changes are documented here. Follow Keep a Changelog and Semantic Versioning.

## [Unreleased]
### Added
- Android: three-page welcome on first start explaining weight per habit (points earned over points possible), with animated burnup chart and Quick Log demos, then the sync choice (connect or use offline).
### Security
- Sync server: failed sign-ins are rate limited (per address and per username, HTTP 429 with a clear message); blocked attempts skip the costly password check.
- Web app: no inline event handlers any more, so the CSP allows scripts only from the server's own files.
- Sync server: refuses rows whose id, reference fields or field names fall outside a plain charset, so synced data cannot inject script into the browser app. Sends CSP, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, and `Cache-Control: no-store` on API responses.
- Web app: ids used in inline handlers and option values are validated before rendering; incoming rows with unsafe ids or field names are ignored.
- Android: the sign-in box warns when the address is plain `http://` and not on a home/local network, since that traffic would cross the internet unencrypted. Lockout messages from the server are shown as written.
### Fixed
- Web app: a change to one field of a row from another device (e.g. a check-in state) no longer wipes the rest of the row, including its comment. Incoming fields are merged by stamp. A delete from another device keeps a tombstone, so later stale fields cannot bring back a half row.
- Android: signing in to a different server or account now asks first, then replaces this phone's synced data and restarts sync from zero, so one account's habits are never uploaded into another. Connecting from offline mode still keeps and uploads the phone's own data.
- Android: a malformed sign-in answer is reported instead of crashing, and cancelling a sign-in is no longer swallowed.
- Android: Back on the welcome pages steps to the previous page; dismissing sign-in returns to the welcome instead of leaving the sync choice unset. The welcome demos are described once for TalkBack and no longer clip at large text sizes.
- Initial engineering framework scaffold.
