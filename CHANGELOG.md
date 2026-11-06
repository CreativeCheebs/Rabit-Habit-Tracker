# Changelog

All notable changes are documented here. Follow Keep a Changelog and Semantic Versioning.

## [Unreleased]
### Added
- Android: three-page welcome on first start explaining weight per habit (points earned over points possible), with animated burnup chart and Quick Log demos, then the sync choice (connect or use offline).
### Security
- Sync server: refuses rows whose id, reference fields or field names fall outside a plain charset, so synced data cannot inject script into the browser app. Sends CSP, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, and `Cache-Control: no-store` on API responses.
- Web app: ids used in inline handlers and option values are validated before rendering; incoming rows with unsafe ids or field names are ignored.
### Fixed
- Web app: a change to one field of a row from another device (e.g. a check-in state) no longer wipes the rest of the row, including its comment. Incoming fields are merged by stamp. A delete from another device keeps a tombstone, so later stale fields cannot bring back a half row.
- Android: signing in to a different server or account resets the saved sync position so the first sync downloads everything.
- Initial engineering framework scaffold.
