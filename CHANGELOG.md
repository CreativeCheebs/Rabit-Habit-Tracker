# Changelog

All notable changes are documented here. Follow Keep a Changelog and Semantic Versioning.

## [Unreleased]
### Fixed
- Web app: a change to one field of a row from another device (e.g. a check-in state) no longer wipes the rest of the row, including its comment. Incoming fields are merged by stamp.
- Android: signing in to a different server or account resets the saved sync position so the first sync downloads everything.

### Added
- Initial engineering framework scaffold.
