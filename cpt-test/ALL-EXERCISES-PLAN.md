# Plan: test all exercises from `integration-test`

Design for running **every exercise against every language in one command**. Single-exercise testing
(`run-exercise.sh`, one branch at a time via `git worktree`) is **built and validated**. Looping over
every exercise branch automatically (`run-all-exercises.sh`) is **not yet built** — see "What's left"
below.

Supersedes an earlier design (harness duplicated on every exercise branch), abandoned as cumbersome
once actually tried.

## Model B: one harness on `integration-test`, workers sourced from exercise branches

- **`integration-test`** owns the ONE and ONLY `cpt-test/` module — `pom.xml`, `run-exercise.sh`,
  `preflight.sh`, `env.local.sh`, `Languages.java`, one `ExerciseNNTest.java` per exercise,
  `start-runtime.sh`. **Built**: this branch exists (orphan root commit, no shared history with
  `main`/exercise branches on purpose) and contains exactly this — no worker code, no `assets/`.
- **Exercise branches** (`exercise-01`, `exercise-05`, ...) carry ONLY worker source (`python/`,
  `csharp/`, `js/`, `java/`, `java-spring/`) and `assets/` (BPMN + Camunda Play scenario JSON). No
  `cpt-test/` on these branches.
- `git worktree` pulls one exercise branch's worker code + assets onto disk so `integration-test`'s
  test suite can exercise them, without disturbing either branch's own checkout.

## How it works today — `run-exercise.sh` + `WORKTREE=`

**Built and validated** (2026-07-03, all 4 languages, real runtime, real workers):

```bash
cd "<repo root>"
git worktree add tmp exercise-05      # pull one exercise's worker code + assets/ onto disk
cd cpt-test
WORKTREE=../tmp ./run-exercise.sh     # preflight -> start runtime -> per language: start worker,
                                       # run tests, stop worker -> stop runtime -> results/report.md
git worktree remove tmp
```

`WORKTREE` (shell) and `-DworktreeDir` (the matching Java system property, read by
`Languages.repoRoot()`) both default to `cpt-test`'s own sibling directory — the old
same-branch layout still works unchanged if you omit them. See `cpt-test/README.md` for the
user-facing version of this (prerequisites, command, expected output).

**What changed in the scripts to make this work:**
- `run-exercise.sh` / `preflight.sh`: added `WORKTREE=` (all `${REPO_ROOT}/<lang>` paths became
  `${WORKTREE}/<lang>`), added `java-spring` as a 4th language (`ALL_LANGS`, `lang_available`,
  `lang_start`, `check_java_spring`), fixed a real bug where `parse_surefire()` only read the LAST
  matching Surefire `.txt` file instead of summing across every test class in a `-Dtest=A,B` run
  (silently under-reported totals once `Exercise05ScenarioReplayTest` was added alongside
  `Exercise05Test`), and now passes `-DworktreeDir` through to `mvn test`.
- `env.local.sh`: added `CAMUNDA_CLIENT_MODE=self-managed` / `CAMUNDA_CLIENT_AUTH_METHOD=none` for
  java-spring (it uses Spring's own `CAMUNDA_CLIENT_*` binding convention, not `CAMUNDA_AUTH_STRATEGY`
  like the other three).
- `Languages.java`: `repoRoot()` now reads `-DworktreeDir` if set, else falls back to its old
  behavior (parent of `cpt-test`'s own directory) — this is the ONLY Java-side change; the
  directory-scan-and-deploy-all generalization discussed earlier was **not** done — `Exercise05Test`
  still deploys `Payment Process.bpmn` by name via `addResourceFile`. Fine for one BPMN per exercise;
  revisit if an exercise needs multiple deployable files.

## Per-language state on `exercise-05` (as of 2026-07-03)

All 4 languages validated end-to-end (`Exercise05Test` 2/2 + `Exercise05ScenarioReplayTest` 1/1,
against a live runtime + real worker, via `run-exercise.sh`):

| Language    | Auth pattern                                    | Code change needed? |
| ----------- | ------------------------------------------------ | -------------------- |
| csharp      | env-var-first (`Camunda.Orchestration.Sdk 9.*`)   | No — already done    |
| java-spring | `CAMUNDA_CLIENT_*` env vars via Spring auto-config | No — framework handles it; `application.yml` still hardcodes SaaS as the only committed default (works today because env vars override it, but not committed as the default) |
| python      | env-var-first (`web_shop.py`)                     | Yes — **done**, committed locally to `exercise-05` (`05b385b`) |
| js          | env-var-first (`exercise_5.ts`)                   | Yes — **done**, committed locally to `exercise-05` (`1b5e7ed`), plus added missing `package.json`/`tsconfig.json` (there was no npm scaffolding at all) |

Both `exercise-05` commits are **local only, not pushed** — holding per user's request until
reviewed. `js` also needs `--transpile-only` when running `ts-node` because of two pre-existing,
unrelated type errors in `exercise_5.ts` (job-handler return type) — not fixed, out of scope for the
auth change.

## Worker completion — scenario replay, not hand-written happy paths only

Each exercise's `assets/` includes a Camunda Play scenario export (e.g.
`PaymentProcess test scenarios.json`). `ExerciseNNTest` (well, specifically
`Exercise05ScenarioReplayTest` — see below) loads it via the CPT JSON test-case API
(`TestCasesReader` / `CamundaTestCaseRunner`), **strips `COMPLETE_JOB` instructions**, and replays the
rest — so the REAL worker completes jobs, not the test driver.

