# Changelog

All notable changes are documented here. Follow Keep a Changelog and Semantic Versioning.

## [Unreleased]
### Added
- Android: three-page welcome on first start explaining weight per habit (points earned over points possible), with animated burnup chart and Quick Log demos, then the sync choice (connect or use offline).
### Fixed
- Web app: a change to one field of a row from another device (e.g. a check-in state) no longer wipes the rest of the row, including its comment. Incoming fields are merged by stamp.
- Android: signing in to a different server or account resets the saved sync position so the first sync downloads everything.

### Added
- Initial engineering framework scaffold.
