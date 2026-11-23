# Reviewing tag translation contributions locally

Use this workflow to exchange proposals and produce a candidate locale pack.
It works with local files and does not contact a server. A proposal, a review
report, and an installable pack are three separate artifacts. The process does
not establish contributor identity, permission to reuse outside translations,
or authority to publish.

1. Start from the checked-in tag catalog and the current pack for the locale.
   Omit `--base` only for a new locale. Draft a proposal for the affected tag
   IDs; repeat `--id` for additional tags:

   ```powershell
   python -m scripts.review_tag_translations draft --locale zh-Hans --id 8378 `
     --base .gradle/zh-Hans-current.json --output .gradle/proposal.json
   ```

2. Edit each change's `after` value to an object with `name` and optional
   `aliases`. Set it to `null` to remove an existing translation. Provide
   optional short context in `note`. Do not edit `id`, `canonicalName`,
   `type`, or `before`; these describe what the contributor reviewed. Send
   the proposal for review without credentials, account information, or user
   rules.

3. As maintainer, generate and retain your own report and the base pack used
   for it. Use the current pack from your release files and the next revision.
   The app does not export installed packs. Keep the original release pack:
   installed aliases do not retain their input order, while proposal comparisons
   and normalized base digests include that order. Reconstructing a pack from
   installed rows may therefore produce a different review base even if the
   visible translations and aliases are the same.

   ```powershell
   python -m scripts.review_tag_translations review .gradle/proposal.json `
     --base .gradle/zh-Hans-current.json --revision 2 --output .gradle/review.json
   ```

   Review all the changes together. The report includes canonical names and
   types, before and after values, contributor notes, and SHA-256 digests of the
   normalized base and resulting pack. JSON escapes invisible formatting
   characters so the review file cannot use them to disguise the surrounding
   structure. If a tag changed
   name or type, or its current translation no longer matches `before`, draft
   it again against the current data. Count changes and edits to other tags do
   not invalidate a proposal. If they change the resulting pack or its revision
   during review, regenerate the report before accepting it.

4. After reviewing the report, use it with the same proposal, base pack, and
   revision to produce a candidate:

   ```powershell
   python -m scripts.review_tag_translations accept .gradle/proposal.json `
     --base .gradle/zh-Hans-current.json --revision 2 --review .gradle/review.json `
     --output .gradle/zh-Hans-candidate.json
   ```

   The tool recomputes the report and candidate and writes the candidate
   atomically only if the report matches. The candidate retains unaffected
   translations and can be installed using Settings → Tag languages. For a
   different canonical snapshot, pass `--catalog` to all three commands.

5. When staging a reviewed candidate, pass its report to the release tool:

   ```powershell
   python -m scripts.build_tag_translation_release .gradle/zh-Hans-candidate.json `
     --review .gradle/review.json --catalog data/tags.json --output .gradle/translation-feed
   ```

   The tool checks the candidate digest and compares the report's normalized
   base digest and revision with the current local release. If they differ,
   review the proposal again against the current pack and accept a new candidate.
   For a batch, repeat `--review` once per candidate locale. An identical
   already-published candidate can be retried. Reports use schema version 2;
   regenerate older reports before acceptance and staging. Release preparation
   without `--review` remains available for packs built directly by the authoring
   tool and does not perform this base check. Use a single publisher, and verify
   the hosted manifest separately: this check only covers the local release. It
   does not recompute the report from the proposal or authenticate its author;
   retain and use the report you reviewed and accepted.

Each proposal uses `schemaVersion: 1`, covers one locale, and contains 1 to
1,000 changes with unique positive tag IDs. It cannot remove the last entry
from a locale or change a tag's canonical identity. The app pack format has
its own separate schema version.

If a target catalog omits IDs present in the current pack, pass the target
snapshot as `--catalog` and the earlier snapshot that covers the base pack as
`--base-catalog` to draft, review, and accept. For an omitted ID, the proposal
shows its name and type from the earlier snapshot; its `after` must be `null`.
The candidate must remove every ID absent from the target catalog, and both
catalogs must agree on the type of every retained base ID. All removals must
fit in one proposal alongside any additions or edits: the intermediate candidate
cannot contain unknown IDs. For a larger correction, prepare a complete pack
with the authoring tool and apply the maintainer's review process separately.

Keep authoring inputs and generated outputs in separate files. The pack builder
and proposal commands reject an output path that resolves to an input path.

For public contributions, the maintainer still needs to establish licensing
and attribution requirements, an authorized reviewer and publisher process,
and rules for moderating and correcting published translations. Anyone with the
same inputs can reproduce the report. A match binds the inputs to the candidate
but does not establish maintainer approval.
