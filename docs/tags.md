# Tag directory and rules

NReader modifications by TheSkyC (2026); see NOTICE.

The tag directory uses the site's positive tag IDs. Canonical names and counts
can change without changing the meaning of a rule. The directory feed must
agree with the known type of an existing ID. An omitted ID stays available, so
gallery links and rules continue to resolve. Search-only tags with no site ID
do not acquire a directory identity.

Every APK and app bundle includes the catalog and revision from its source
checkout. A background job can install that catalog without Internet access.
A bundled revision can replace an older bundled revision; it cannot replace an
installed remote catalog. Upstream and the repository's bundled dataset have
independent version histories. An open tag-management screen refreshes when
the installation commits. After a successful backup restore, the bundled job
runs again and reinstalls the seed if restore cleared the catalog revisions.

Local rules retain the default, avoided and accepted states. The active
account's blacklist remains subject to its existing authentication and revision
checks. A directory import updates only canonical names and counts, and adds
previously unseen IDs with default rule and blacklist values. Editing a rule
does not copy a potentially stale count from the editor back into the directory.

## Directory refresh

The upstream feed currently exposes an integer revision and a JSON array of
`[id, canonicalName, count, typeId]` rows. The app downloads the payload to a
private temporary file before starting the database transaction. It checks the
revision again after downloading because the upstream paths are mutable. The
legacy protocol has no digest that binds the revision to the payload.

The legacy feed is limited to 16 MiB and 250,000 rows. It must contain at least
50,000 rows covering all seven known types. A canonical name must fit in the
site's quoted query syntax. Invalid fields, duplicate IDs, type conflicts,
cancellation and database errors reject the import. Rows and the
applied revision are committed together. Equal or older revisions do not
replace the directory. A restore that replaces the tag rows invalidates the
applied revision in the same transaction.

Remote checks share one WorkManager job. A successful check is cached
for seven days. Failures retry a bounded number of times. Existing directory
rows and rules are usable without network access.