**Validated end-to-end** for exercise-05, all 4 languages. Not yet tried for any other exercise (no
scenario JSON exists yet for exercises other than 05 — that's the user's own next task, tracked
separately).

Note: `Exercise05Test` (hand-written, 2 `@Test` methods) and `Exercise05ScenarioReplayTest`
(JSON-replay, 1 `@Test` method) currently coexist as separate classes covering overlapping ground —
not yet consolidated. Fine for now; revisit once more exercises are added and the pattern repeats.

### Coverage requirement

**Every service task in an exercise must be covered** by at least one scenario — not just a
happy-path subset. Check by confirming every service task appears in at least one scenario's
`coveredFlowNodes` (Play records this metadata per scenario).

### Incident / BPMN-error paths are exercise-specific, not a general mechanism

Exercise-08 (incidents) and exercise-09 (BPMN errors) each need exactly ONE worker/service task's
failure path tested — hand-written, not derived from Play (Play only records happy-path runs). Do
**not** build a general per-exercise failure-simulation mechanism — considered and explicitly
rejected. The CPT JSON schema's `RESOLVE_INCIDENT` and
`MOCK_JOB_WORKER_THROW_BPMN_ERROR`/`THROW_BPMN_ERROR_FROM_JOB` instruction types are the natural fit
for these two exercises specifically, when built.

## Versioning: alpha replay API requires a version-matched stack

The scenario-replay API (`TestCasesReader`, `CamundaTestCaseRunner`) only exists in
`io.camunda:camunda-process-test-java` / `io.camunda:camunda-process-test-json-test-cases` version
**`8.10.0-alpha3-rc2`** (as of 2026-07-02/03) — not in any 8.7–8.9 stable release, undocumented on
docs.camunda.io.

**Client and broker versions must be kept in lockstep** — confirmed the hard way. Bumping only
`cpt-test/pom.xml`'s `camunda.version` while leaving the Docker runtime on stable `8.9.6` broke
`CamundaAssert...hasCompletedElements(...)` with `400 Bad Request: Request property [filter.$or]
cannot be parsed` (the alpha client sends a filter shape the 8.9.6 REST gateway rejects). Fix: both
`cpt-test/start-runtime.sh` and `cpt-test/run-exercise.sh` pin the SAME `camunda/camunda:
8.10.0-alpha3-rc2` image as the pom's `camunda.version`. **Done, both files updated and verified.**

Re-check this constraint once a stable Camunda release ships the replay API.

Worker-side SDK versions (python `camunda-orchestration-sdk`, C# `Camunda.Orchestration.Sdk`, JS
`@camunda8/orchestration-cluster-api`) are independent of this and unaffected — they talk to the
broker over a stable, long-supported subset of the API, unrelated to the alpha-only `filter.$or`
query CPT's own assertions introduced.

## What's left

The single-exercise flow (`run-exercise.sh` + `WORKTREE=`) is done. What remains is looping it over
every exercise branch automatically:

1. **Write `run-all-exercises.sh`** — not started. Shape:
   ```bash
   start runtime ONCE
   for branch in $(git branch -r | grep 'exercise-'):     # auto-discover
       git worktree remove tmp --force 2>/dev/null || true   # defensive: stale prior run
       git worktree add tmp "$branch"
       WORKTREE=tmp <run the per-language loop that run-exercise.sh already does, but reusing
                     the ALREADY-RUNNING runtime instead of starting/stopping it each time>
       git worktree remove tmp
       collect this branch's row(s) into the aggregate report
   aggregate -> one exercise × language matrix report
   stop runtime
   ```
   `run-exercise.sh` already reuses an already-running runtime via its `runtime_ready` check, so the
   per-branch inner loop can mostly reuse it directly (e.g. `WORKTREE=tmp ./run-exercise.sh`) rather
   than reimplementing worker start/stop — needs the outer aggregation (per-branch report rows →
   one combined table) added on top.
2. **Author scenario JSON + `ExerciseNNTest` for each remaining exercise** — exercise-05 is the only
   one with scenarios today. This is the user's own task, in progress separately.
3. **Exercise-08 / exercise-09 hand-written failure-path tests** (incidents / BPMN errors) — lowest
   priority, deferred until more of the harness is in place.
4. Push the two local-only `exercise-05` commits (JS + python auth fixes) once reviewed.

## Future constraint: this should eventually run as a GitHub Action

Not acted on yet, but worth designing for from the start:

- **No interactive prompts** — `run-exercise.sh`'s preflight already guards on
  `[[ ! -t 0 || YES=1 ]]`; keep this in `run-all-exercises.sh` too.
- **Docker must "just work"** — GitHub-hosted runners ship Docker preinstalled.
- **No reliance on locally-cached tool state** — venvs/`node_modules`/NuGet must install fresh
  (already true: `run-exercise.sh` calls `pip install`/`npm install` explicitly, doesn't assume them).
- **Nothing GUI-driven or requiring a human to start a worker** — already satisfied by
  `run-exercise.sh`'s design.

No CI YAML written yet; this is a constraint to keep satisfied while building the harness, not a
separate task.
