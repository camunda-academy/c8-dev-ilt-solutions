# CPT test harness

This folder contains the instructor-facing Camunda Process Test harness for the exercise branches.
Its job is simple: whenever an exercise branch changes, run an end-to-end verification of that
exercise against the real workers in each available language implementation.

The tests do not mock workers. They start a local Camunda runtime, start one worker implementation
at a time, execute the CPT suite, and report whether that language still behaves correctly.

There are two ways this harness is used:

- Automatic: GitHub Actions runs it when an `exercise-NN` branch is updated.
- Manual: a trainer runs it locally to verify a branch or investigate a failure.

## What this is for

Use this harness when you want to:

- verify that a change on an `exercise-NN` branch did not break the exercise
- replay the same test logic across `python`, `csharp`, `js`, and `java-spring` when available
- inspect logs and reports when a GitHub Action fails

This branch is not part of the learner material. It is trainer and maintainer tooling.

## Branch model

- The `integration-test` branch owns `cpt-test/` and the CPT Java test suite.
- Each `exercise-NN` branch owns the worker code and `assets/` for that exercise.
- The harness tests one exercise branch at a time, either locally through `git worktree` or in CI.

## What is tested

For each exercise, the suite combines:

- `ExerciseNNTest`: focused assertions for the happy path and the exercise-specific behavior
- `ExerciseNNScenarioReplayTest`: replay of recorded Camunda Play scenarios, where available

Coverage currently exists for exercises 05 through 12.

| Exercise | Main focus |
| --- | --- |
| 05 | Payment process happy path |
| 06 | Script task fee calculation |
| 07 | Message correlation across order and payment processes |
| 08 | Incident handling |
| 09 | BPMN error handling |
| 10 | User task recovery flow |
| 11 | Connector-driven product lookup with test-side stubbing |
| 12 | DMN-based discount decision |

If a language has no implementation on the selected branch, that language is skipped and reported
as skipped, not failed.

## Automatic execution in GitHub Actions

Every push to an `exercise-NN` branch triggers the GitHub Action stub on that branch, which then
calls the reusable workflow stored on `integration-test`.

Expected outcome after a branch update:

1. GitHub Actions starts a test run for that branch.
2. The workflow checks out both the harness and the updated exercise branch.
3. The workflow runs `cpt-test/run-exercise.sh` for that branch.
4. A summary report and per-language logs are uploaded as build artifacts.

Important details:

- The workflow tests only the branch that changed.
- Documentation-only changes do not trigger the workflow.
- If a language implementation or toolchain is missing, that language is skipped.
- A failed run usually means either a real regression in the exercise or an environment problem in
  one language implementation.

No local Docker, Java, Maven, Python, Node.js, or .NET setup is needed for this mode because the
workflow provisions its own environment in GitHub Actions.

## Manual local execution

Use this path only when a trainer wants to run the tests personally, for example to reproduce a CI
failure or validate a branch before pushing.

### Local prerequisites

- Docker
- Java 17+ and Maven
- The language toolchains you want to execute locally:
  - Python 3
  - .NET SDK / runtime for the C# project
  - Node.js for the JS workers

The preflight step auto-creates the Python virtual environment and runs `npm install` when needed.

### Running locally

From the repository root:

```bash
git worktree add tmp exercise-05
cd cpt-test
WORKTREE=../tmp ./run-exercise.sh
cd ..
git worktree remove tmp
```

Useful variants:

- Run only selected languages: `WORKTREE=../tmp ./run-exercise.sh python js`
- Test another exercise: point the worktree at a different `exercise-NN` branch
- Report only missing dependencies: `NO_AUTOFIX=1 WORKTREE=../tmp ./preflight.sh`

## Where to look after a run

- Summary report: `cpt-test/results/report.md`
- Per-language logs and Surefire reports: `cpt-test/results/<language>/`
- CPT coverage report: `target/coverage-report/report.html`

In GitHub Actions, download the `cpt-results-exercise-NN` artifact for the same content.

## CI wiring

- The real workflow logic lives in `.github/workflows/cpt-reusable.yml` on `integration-test`.
- Each exercise branch must contain the small `.github/workflows/test.yml` stub that calls it.

If an exercise branch is missing the stub, pushes on that branch will not trigger any tests.

## Adding a new exercise

For a new `exercise-NN` branch:

1. Copy `.github/workflows/test.yml` onto that branch unchanged.
2. Add `ExerciseNNTest.java` and, if applicable, `ExerciseNNScenarioReplayTest.java` on `integration-test`.
3. Add the new test class to `AllExercisesTestSuite.java`.
4. Push the exercise branch and confirm that a GitHub Action run appears for it.

## Current version constraint

The scenario replay API used by these tests currently depends on CPT `8.10.0-alpha3-rc2`, and the
Docker runtime image is intentionally pinned to the same version in the scripts. If that version is
changed, client and runtime must stay aligned.
