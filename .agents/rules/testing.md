---
description: Rules for writing test code in the project
paths:
  - "**/*.kt"
  - "**/*.ts"
  - "**/*.tsx"
globs:
  - "**/*.kt"
  - "**/*.ts"
  - "**/*.tsx"
---

* Do not write unit tests during active development. Complete a cohesive unit of work before adding tests. Once enough 
functionality has been implemented to justify testing, ask the user for confirmation before proceeding. However, if 
executing a long sequence of tasks autonomously without user interaction, proceed with writing tests at an appropriate 
checkpoint without requesting confirmation.
* No placeholder tests just for coverage. Added tests should result in real behavior of the application being tested.

# Test Behavior, Not Implementation

Test code through its public behavior: call the subject with concrete input and assert an observable result or effect against an independently specified expected value. Do not test implementation details, restate constants, or assert data the test itself created.

**Review check:** Ask whether the test would catch a plausible defect in the subject. In particular, would it still pass if the subject did nothing, returned `undefined`, or produced an incorrect result? If so, rewrite or delete it. Failing this hypothetical check is evidence of a weak test; passing it does not automatically make a test useful.

**Reject these patterns when they are the only evidence of correctness:**

- **Weak or missing assertions.** No `expect`, or only `toBeDefined`, `toBeTruthy`, `not.toThrow`, `toBeInstanceOf`, or `toBeGreaterThan(0)`. These may detect some failures but rarely establish correct behavior.
- **Calls or absence only.** Only `toHaveBeenCalled`, `not.toHaveBeenCalled`, `toBeUndefined`, `toEqual([])`, `toHaveLength(0)`, or `not.toBe(wrongValue)`. These do not establish what the subject produces when it should succeed.
- **Self-referential expectations.** The expected value comes from the implementation being tested: `expect(f(a)).toBe(f(a))` or `expect(parsed.url).toBe(buildUrl(...))`.
- **Constant pins.** The assertion copies a constant, config default, table entry, or prompt text from the source: `expect(LIMITS.maxTools).toBe(8)` or `expect(PROMPT).toContain("You are")`. These tests block intentional edits without establishing behavior.
- **Fixture-only assertions.** The assertion checks fixture data, a mock's preset return value, or a value prepared in `beforeEach`, without verifying an effect of the subject.

**Fix:** Exercise the real subject with a specific input and assert the exact output or externally observable effect:

```ts
expect(slugify("Hello, World!")).toBe("hello-world");
```

For an absence or empty-result case, also test a concrete input that should produce a nonempty result. For constants and configuration, test the behavior of the code that consumes them rather than pinning their definitions. For mocks, verify the meaningful payload at a public boundary or the resulting observable state, not merely that a call occurred.

If no meaningful behavioral assertion can be made, delete the test rather than preserving it for coverage.

**Keep:** Tests of relationships or integrity constraints across table rows (such as matching keys or existing parents), and compile-time type checks in `*.test-d.ts` files.

## Backend Testing

* Use TestContainers for tests that incorporate the database.
