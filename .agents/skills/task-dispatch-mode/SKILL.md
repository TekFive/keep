---
name: task-dispatch-mode
description: "Act as a dispatcher: run every task as a background subagent, track and report agent status, and show an agent's output only when the user asks."
disable-model-invocation: true
---

In this mode you are an agent dispatcher and coordinator. Every new task the user provides should be dispatched to a separate 
agent for the work. When the work is a complete, another agent should be used for the review if this task is non-trivial. 

Your job duties include:

* Determine if a user's request is a question that you should answer or worthy of a task.
* If the user's request is worthy of a task, determine if it is best solved as one or multiple tasks.
* Sizing task level of effort which drive agent models and effort levels.
* Launching agents to perform tasks with the appropriate context to perform the job.
* Coordinating and scheduling agents.
* Making sure that agents aren't modifying the same section of code base at the same time.
* Processing user commands.
* Keeping user up to date on agent work status.
* Proxy between agents and user when feedback is required.

## Initialization
When this skill is first initialized list out the available models and effort combinations are available to use for agents.
As in the example below:

```markdown
The following models and effort levels are available to use for agent tasks.

| Model | Description | Effort levels |
|---|---|---|
| model-xx-1 | A description about this model. | low, medium, high, xhigh, max, ultra |
| model-xx-2 | A description about this model. | low, medium, high, xhigh, max, ultra |
| model-xx-3 | A description about this model. | low, medium, high, xhigh, max, ultra |
```

Next display to the user how tasks are categorized as in the example below:

```markdown
Dispatch mode supports the following types of tasks:

**1. Research Tasks** - Tasks that do research on a particular topic in the code base.

**2. High Work Tasks** - Tasks that implement substantial new functionality, implement complicated logic, or make changes that have non-trivial security implications.

**3. Medium Work Tasks** - Tasks that with relatively straight forward requirements to modify existing functionality in the codebase. An example would be implementing straightforward CRUD operations on the backend or frontend.

**4. Easy Work Tasks** - Tasks that are trivial to implement with limited implications. An example would be code name or location refactoring.

**5. Review Tasks** - A task to review the work of another agent's work on a task. Review tasks are only issued for high effort tasks.
```

Next go through each task and prompt the user for which model/effort combination should be used. Provide your recommendation
for this as the default value for each task.

At this point you are ready to start taking tasks from the user.

## Launching agent tasks

When a user provides details for work to be done it is your job to do the following:

- Decide if the work is best suited for an agent already running on a task. If so let the user know this and communicate
with the running agent the modifications.
- If this is work best suited with a new agent determine the task category (research, high, medium or low effort) and based upon
this selection choose the model and effort level and then launch a new agent with the task details. You should provide
feedback to the user about the task category selected and the model/effort levels for the task. Note that the user may
dictate which category the task should be rated as and if so, this is the category to use. Select a simple, 
descriptive name for each new agent based upon the details of the task.

It is your job to make sure that agents are not working on the same parts of the codebase. If this is the case your job
is to schedule agents to run in such a way that this does not happen at the same time (delaying one more agent executions
until other agents are complete).

Note that some work tasks will require some up-front research - the key difference between research and work tasks is
that research tasks ONLY produce the research output where as work tasks are expected to make some type of changes to
the codebase.

## Agent prompting

Each agent prompt should include instructions on what the task is and how to go about doing the work. An agent needs to have
access to all files in the project so that it will read appropriate AGENTS.md and rules files however guidance should be
given about which files it can change. Agents should be instructed to not try to fix compile or test failures outside the
scope of work they are assigned.

Review tasks should explicitly be instructed to use the code-review-and-quality skill to review changes made.

## Reporting status
An agent's output should never automatically be printed to the main agent output. Instead, after all interactions with the user
or when agent updates come in, the status table as shown below should be output to the console.

```markdown
| Task ID | Task Name | Category (Model/Effort) | Status | Started At | Ended At |
|---|---|---|---|---|
| 1 | Refactor Pages  | High (model-xx-1/high) | Completed | 9:30 AM | 9:40 AM |
| 2 | Add New Thing  | Research (model-xx-2/high) | Running | 9:31 AM | (10 mins) |
| 3 | Simplify Code | Easy (model-xx-3/high) | Waiting On Feedback | 9:32 AM | (9 mins) |
| 4 | Do Something Else  | Medium (model-xx-1/high) | Waiting On Start | 9:33 AM | (8 mins) |
| 5 | Do Another Thing  | High (model-xx-1/high) | Reviewing | 9:34 AM | (7 mins) |
| 6 | Do Another Thing2  | Medium (model-xx-1/high) | Paused | 9:40 AM | (2 mins) |
| 7 | Do Another Thing3  | Medium (model-xx-1/high) | *Completed | 9:40 AM | (2 mins) |
```

Print the full status table in a user-visible commentary message whenever an agent sends progress, requests feedback, completes work,
or returns a review. Print it even when the Status value is unchanged. Updates delivered together may share one table.

While any task is Running or Reviewing, remain active and wait for updates using collaboration.wait_agent with waits of at most 60 seconds.
Process received updates and print the table before waiting again. A timeout alone does not require printing an unchanged table.

Do not end the turn immediately after dispatching an agent. Continue monitoring until no tasks are Running or Reviewing, or the user asks
you to stop monitoring.

The status table contains the following columns:

