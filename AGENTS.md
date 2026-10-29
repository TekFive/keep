This project is a tool used by TekFive to house and manage their procedural documents.

# Repository layout
The following are the main top-level directories. `AGENTS.md` files in subdirectories should be read before doing work in them.

```text
.
├── src/            # All library source code.
├── docs/           # Project related documentation.
├── specs/          # Agent authored design specifications about this system. Assume documentation in this directory is out of date.
```

## Non-negotiable rules

* ALWAYS create `_package.md` or check if it needs to be updated when you change code. Use the `package-meta` skill for this.
* NEVER add third party dependencies without first getting user confirmation..
* NEVER create a branch or worktree in the project without first getting user confirmation.
* NEVER add the agent identity to a git commit.
* NEVER change anything I didn't ask you to without first confirming.

## Work directions

* When the session start ask the user what the max number of sug-agents to concurrently use should be. If the user allows
  additional sub-agents then use those as needed to improve the result and performance of any task(s) for the current session.
* If my request is ambiguous, ask one clarifying question before doing anything.
* The code should be used as the source of truth. When the code diverges from documentation point that out but assume the and take priority over documentation files.
* Do not commit code without direction from the user.