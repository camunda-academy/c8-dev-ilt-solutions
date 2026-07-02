# Plan: test all exercises from `integration-test` (not yet implemented)

This documents the agreed design for running **every exercise against every language in one command**.
It is a **plan only** — nothing below is built yet, except where noted as validated.

Supersedes an earlier design (single-repo, harness duplicated on every exercise branch) that turned
out to be cumbersome in practice once actually tried.

## Model B: one harness on `integration-test`, workers sourced from exercise branches

- **`integration-test`** owns the ONE and ONLY `cpt-test/` module: `pom.xml`, `run-all-exercises.sh`,
  `Languages.java`, one `ExerciseNNTest.java` per exercise, `start-runtime.sh`. This replaces
  per-branch harness copies entirely — no more duplication to keep in sync.
- **Exercise branches** (`exercise-01`, `exercise-05`, `exercise-08`, ...) carry ONLY worker source
  (`python/`, `csharp/`, `js/`, `java/`, `java-spring/`) and `assets/` (BPMN/DMN/forms + Camunda Play
  scenario JSON exports). No `cpt-test/` on these branches.
- `git worktree` is repurposed: instead of "check out a branch and run its own tests" (the old model),
  it's now "pull one exercise branch's worker code + assets onto disk so `integration-test`'s single test suite
  can exercise them."

## Runner (`run-all-exercises.sh`, to be written, lives on `integration-test`)

```bash
start runtime ONCE                                    # shared, fixed ports 26500/8080/9600
for branch in $(git branch -r | grep 'exercise-'):    # auto-discover exercise-* branches
    git worktree remove tmp --force 2>/dev/null || true   # defensive: clean up a stale prior run
    git worktree add tmp "$branch"                    # ALWAYS the same fixed path "tmp"
    for lang in python csharp js java java-spring:
        [ -n "$(ls tmp/$lang 2>/dev/null)" ] || continue     # skip if this branch has no impl
        start that worker IN PLACE from tmp/$lang/, pointed at the shared runtime
        mvn test -Dtest=ExerciseNNTest -Dlang=$lang   # integration-test's cpt-test/, deploys
                                                        # everything under tmp/assets/ (see
                                                        # "Asset resolution" below)
        stop worker
    git worktree remove tmp
    collect per-branch/per-language result rows
aggregate -> one exercise × language matrix report
stop runtime
```

**Execution model: sequential**, one shared runtime reused across every branch/language — not
parallel per-branch runtimes. Simpler, matches today's fixed-port single-runtime design, and easier
to debug one failure at a time. (A parallel design would need per-branch runtimes on distinct ports;
rejected as unnecessary complexity for the exercise count involved.)

## Asset resolution — how `ExerciseNNTest` finds "this exercise's assets" (resolved 2026-07-02)

The problem: `integration-test`'s test code has no way to know, at the moment it deploys a BPMN file, which
exercise branch is currently checked out in the worktree — unless something tells it. Two changes
together remove the need for any runtime configuration at all:

1. **Fixed, reused worktree path.** The loop always does `git worktree add tmp <branch>` /
   `git worktree remove tmp` — never `tmp/<branch>`. So the assets directory is always the same
   literal path, `tmp/assets`, regardless of which exercise is currently checked out there.
2. **Deploy by directory scan, not by filename.** `ExerciseNNTest` doesn't hardcode a BPMN/DMN/form
   filename. It lists every deployable file under the assets directory and adds each one to a single
   `newDeployResourceCommand()` before sending. Adding a DMN table or a form to an exercise later
   needs zero test-code changes.

This also means the existing manual, single-branch workflow (`mvn test -Dtest=Exercise05Test` run
directly from an `exercise-05` checkout, no aggregator involved) keeps working unchanged — `assets/`
sits next to `cpt-test/` either way, so the same relative path resolution applies.

**Not yet resolved:** `run-all-exercises.sh` should defensively remove/prune a stale `tmp` worktree
before each `add`, in case a previous run crashed mid-loop (sketched above, not yet written/tested).

## Worker completion — scenario replay, not hand-written happy paths

Each exercise's `assets/` includes a Camunda Play scenario export (e.g.
`PaymentProcess test scenarios.json`). `ExerciseNNTest` loads it via the CPT JSON test-case API
(`TestCasesReader` / `CamundaTestCaseRunner`), **strips `COMPLETE_JOB` instructions**, and replays the
rest (`CREATE_PROCESS_INSTANCE`, `ASSERT_PROCESS_INSTANCE`, etc.) — so the REAL worker started by the
loop completes the jobs, not the test driver. This is what makes the test a genuine worker↔engine
integration test rather than a mocked-worker process test.

**Validated end-to-end 2026-07-02** for exercise-05 / python: `Exercise05ScenarioReplayTest` passed
against a live runtime + real Python worker. Not yet tried for exercise-05's js worker, or for any
other exercise (no scenario JSON exists yet for exercises other than 05).

### Coverage requirement

**Every service task in an exercise must be covered** by at least one scenario — not just a
happy-path subset. Check this by confirming every service task appears in at least one scenario's
`coveredFlowNodes` (Play already records this metadata per scenario).

### Incident / BPMN-error paths are exercise-specific, not a general mechanism