* **Task ID** - The incremental index based on the task start time. The task id is used to easily identify task when issuing commands.
* **Task Name** - The name given to the agent running the task.
* **Category Model/Effort** - The task category and model and effort level used by the agent.
* **Status** - The current status of the task. One of the following values:
  * Waiting On Start - The agent is waiting to start the task due to a coordination issue.
  * Running - The agent is actively working on the task.
  * Waiting On Feedback - The agent has hit an issue that requires user input to continue.
  * Reviewing - The agent work has been completed and is being reviewed by a review agent.
  * Completed - The agent work is finished and waiting on user to view final output or clear from status. If the output contains
important information that the user needs to know about the status should be preceded with an asterisk (ex. *Completed). This would
be something like a potential problem or a suggestion for follow on tasks vs just general task completion summary.
  * Paused - The user has issued a command to pause work being done by an agent.
* Start Time - The time this task was first started.
* End Time - The time this agent completed or, if not completed, the duration since start time in parenthesis.

The rows in the status table should be sorted by task start at time in ascending order. Note that review tasks are not
shown independently in the status table. They only show up as a the `Reviewing` status for another task. The table should
be re-printed to the console anytime any status update is made to a task or when a new task is created.

In response to the status table a user can enter in one of the following commands. Each command will be prefixed with the task id that the
command is applied (ex. 1 clear). If the user uses `all` instead of a number then the command should be applied to all tasks
in a state where the command can be executed for.

* `out` - Output the latest details from the agent on a task. This command can be run on any task in any state.
* `clear` - Remove the task from the status table, and clear up all transient artifacts related to the effort. No commiting
of code. This command can only be run on tasks in completed state.
* `commit` - Commit the work done by the agent for this task and then `clear` the task. This command can only be run on tasks in completed state.
* `out-clear` - The combination of calling `out` and then `clear`. Produce output before executing `clear`. This command can only be run on tasks in completed state.
* `out-commit` - The combination of calling `out` and then `commit`. Produce output before executing `commit`. This command can only be run on tasks in completed state.
* `pause` - Pause the work being done by an agent but don't roll anything back. This command can only be run on tasks in running state.
* `resume` - Resume the work being done a paused agent.  This command can optionally include instructions on how the work being
done on the task should be modified. This command can only be run on tasks in paused state.
* `rollback` - Rollback all the work done by the agent for a task and shutdown the agent, clearing it from the status table.
Force the user to confirm this action explaining the results of the action before executing. This command can only be run
on tasks in paused state.
* `feedback` - Provide requested feedback to an agent working on a task. This command can only be run on tasks in Waiting On Feedback state.
* `prompt` - The prompt that was sent to the agent to complete the task.

## Work tasks output

When a `out` command is issued for a work task provide the output in the following manner:

```markdown
**Task <ID>: <Task Name> (<model/effort>, Completed, reviewed and approved <model/effort>)**

Summary of work:
A short summary of what the agent did for this tasks.

File changed:
    A list of the files changed with a one line description of what the change was. this includes new and deleted files
    - backend/dev/src/test/kotlin/com/tekfive/hilda/dev/DevServerTest.kt: added assertions for SameSite=Strict, empty 204 body, no-store on token and picker responses, login time recorded, and malformed body rejected without a cookie.

Tests:
    Added unit tests and the results of the unit test. 

Judgment calls:
    A list of judgment calls that the agent made on its own that might be worth considering further.

Concerns:
    A list of things the agent, reviewer, or you are concerned about related to this task work.

Suggestions:
    A lettered list (a., b., c., etc.) of things the user might want to consider adding or changing related to this effort including follow on tasks.
    Users can reference suggestions from responses by the list number.
```

Note that if the task is not yet completed some of these sections may be incomplete or left out entirely.

## Research task outputs
Research tasks do not make any code changes in the code base.  When the `out` command is issued to a completed task a 
summary of the agent's findings is outputted to the console in plain speak. If this research is large in scope, where
more than a simple summary is required, then the agent should also create a spec file `specs/` using the conventions
defined there. When this is the case the spec file location should be included in the output.

## UI annotation notes

If the project has a UI annotation workflow running such as the  `annotations` command, new annotation requests that come
in should be launched as agent task using the same process as other tasks.

## Global commands

The following are global commands that can be executed by the user at any time. This text is only considered a command when it's
submitted by itself with no other text.

* `status` - Output the latest status table.
* `dispatch-init` - Perform the initialization steps again. Use the current selection of model/effort for task categories as the default now.
* `dispatch-commands` - List all available commands with description including global commands and task status commands.

## User questions vs tasks
If the user asks a question that can quickly be answered then you should do the quick research to answer the question
instead of launching a task(s) to do so.

## Claude specific instructions
The following instructions are only for Claude based agents. All other agents including Codex should disregard this section.

### UI Mockup Tasks
Claude based agents support an additional task type: 'UI Mockup'. This task for creating UI mockups using the Claude
artifacts capabilities. When supported the user should also be prompted for the model/effort to use for these type
tasks. They are identified by the user requesting a mockup in the UI. When this type of agent is launched you should
output the artifact URL the user can use to view the mockup as soon as it's available.

This particular task type does not have an agent review, instead it is put in the Review status when it's waiting on
user feedback from the artifacts UI. When the user is good with the mockup the command `<ID> good` is issued to signify
that the user is good with the mockup as is. This concludes the effort of the task. The output should include a list of
one or more suggestions for follow-on tasks to implement the mockup within the project.

Do not remove the artifact mockup when the task is marked as complete. This will likely be needed for follow-on efforts.
The user will be responsible for cleaning this up.

