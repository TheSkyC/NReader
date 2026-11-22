# Tag format versioning

NReader keeps three kinds of version separate. The SQLite database version
describes the local storage schema. A feed or pack `schemaVersion` describes
the wire shape of that one format. A catalog or locale `revision` identifies a
new complete snapshot within one source and identity. None of these values can
be used in place of another.

## Current contracts

- The application database is schema version 24. `DatabaseHelper.onUpgrade`
  applies upgrades in order; downgrade is deliberately unsupported. A storage
  change requires a new database version, an upgrade path from every supported
  prior version, and rollback tests for an injected migration failure.
- The catalog payload and catalog manifest use schema version 1. Catalog
  revisions are monotonic within `bundled`, `legacy-upstream`, or
  `nreader-catalog`; a corrected snapshot uses a higher revision. The payload
  shape and site ID meaning are not reinterpreted by a revision change.
- Translation packs and translation manifests use schema version 1. A pack is
  a complete snapshot for one locale. A locale revision replaces that locale's
  previous snapshot only after validation and an atomic database transaction.
  Unknown schema versions and fields are rejected before installation.

## Change procedure

When a format shape or field meaning must change:

1. Reserve the next `schemaVersion`; do not add new required semantics to v1.
2. Define the accepted fields, limits, canonical encoding, and rejection rules
   in the format document and both host and Android parsers.
3. Update builders, release verifiers, deterministic fixtures, and malformed
   input tests together. Keep immutable payload names bound to their exact
   bytes.
4. If the Android storage shape changes, bump the database version and add an
   ordered migration. Recheck revisions and source identity inside the same
   transaction that replaces rows.
5. Verify process-stop, cancellation, storage-limit, and process-death paths;
   an unsupported or partially migrated input must leave the last good data
   usable.
6. Update the distribution and contribution contracts before enabling a new
   production format. A public Feed URL must remain disabled until its trust
   policy supports the new contract.

Readers may continue to accept an older schema only when its semantics remain
unambiguous and the compatibility behavior is documented. Otherwise, ship a
reader migration in an app release and keep the old data untouched until the
new representation has been validated. Never silently reinterpret an existing
revision or change the meaning of a saved site tag ID.
