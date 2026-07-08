# CPT tests — Payment Process

Camunda Process Test (CPT) suite that verifies the `PaymentProcess` solutions end-to-end against the
**real** job workers (`python`, `csharp`, `js`, `java-spring`) — no mocks. One command boots a local
Camunda runtime, runs every available worker against it in turn, and reports pass/fail per language.

This harness lives on its own branch (`integration-test`). Worker source and `assets/` (BPMN +
scenario JSON) live on the exercise branch instead (e.g. `exercise-05`) and are pulled in via
`git worktree` — see `ALL-EXERCISES-PLAN.md` for the full design and why.

## Prerequisites

- Docker (running)
- Java 21 + Maven
- Whichever worker toolchains you want tested: Python 3, .NET 8 SDK, Node.js — `run-exercise.sh`
  skips any language whose toolchain or worker code isn't available, rather than failing.

No manual per-worktree setup is needed beyond that: the first run against a fresh `git worktree`
checkout creates the Python venv and runs `npm install` automatically (preflight does this — set
`NO_AUTOFIX=1` to just report what's missing instead).

## Run

`git worktree add` creates its directory relative to wherever you run it — the commands below only
work if you're at the repo root when you run them. If in doubt, run `git rev-parse --show-toplevel`
first and `cd` there.

```bash
cd "$(git rev-parse --show-toplevel)"
git worktree add tmp exercise-05      # once, to pull in that exercise's worker code + assets/

cd cpt-test
WORKTREE=../tmp ./run-exercise.sh     # runs every available language

cd ..
git worktree remove tmp               # when done
```

To test a specific exercise, point `git worktree` at that exercise's branch instead of `exercise-05`.
To test only some languages: `WORKTREE=../tmp ./run-exercise.sh python js`.

If `WORKTREE` doesn't point at an existing directory, or that directory isn't checked out to an
`exercise-NN` branch, `run-exercise.sh` exits immediately with an explanatory message rather than
running against the wrong thing.

## Expected output

```
Preflight — CPT test dependencies

Core (required):
  ✓ Docker running
  ✓ Java 21
  ✓ Maven 3.9.14
  ✓ curl

Workers (each optional — missing ones are skipped by run-exercise.sh):
python
  ✓ .../python/.venv/bin/python + camunda_orchestration_sdk
csharp
  ✓ dotnet (runtimes: 8 9 10; project target: net8.0)
js
  ✓ Node.js + js/node_modules
java-spring
  ✓ mvn (java-spring/pom.xml present)

All dependencies present.

==> Starting Camunda runtime (camunda/camunda:8.10.0-alpha3-rc2)
Waiting for runtime... ready.

==> [python] starting worker
==> [python] running tests
==> [python] PASSED (tests=3 passed=3 failed=0 skipped=0)

==> [csharp] starting worker
==> [csharp] running tests
==> [csharp] PASSED (tests=3 passed=3 failed=0 skipped=0)

==> [js] starting worker
==> [js] running tests
==> [js] PASSED (tests=3 passed=3 failed=0 skipped=0)

==> [java-spring] starting worker
==> [java-spring] running tests
==> [java-spring] PASSED (tests=3 passed=3 failed=0 skipped=0)

==> Report written to cpt-test/results/report.md

# CPT test report

_Generated: 2026-07-03 12:31:19_  •  Process: `PaymentProcess`  •  Runtime: `camunda/camunda:8.10.0-alpha3-rc2`

| Language    | Tests | Passed | Failed | Skipped | Status |
| ----------- | ----- | ------ | ------ | ------- | ------ |
| python      | 3     | 3      | 0      | 0       | PASSED |
| csharp      | 3     | 3      | 0      | 0       | PASSED |
| js          | 3     | 3      | 0      | 0       | PASSED |
| java-spring | 3     | 3      | 0      | 0       | PASSED |

==> Stopping Camunda runtime
```

A language reports `SKIPPED — <reason>` instead of `PASSED`/`FAILED` when its toolchain or worker
code isn't available — that's expected, not an error. Full detail (Surefire reports, worker logs,
coverage HTML) lands under `cpt-test/results/<language>/`; the summary table above is also written to
`cpt-test/results/report.md`.

If preflight finds a problem, it asks whether to continue (default: no) — set `YES=1` to skip that
prompt (e.g. in CI), or `PREFLIGHT=0` to skip the check entirely.

## Also runs automatically in CI

Every push to an `exercise-NN` branch (or `main`) runs this same suite via GitHub Actions —
filter the Actions tab by branch to see a given exercise's results, and download the
`cpt-results-exercise-NN` artifact from a run for the full report/logs, same content as a local
`results/` directory. Doc-only changes (`**.md`) don't trigger a run.

The actual CI logic (`.github/workflows/cpt-reusable.yml`) lives only on this branch; every
exercise branch just has a tiny stub (`.github/workflows/test.yml`) that calls it. See
`ALL-EXERCISES-PLAN.md`'s "Adding a new exercise branch" section before creating a new exercise —
forgetting to copy that stub is the most common way to end up with a branch that silently never
runs any tests.
