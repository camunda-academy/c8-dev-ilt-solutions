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

## GitHub Actions: built, per-branch (not batched)

Every push to an exercise branch tests ONLY that branch — not a fan-out across all of them (rarely
needed in practice; see "Running all exercises" above for the manual batch option when it is).

**How it's wired:**
- `.github/workflows/cpt-reusable.yml` lives ONLY on `integration-test`. It's the real logic: checks
  out `integration-test` (harness) and the triggering branch (worker code) into `harness/` and
  `worktree/` side by side, sets up all 4 language toolchains with dependency caching, runs
  `run-exercise.sh`, uploads `results/` as a build artifact.
- `.github/workflows/test.yml` is a ~10-line stub, byte-identical on `main` and every
  `exercise-NN` branch. It triggers on push (`paths-ignore: ['**.md']`, so doc-only changes don't
  run anything) and calls the reusable workflow with `exercise-branch: ${{ github.ref_name }}`.

**Editing test behavior:** change `cpt-reusable.yml` once, on `integration-test`, and push — every
exercise branch picks it up automatically next time it's triggered (the stub always resolves
`@integration-test` fresh at call time). Never edit the stub itself unless the calling convention
changes (e.g. a new required input).

### Adding a new exercise branch — do this or nothing will run

**The #1 way to break CI silently: forgetting the stub.** A branch with no
`.github/workflows/test.yml` produces zero signal on push — no failure, no email, nothing. This
happened once already (exercise-06, 2026-07-08) before the stub was rolled out everywhere.

Checklist for exercise-NN:
1. Branch it from `main` (or the previous exercise), as usual.
2. Copy `.github/workflows/test.yml` onto it, unmodified, from any existing exercise branch or
   `main`. This file never has branch-specific content — if you're tempted to edit it, don't;
   put that logic in `cpt-reusable.yml` on `integration-test` instead.
3. On `integration-test`: write `ExerciseNNTest.java` + `ExerciseNNScenarioReplayTest.java`, add
   both to `AllExercisesTestSuite.java`.
4. Push the exercise branch. Check the Actions tab, filter by branch — confirm a run actually
   appears. If it doesn't, re-check step 2 first.
5. Push `integration-test`. This does NOT automatically re-test exercise-NN — the reusable workflow
   only runs when triggered by a push to the exercise branch itself, or manually.

**Known gotcha when testing the stub itself:** a commit with zero file changes (e.g.
`git commit --allow-empty`) does not reliably trigger `on: push` — GitHub's `paths-ignore`
evaluation appears to treat a diff-free commit as matching nothing. Use a real, trivial content
change (e.g. add a comment) instead of an empty commit when you need to force a fresh run.

**Known gotcha inside the reusable workflow:** inside a `workflow_call`-invoked workflow, the
default `github.repository`/`github.ref` context reflects the CALLER (whichever branch triggered
the stub), not `integration-test` itself. The harness checkout step in `cpt-reusable.yml` pins
`repository:`/`ref:` explicitly for exactly this reason — don't remove those, or the checkout
silently lands on the wrong branch (surfaces as `setup-java` reporting "no file matched
[harness/cpt-test/pom.xml]", since the exercise branch has no `cpt-test/` at all).

**Also required:** `run-exercise.sh` derives which exercise to test from
`git branch --show-current` on `WORKTREE`, which returns empty under CI (`actions/checkout` leaves
a detached HEAD). The reusable workflow passes `WORKTREE_BRANCH` as an env var to work around
this — if you ever call `run-exercise.sh` from a new CI context, keep setting `WORKTREE_BRANCH`
explicitly.

### Cost

Well within the private-repo free tier (2,000 min/month) at today's scale (8-12 exercises, tested
one branch per push, not batched). Per-language-run: ~90-180s cold, ~40-70s with the `~/.m2`/
`node_modules`/pip/NuGet caches warm. Dependency install (npm/pip/dotnet/mvn) dominates the cost,
not the Docker image pull (~30-90s once per job) or actual test execution (5-12s). Revisit if the
exercise count or push frequency grows a lot — see the git history of this file (pre-simplification
version) for the original per-exercise-batch cost math if that's ever needed again.
