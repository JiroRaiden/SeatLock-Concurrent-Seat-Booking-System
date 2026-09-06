# CI

One workflow, `ci.yml`, on every push and pull request to `main`. Three jobs run
in parallel and every one of them can fail the build.

| job | what it does | typical time |
|---|---|---|
| **Backend (Java 21)** | `mvn -B verify` — compiles, runs the fast unit tests under Surefire, then the Testcontainers integration tests under Failsafe against a real Postgres and a real Redis | ~3–5 min |
| **Frontend (Node 20)** | `npm ci`, `npm run typecheck`, `npm run lint`, `npm run build` | ~1–2 min |
| **Security scan** | Trivy filesystem scan (HIGH/CRITICAL, fixable only) plus `npm audit --audit-level=high` | ~1 min |

There is no deploy job. `ci.yml` ends with a commented-out sketch of one, and a
note on why the credentials it would need do not belong in this repository.

---

## Reading a failure

Start with the job name in the Actions tab — the three jobs fail for completely
different reasons and the fix is different in each case.

### Backend

**A test failed.** The log shows the assertion, but the useful detail is in the
artifact: a failed run uploads `surefire-and-failsafe-reports`, which contains
the per-class `.txt` and `.xml` files with full stack traces and AssertJ's
description of what was expected. Download it from the run summary page.

Then reproduce locally:

```bash
cd backend
mvn -B verify                 # everything, needs Docker running
mvn -B test                   # unit tests only, no Docker, seconds
mvn -B verify -Dit.test=BookingLifecycleIT   # one integration class
```

**It passed locally but failed in CI.** Almost always one of three things:

- *A concurrency test.* `ConcurrentSeatBookingIT` is a `@RepeatedTest` for
  exactly this reason — a race that shows up one run in twenty is still a race.
  A CI runner has different core counts and timing from a laptop, so it can
  surface something a local run does not. Re-running to make it go away is the
  wrong move; that is the test doing its job.
- *Leaked state between tests.* Every integration test class truncates the
  database and flushes Redis in `@BeforeEach`. A test that forgets the Redis
  flush passes alone and fails in a suite, because a hold from the previous
  class is still live — Redis has no transaction to roll back.
- *A Testcontainers startup failure.* Look for `Could not find a valid Docker
  environment` near the top. That is infrastructure, not your code.

**`ddl-auto: validate` failed at startup.** Hibernate's entity mappings no longer
match the schema Flyway produced. Someone changed an entity without adding a
migration, or the other way round.

### Frontend

- `typecheck` failing but `build` passing locally is expected —
  `vite build` strips types with esbuild rather than checking them, which is why
  the two are separate steps here.
- `lint` fails on warnings, not just errors (`--max-warnings 0`). Warnings that
  do not fail the build accumulate until nobody reads any of them.

### Security scan

This is the one job that can go red on a commit that changed nothing, because a
new advisory was published overnight. That is accepted on purpose: the
alternative is hearing about it from somebody else.

- Read the finding first. Trivy names the artifact, the installed version and
  the version that fixes it.
- Bump the dependency. For a Spring-managed one that usually means bumping the
  `spring-boot-starter-parent` version rather than pinning a single artifact.
- Only if there is genuinely no fix available should the finding be suppressed,
  and the suppression should say why and who decided. `ignore-unfixed: true` is
  already set, so anything reported *does* have a fix.

---

## Why `verify` and not `test`

`pom.xml` splits the suite by class name:

- `*Test` → Surefire → `mvn test`. No Docker, milliseconds. Run these constantly.
- `*IT` → Failsafe → `mvn verify`. Testcontainers, real Postgres and Redis.

The inner loop stays fast; CI runs everything. A new test class needs the right
suffix or it will never run in the phase you expect — a class named
`SeatHoldServiceTest` that needs Redis would fail every local `mvn test`, and one
named `JwtServiceIT` that needs nothing would pointlessly wait for Docker.