Exercise-08 (incidents) and exercise-09 (BPMN errors) each need exactly ONE worker/service task's
failure path tested — hand-written, not derived from Play (Play only records happy-path runs). Do
**not** build a general per-exercise failure-simulation mechanism (e.g. an input variable that tells
every worker to misbehave on demand) — that was considered and explicitly rejected as unnecessary
generalization. The CPT JSON schema's `RESOLVE_INCIDENT` and
`MOCK_JOB_WORKER_THROW_BPMN_ERROR`/`THROW_BPMN_ERROR_FROM_JOB` instruction types are the natural fit
for these two exercises specifically, when built.

## Versioning: alpha replay API requires a version-matched stack

The scenario-replay API (`TestCasesReader`, `CamundaTestCaseRunner`) only exists in
`io.camunda:camunda-process-test-java` / `io.camunda:camunda-process-test-json-test-cases` version
**`8.10.0-alpha3-rc2`** as of 2026-07-02 — not in any 8.7–8.9 stable release, and undocumented on
docs.camunda.io.

**Client and broker versions must be kept in lockstep.** Bumping only `cpt-test/pom.xml`'s
`camunda.version` while leaving the Docker runtime on stable `8.9.6` was tried first and **broke**:
`CamundaAssert...hasCompletedElements(...)` failed with `400 Bad Request: Request property
[filter.$or] cannot be parsed` — the alpha client sends a filter shape the 8.9.6 REST gateway
rejects. Fix: bump the Docker image to the same version too. `camunda/camunda:8.10.0-alpha3-rc2` is a
real, pullable tag with a matching gateway/broker version. After bumping both `cpt-test/
start-runtime.sh` and `cpt-test/run-exercise.sh`'s `IMAGE=` to match, both `Exercise05Test` (2/2) and
`Exercise05ScenarioReplayTest` (1/1) passed cleanly end-to-end.

Re-check this constraint once a stable Camunda release ships the replay API — a stable release
should not have this specific incompatibility, but verify before assuming.

Worker-side SDK versions (python `camunda-orchestration-sdk`, C# client, JS
`@camunda8/orchestration-cluster-api`) are **independent** of this and were not changed — those
clients talk to the broker over a stable, long-supported subset of the API (create/complete/fail
job), unrelated to the alpha-only `filter.$or` query CPT's own assertions introduced.

## Implementation plan, in dependency order

1. **Validate remaining exercise-05 languages against the alpha stack (current focus).** Python
   confirmed working end-to-end. `exercise-05`'s C# worker is already migrated to the new
   `Camunda.Orchestration.Sdk 9.*` with env-var-first zero-config auth (confirmed against the
   official C# SDK docs 2026-07-02) — this branch (`exercise-05-with-tests`) still has the OLD
   `csharp/*.csproj` pinning `zb-client 2.9.0` and needs the same update before it can be validated
   here. JS and java/java-spring not yet tried.
2. **Migrate `cpt-test/` from its current working branch onto `integration-test`**, generalizing anything that
   hardcodes exercise-05 assumptions. In particular, replace the named-file
   `addResourceFile("Payment Process.bpmn")` deploy pattern with the directory-scan-and-deploy-all
   approach described above, once, so every later `ExerciseNNTest` can reuse it.
3. **Don't merge `cpt-test/` into exercise branches** that shouldn't carry it (i.e. don't merge it
   into `exercise-05` itself — it should only ever live on `integration-test`).
4. **Write `run-all-exercises.sh`** — the worktree-loop aggregator described above.
5. **Author scenario JSON + `ExerciseNNTest` for each remaining exercise.** Exercise-05 is the only
   one with scenarios today. `run-all-exercises.sh` should skip/report cleanly (not crash) for
   exercises that don't have a test class yet, since steps 2–4 can land before step 5 is complete for
   every exercise.
6. **Exercise-08 / exercise-09 hand-written failure-path tests** (incidents / BPMN errors) — lowest
   priority, deferred until the rest of the harness is in place.

## Future constraint: this should eventually run as a GitHub Action

Not acted on yet, but worth designing for from the start rather than retrofitting later:

- **No interactive prompts.** `run-exercise.sh`'s preflight confirmation already guards on
  `[[ ! -t 0 || YES=1 ]]` (no TTY, or `YES=1` set) to skip the `[y/N]` prompt — keep this pattern in
  `run-all-exercises.sh` too.
- **Docker must "just work."** GitHub-hosted runners ship Docker preinstalled, so `docker run` for the
  Camunda runtime should need no special CI setup.
- **No reliance on locally-cached tool state.** venvs/`node_modules`/NuGet packages must be installed
  fresh by the script itself (already true today — `pip install`/`npm install` are explicit steps,
  not assumed pre-existing).
- **Nothing GUI-driven or requiring a human to start a worker.** The whole point of `run-exercise.sh` /
  `run-all-exercises.sh` is to automate what's manual today, so this falls out naturally once those
  scripts exist — no extra work needed specifically for CI.

No CI YAML has been written; this is a constraint to keep satisfied while building the harness, not a
separate task.

## Open items

- Crash-recovery for a stale `tmp` worktree in `run-all-exercises.sh` (sketched above, not written).
- c8ctl (installed locally, v3.2.0) is a candidate for ad-hoc/manual inspection during development
  (`search jobs`, `search vars`, `search inc`), but CPT's own `CamundaAssert`/
  `CamundaProcessTestContext` API remains the automated-suite assertion mechanism — c8ctl doesn't
  replace it.
