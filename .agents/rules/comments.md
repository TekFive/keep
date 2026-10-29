---
description: Rules for comments in source code
paths:
  - "**/*.kt"
  - "**/*.ts"
  - "**/*.tsx"
globs:
  - "**/*.kt"
  - "**/*.ts"
  - "**/*.tsx"
---

# Comments

A comment earns its place when a reader who knows the language and this codebase would otherwise
make a wrong change or spend time rediscovering a reason. If the code, a name, or a type already
says it, leave the comment out. Noisy comments teach readers to skip the ones that matter.

Before writing a comment that explains *what* a block does, try a clearer name or an extracted
function. Comment only when the code can't carry the meaning itself.

## What to comment

A brief, 3 sentence maximum comment is allowed at the start of a class or object to explain it's role/functionality. If more
description is warranted then put this in the associated `_package.md`. Do not add this for enumerations.

**The reason for a choice the code can't show.** Say what goes wrong if someone takes the obvious
route instead.

```kotlin
// A wrong match permanently links the source to the notice, so one weak property is not enough.
if (!titleMatches && otherMatches < minMatchesWithoutTitle) {
    continue
}
```

**External constraints.** Behavior of third-party APIs, libraries, browsers, or regulations isn't
visible from our code.

```kotlin
// GovWin enforces daily download limits; the default spacing keeps a
// connection from burning the allowance in one tick.
```

```tsx
// Radix routes typing to its own menu typeahead; the inputs below need
// the keys instead, and Escape still closes because that is not stopped.
onKeyDown={(event) => { ... }}
```

**Deliberate absences.** A missing index, a skipped check, or an unhandled case that a reviewer
would otherwise "fix".

```kotlin
// Not indexed: a btree on an array serves only whole-array equality, and every lookup
// narrows by data_id first, which the foreign key indexes.
val propertyChanges = column(DataSnapshot::propertyChanges)
```

**Chosen values.** Explain a number, limit, timeout, or color when its name doesn't say why it has
that value.

```ts
// Deeper than the dark accent, which is too light to carry a white label.
buttonPrimary: '#0a6ed1',
```

**Security and compliance rationale, always.** The stated reason a check exists is what stops
someone from removing it later.


**Summary of a block of code.** Sometimes commenting what a block code does can be helpful to quickly understanding the code.

```kotlin
// Related notices under several acquisitions make those acquisitions merge candidates. Until that
// is decided the notice joins the one holding most of its related notices, the oldest on a tie.
var selected: Acquisition? = null
var selectedCount = 0
for ((acquisitionId, count) in relatedAcquisitionCounts) {
    val acquisition = Acquisition.findById(acquisitionId) ?: continue
    if (selected == null || count > selectedCount ||
        (count == selectedCount && acquisition.addedAt.isBefore(selected.addedAt))
    ) {
        selected = acquisition
        selectedCount = count
    }
}
return selected
```


## What not to comment

- **Notes about the edit.** `// Added null check`, `// Now uses findById instead of a query`,
  `// Fixed bug where...`. These describe the change, not the code, and go stale right away. Put
  them in the commit message.
- **Notes to the reviewer or user.** `// As requested`, `// You may want to...`. Comments are
  for future readers of the code.
- **Narration.** `// Loop over the notices`, `// Save the user`, `// Return the result`.
- **Speculation.** `// Might be needed later`, `// Not sure this handles X`. Settle the question or
  ask the user. Don't leave the doubt in the source.
- **Commented-out code.** Delete it. Git keeps it.
- **Doc comments that restate the declaration.** Don't turn a name, type, or inheritance
  relationship into a sentence. A declaration doesn't need a comment just because it is public,
  abstract, or shared.
- **Incidental implementation details.** Omit routine naming conventions, generated identifiers,
  and examples of those names unless a reader needs them to avoid a specific mistake. When a
  convention is required by an external dependency or a hidden constraint, explain why it matters
  and what would break if it changed.
- **Test comments that repeat the assertion.** Name the test well instead.


This is an example of a bad comment. It's obvious that NoticeDocumentVersion is a single version of a NoticeDocument
just from the name.

```kotlin
/** One observed URL of an [NoticeDocument]; notices reference versions, not documents. */
class NoticeDocumentVersion(
    val noticeDocumentId: UUID,
    primaryUrl: String,
    secondaryUrls: List<String>,
    version: Int,
    details: String? = null,
    fileName: String? = null,
    sha256: String? = null,
    mediaType: String? = null,
    sizeBytes: Long? = null,
    createdAt: Instant? = null,
    modifiedAt: Instant? = null,
    addedAt: Instant = Instant.now(),
    addedBy: DataWriterReference,
) : DocumentVersion(primaryUrl, secondaryUrls, version, details, fileName, sha256, mediaType, sizeBytes, createdAt, modifiedAt, addedAt, addedBy,)
```

## Doc comments

Use KDoc or TSDoc on a declaration when its contract isn't obvious from the signature:
preconditions, what it throws, side effects, cost, or thread-safety. Put contracts in doc comments,
not `//`, so they show up in IDE hover at the call site.

Check each sentence separately. Adding a detail or example doesn't make an obvious summary useful.
Keep only information that helps a reader use or change the declaration correctly. If nothing
remains beyond what the code already says, omit the doc comment.

```kotlin
// Before: an inline note callers never see.
// Use type to make sure this is an acquisition based opportunity before calling.
val acquisition: Acquisition

// After
/** Throws unless [type] is [OpportunityType.ACQUISITION]. */
val acquisition: Acquisition
```

See [Kotlin style](kotlin-style.md#comments-and-kdoc) for KDoc rules specific to Kotlin.

## Keeping comments true

- When you change code, reread the comments next to it. Update or delete any that no longer hold.
  A wrong comment is worse than a missing one.
- Keep existing comments when you move or refactor code. Don't delete a comment just because you
  don't see its point. It may record a constraint you haven't run into yet.
- Describe how the code behaves now, in the present tense. Don't describe how it used to work.

## Voice

Write full sentences in plain words. Start with the fact, then the consequence ("..., so ...",
"Otherwise ..."). A `TODO:` should name what's missing and what would unblock it.
