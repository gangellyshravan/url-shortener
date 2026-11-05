# URL Shortener and Agentic Engineering Workflow

This Java 21 / Spring Boot application provides a persistent URL shortener and a prototype SDLC orchestration API. The orchestration demonstrates requirement normalization, design, implementation proposals, validation, documentation, release review, human approval, audit history, and reliability metrics.

## Run locally

The default database is a file-backed H2 database under `./data`. Flyway applies the SQL migrations at startup. The service requires JWT verification configuration:

```powershell
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
.\mvnw.cmd spring-boot:run
```

The generated key above is for local JWT verification only; the app does not issue tokens. Obtain an admin token from a trusted issuer, or configure `JWT_ISSUER_URI` / `JWT_JWKS_URI` instead. Admin tokens must contain the configured role claim (default `role`) with value `urlshortener.admin`. Never reuse a demo key in a shared or production environment.

Configuration can be supplied through environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `URLSHORTENER_JDBC_URL` | `jdbc:h2:file:./data/urlshortener;AUTO_SERVER=TRUE;NON_KEYWORDS=DAY` | JDBC URL |
| `URLSHORTENER_DB_USER` / `URLSHORTENER_DB_PASSWORD` | `sa` / empty | Database credentials |
| `JWT_SECRET` | unset | Base64 HMAC key (minimum 32 decoded bytes) |
| `JWT_ISSUER_URI` / `JWT_JWKS_URI` | unset | Alternative JWT verification configuration |
| `JWT_AUDIENCE` | unset | Optional required token audience |
| `JWT_ROLE_CLAIM` / `JWT_ADMIN_ROLE` | `role` / `urlshortener.admin` | Admin authorization claim/value |
| `WORKFLOW_MAX_PARALLEL_TASKS` | `4` | Bounded concurrent task execution (1–16) |
| `WORKFLOW_MAX_ATTEMPTS` | `3` | Bounded task attempts (1–5) |
| `WORKFLOW_RETRY_DELAY_MS` / `WORKFLOW_MAX_RETRY_DELAY_MS` | `100` / `1000` | Exponential retry delay bounds |
| `WORKFLOW_TASK_TIMEOUT_MS` | `30000` | Maximum task-wave duration, including retries (1–300000 ms) |

Run the test suite with:

```powershell
.\mvnw.cmd test
```

## Orchestration API

All `/api/orchestration/**` routes require an admin JWT. Scenarios are listed at `GET /api/orchestration/scenarios`. Submit a run:

```http
POST /api/orchestration/runs
Authorization: Bearer <admin-jwt>
Content-Type: application/json

{"scenario":"GREENFIELD","requirement":"Add an authenticated link export endpoint"}
```

The response is `202 Accepted` with the run ID and current state. Poll `GET /api/orchestration/runs/{id}`. Review `GET /api/orchestration/runs/{id}/artifacts` and `/events`. When the run reaches `WAITING_APPROVAL`, approve or reject the exact task artifact version:

```http
POST /api/orchestration/runs/{id}/approvals
Authorization: Bearer <admin-jwt>
Content-Type: application/json

{"taskKey":"release-readiness","artifactVersion":1,"decision":"APPROVE","rationale":"Reviewed the proposal and validation evidence"}
```

Use `taskKey:"assumptions"` to approve the clarification gate in the `AMBIGUOUS` scenario. A stale artifact version returns `409 Conflict`. `POST /api/orchestration/runs/{id}/cancel` requests a safe stop. `POST /api/orchestration/runs/{id}/replan` accepts a revised completed non-approval artifact and rationale; downstream tasks are rerun, their artifact versions advance, and prior approvals are invalidated.

`GET /api/orchestration/metrics` reports total/terminal runs, terminal-run success rate, retry events, withdrawn-artifact rollback events, mean recovery time for successful replans, and mean end-to-end time for terminal runs. The recovery interval is measured from the first replan to eventual success. Open runs are excluded from terminal-run rate and duration averages.

## Scenarios and limitations

- **GREENFIELD:** a well-defined new capability goes through the full graph and pauses for release approval.
- **BROWNFIELD:** an enhancement proposal explicitly preserves existing behavior and pauses for release approval.
- **AMBIGUOUS:** produces explicit assumptions and pauses for human clarification before design and implementation work.

The default deterministic executor is an offline demonstration adapter that writes only bounded proposal artifacts to the database. It never runs generated code, edits the repository, deploys, or calls an external model. A custom `WorkflowTaskExecutor` bean can replace it; any real agent/tool adapter must retain the policy, artifact, authorization, and approval boundaries. Approval records review of a proposal; this prototype intentionally does not implement deployment or repository mutation.

The application uses a single-process coordinator and H2 default storage. It is not a distributed worker system; process crashes can leave a run marked `RUNNING`, and operator recovery/reconciliation is currently manual. Audit history has no automatic retention/compaction policy. Production deployment requires an external database, secret management, distributed scheduling/recovery, operational retention controls, and security review.

See [docs/architecture.md](docs/architecture.md) for workflow state, governance, and risk details.
