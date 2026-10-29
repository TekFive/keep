---
description: Rules for backend controllers that take a request from the frontend and returns a response based upon the shared API.
paths:
  - "**/*Controller.kt"
globs:
  - "**/*Controller.kt"
---

The following rules should be followed when adding or updating controllers in the backend code base.

* Controllers and controller related logic should be placed in the subpackage `io.api` relative to whatever package the
controller logic is associated with.
* Controller file names should should always end in `Controller` such as `OpportunityController.kt`.
* Only one controller class per file should be allowed.
* A controller should be defined as an object unless it needs state initialization.
* A controller function declares a parameter of a generated `...Dto` class to read the body and returns one to write 
the response; the kivash configured pipeline will map the response JSON. Unless explicitly requested from the user 
a controller should never generate dynamic JSON. If a model doesn't exists for the response prompt the user for direction.
* Controllers check and clean what a request asks for. Turning a request into values fit to store is a core part of a 
controller's job: trimming text, treating blank text as none, enforcing lengths, ranges, and formats, removing repeats, 
and checking stored state, such as a name another record already holds or a referenced record that must exist. Do this 
in the controller, not in a separate `...Edit` or request class. 
* Avoid locking rows for updates. Instead run units of work in a `db { }` transaction. On the rare occasion where two
users are making changes at the same time and collide the database will determine who wins and one user will receive an
error. This rare condition isn't worth cluttering the code.
* Avoid custom API exception classes except for cases where significant code can be reduced or simplified.
* Any custom classes or types that are created for controller work should live in the controller package.
* Authentication and RBAC authorization is enforced by request pipeline. Controllers should not re-implement this. In
some cases a more contextual level of authorization is required that controllers will need to provide. In these
cases use `AuthenticationContext` as route function parameter to perform.
* Database work owns its transactions. There is no request-wide JDBC interceptor. Controllers or domain services use 
explicit `db { }` blocks for atomic reads and writes. Session and failure counters commit independently;

