# CPT harness across all exercises

## Architecture

- **`integration-test`** (orphan branch) owns the ONE `cpt-test/` Maven module: `pom.xml`,
  `start-runtime.sh`, `run-exercise.sh`, `preflight.sh`, `env.local.sh`, `Languages.java`, and one
  `ExerciseNNTest.java` + `ExerciseNNScenarioReplayTest.java` pair per exercise. No worker code, no
  `assets/` — `git ls-tree` on this branch shows only `.gitignore`, `CLAUDE.md`, `README.md`,
  `cpt-test/`.
- **Exercise branches** (`exercise-05` ... `exercise-12` today) carry ONLY worker source
  (`python/`, `csharp/`, `js/`, `java/`, `java-spring/`) and `assets/` (BPMN/DMN/form files +
  Camunda Play scenario JSON). No `cpt-test/` on these branches.
- `git worktree add tmp exercise-NN` pulls one exercise branch's worker code + assets onto disk so
  `integration-test`'s test suite can run against them, without touching either branch's own
  checkout.

## Running the tests

Single exercise, every available language (skips languages with no implementation on that
branch):

```bash
git worktree add tmp exercise-05
cd cpt-test
WORKTREE=../tmp ./run-exercise.sh          # or: ./run-exercise.sh python js  (specific languages)
git worktree remove ../tmp
```

`run-exercise.sh` derives the exercise number from `WORKTREE`'s checked-out branch name, runs
preflight (auto-fixes missing venvs/`node_modules`), starts (or reuses) a local Camunda runtime,
then for each available language: starts that worker, runs `ExerciseNNTest` +
`ExerciseNNScenarioReplayTest` against it, stops the worker. Writes `results/report.md` plus
per-language Surefire reports and worker logs under `results/<language>/`.

There's no `run-all-exercises.sh` yet — looping this over every exercise branch is a one-off
shell loop around the same `WORKTREE=`/`git worktree` pattern, reusing the already-running runtime
across branches (the script already supports this via its `runtime_ready` check).

## Per-exercise coverage (exercise-05 through exercise-12)

