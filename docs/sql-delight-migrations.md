# SQLDelight schema and migration policy

1. Store `.sq` schema/query files and numbered `.sqm` migrations in the configured SQLDelight source directory.
2. Check in the generated schema snapshot under version control. Never commit generated source under `build/`.
3. To introduce the first baseline, configure `schemaOutputDirectory`, run the module's `generateSqlDelightSchema` task, inspect the snapshot, and commit it.
4. For every later schema change, add a new forward-only migration; do not edit a released migration or a prior baseline to hide a production upgrade path.
5. Run `verifySqlDelightMigration` locally and in CI. Confirm it is registered and wired to `check` for your installed SQLDelight plugin/version.
6. Add tests for data-preserving upgrades when a migration transforms data, not just schema shape.
7. Test all configured drivers that ship: Android, Native/iOS, and SQLite/JVM. Their runtime behavior and storage guarantees differ.
8. Document recovery and backup/upgrade strategy for user data. Browser persistence is subject to browser storage policies and user clearing; it is not a cloud backup.

The hosted browser app does not use SQLDelight directly; persistence remains behind the sync server API.
