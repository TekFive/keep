---
name: package-meta
description: Write or update _package.md in each source code package. Use for package documentation requests and for keeping an existing package guide accurate after code changes. Root project setup and tooling operations belong in their own README.md files.
paths: ["**/*.ts", "**/*.tsx", "**/*.kt"]
---

# Package documentation

Documentation about the code found at a source package location should be maintained in a `_package.md` within the source
package directory. The purpose of these metafiles is to make it easier for a user to come up to speed with the code within the package and
to limit the amount of commenting that is required within the source code files. These files should be written using 
the `plain-writing`. 

* An agent should create this file anytime they are updating code in a package where it doesn't already exists (exceptions
to this rule are listed at the end of this file).
* Agents should update this file when they are modifying code within a package to remain consistent.

## Introduction
The introduction must use plain, nontechnical language. Explain what the package
does and why it matters without requiring knowledge of the implementation.

## Subpackages

A table that contains each subpackage to this package with a short description of what each subpackage is responsible for.

Example:
```markdown
| Package | Responsibility |
| --- | --- |
| [`storage`](storage/_package.md) | Fetching, checking, and storing a version's files as copies, and making PDF renditions. |
| [`shred`](shred/_package.md) | Breaking a version into pieces, the shred job, and anchors over the pieces. |
```
## Code
A table that contains non-trivial classes, objects, and types.

Example:

```markdown
| Type (File) | Responsibility |
| --- | --- |
| `DocumentStructure` (Document.kt) | The audited base of a document: name, description, access. |
| `DocumentVersion` (DocumentVersion.kt) | The base of a version: its source URL, what the source claimed about the file, and its copies. |
| `DocumentCopy` (DocumentCopy.kt), [`DocumentCopyKind`](DocumentCopyKind.kt) | A file Hilda stored: an original or a PDF rendition, with its verified hash and size. |
| `DocumentPointer` (DocumentPointer.kt) | Parses a document URL: `https://`, `graph://tenant/drive/item`, or `storage:<blob-id>`. |
| `DocumentOwnerKind` (DocumentOwnerKind.kt), [`DocumentVersionReference`](DocumentVersionReference.kt) | Which kind a document belongs to, its tables, and a version of either kind. |
| `DocumentAnchor`] DocumentAnchor.kt), [`AnchorResolver`](AnchorResolver.kt), [`DocumentRegion`](DocumentRegion.kt) | A place in a version, down to a span of one shred node, and how one is checked and moved. |
| `DocumentMediaType` (DocumentMediaType.kt), [`DocumentAccess`](DocumentAccess.kt), [`DocumentAssertion`](DocumentAssertion.kt) | Media types, access levels, and a source's claim about a document. |
| `JsonbContains` (JsonbContains.kt) | SQL's `column @> value::jsonb`, for lookups in JSON columns. | none |
```

## Database Tables
A list of database tables that are defined in this package. This section should only be included for backend code.

```markdown
| Table Name | Code | Description |
| --- | --- |
| `documents` | Document.kt | Contains all document metadata with pointers to the actual document content. |
```

## Architecture 
This section will include a sub-section for each significant architecture topic within the code and will typically include code style diagrams
such as sequence diagrams. Use mermaid.js for charts and graphs when relevant.

## TODO
A section capturing things to be added or considered in the future. 

## FAQs
Frequently asked questions about the code base. When the user ask about code related to the package this section should
be updated to include that interaction.

# When not to use
`_package.md` should not be used in the following scenarios:

- Packages that contain generated code.
- Test code.

# What not to include
The following information should not be included in these metafiles.

- Test details: test classes, case inventories, counts, coverage, fixtures, commands,
  or results. Omit a Tests section unless explicitly requested. Tests may still
  inform the explanation of behavior.
- Release history, migration narratives, completed-work summaries, or session notes.
- Exhaustive class, field, or method inventories that repeat the source.
- Unimplemented plans, speculative behavior, or unsupported security guarantees.
- Generic tutorials, promotional language, or commentary about writing the package guide.
- Duplicated parent or child documentation, project setup, or appliance procedures.

## The synchronization command

When the user says `/package-meta synchronize <path>`, start at the provided project relative path (or root if no path is specified)
and find each package metafile and identify any discrepancies between the meta file and source file and report back to
the user. The user will selection with files to update based upon this output.