Every exercise has a hand-written `ExerciseNNTest` (happy path + the exercise's specific feature)
and an `ExerciseNNScenarioReplayTest` (replays the Camunda Play-recorded `assets/*.json` scenarios
via the CPT JSON test-case API, real worker completes the jobs). Validated end-to-end, all
available languages, against a live runtime + real workers:

| Exercise | Feature | Notes |
| --- | --- | --- |
| 05 | Payment Process happy path | credit sufficient / insufficient branches |
| 06 | + card fee script task | |
| 07 | Order + Payment Process, message correlation | two processes, async invoke |
| 08 | Incidents | invalid expiry date → job fails with `retries=0` → incident on `credit-card-charging` |
| 09 | BPMN errors | invalid expiry date → `ThrowError` → boundary event → dedicated failure path (`OrderProcess` gets an event-based gateway racing success/failure messages) |
| 10 | User tasks | boundary event now routes to a "Check failed payment data" user task; test completes it directly via `newCompleteUserTaskCommand`, either resolving (retries the charge) or not (routes to failure) |
| 11 | Connectors | `OrderProcess` gained an HTTP-JSON connector step (`Fetch product info`, calls dummyjson.com) feeding `orderTotal`; test stubs this job (activates + completes it manually with fabricated data) instead of calling the real API — no Connector Runtime container in this setup, and the endpoint needs a real, expiring bearer token |
| 12 | DMN | `OrderProcess` gained a business rule task evaluating `Order Discount DRD.dmn` against the (stubbed) `productPrice`; the DMN itself runs for real (broker evaluates it directly, no separate runtime needed) |

Language availability varies per branch — `python` has no implementation on exercise-11/12 (empty
`python/` folder, just `.gitkeep`); `Languages.isAvailable()` detects this and the test class skips
that language cleanly rather than failing.

## Known recurring bug (fixed independently on each branch)

Exercises 09 through 12 each independently reintroduced the same JS bug: `respondToOrderProcessFail`
(the `payment-failure` job handler) was copy-pasted from the success handler and published
`paymentCompletedMessage` instead of `paymentFailedMessage`, breaking `OrderProcess`'s
event-based-gateway failure routing. Fixed with a one-line commit on each branch as it was found
(each branch was cut before the previous branch's fix landed, so the branches are independent, not
stacked). Worth checking for on any new exercise branch before assuming the failure path works:
`grep -A3 respondToOrderProcessFail js/src/workers/exercise_*.ts`.

Exercise-08/09/10/11/12's `PaymentProcess`/`Order process test scenarios.json` also needed
rebuilding from the stale pre-fix version more than once, for the same reason (each branch cut
before the fix). `Payment Process.bpmn` itself is unchanged since exercise-08, so once fixed on one
branch, that scenario JSON can just be copied to the next as a starting point — verify the BPMN
really is unchanged first (`git show <prev-branch>:"assets/Payment Process.bpmn" | diff - "assets/Payment Process.bpmn"`).

## Versioning constraint

The CPT JSON scenario-replay API (`TestCasesReader`, `CamundaTestCaseRunner`) only exists in
`io.camunda:camunda-process-test-java` / `io.camunda:camunda-process-test-json-test-cases` version
`8.10.0-alpha3-rc2` — not in any 8.7-8.9 stable release, undocumented on docs.camunda.io. The Docker
runtime image must be pinned to the SAME version (`camunda/camunda:8.10.0-alpha3-rc2` in both
`start-runtime.sh` and `run-exercise.sh`) — a version mismatch between client and broker breaks
`CamundaAssert` with a `400 Bad Request` on the alpha-only filter shape the client sends. Re-check
this constraint once a stable Camunda release ships the replay API.

## GitHub Actions: what it would take

Not built yet. Researched, not blocking at current scale (8-12 exercises).

**Triggering.** `on.push.paths-ignore` (e.g. ignore `**.md`) works fine per-branch for skipping
doc-only changes — no branch-specific logic needed there. But a push to `integration-test` (the
harness itself) needs to fan out and re-test EVERY exercise branch, not just re-run in place; that
fan-out is a job-level concern (list `exercise-*` branches, feed a matrix), not something the
trigger block itself can express.

**Checkout.** The direct equivalent of local `git worktree add tmp exercise-NN` is two
`actions/checkout@v4` steps in one job with different `ref:`/`path:` — one for `integration-test`,
one for the exercise branch (or `${{ matrix.exercise }}`). `WORKTREE` then just points at the
second checkout's path, unchanged from how the script already works locally.

**Docker.** GitHub-hosted `ubuntu-latest` runners have Docker preinstalled; the existing scripts'
raw `docker run`/`docker rm` calls work as plain `run:` steps, no `services:` sidecar or DinD setup
needed.

**Cost.** Matrix by exercise only (not exercise × language — `run-exercise.sh` already loops
languages internally with skip-on-missing). Rough estimate: ~90-180s per language-run cold,
~40-70s with `~/.m2`/`node_modules`/pip/NuGet caches warm via `actions/cache`. Full run today (8
exercises × up to 4 languages ≈ 32 language-runs): ~80 minutes of billed job-minutes uncached,
dropping meaningfully with dependency caching. **Dependency install (npm/pip/dotnet/mvn) dominates
the cost, not the Docker image pull (~30-90s, paid once per job) or actual test execution
(5-12s).** Parallelizing across jobs buys wall-clock speed, not lower total billed minutes (GHA
bills the sum of job-minutes). Well within the private-repo free tier (2,000 min/month) unless this
runs many times a day — confirm repo visibility before treating cost as a real constraint.

**Before building:** confirm repo visibility (private free-tier minutes vs. public unlimited);
spike the dynamic branch-discovery step for the fan-out matrix (list `exercise-*` branches → JSON →
`fromJSON` in `strategy.matrix`); consider pinning the alpha image by digest rather than mutable tag
if a CI run's reproducibility matters.
