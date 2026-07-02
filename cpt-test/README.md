# CPT tests — Payment Process

Camunda Process Test (CPT) suite that verifies the `PaymentProcess` solutions end-to-end against the
**real** job workers (`python`, `csharp`, `js`) — no mocks. CPT runs in `remote` mode against a local
Camunda runtime; you (or the script) start one worker that connects to the same runtime.

## Run everything (one command)

```bash
cd cpt-test
./run-exercise.sh                 # all available languages
./run-exercise.sh python js       # only these
```

`run-exercise.sh` first runs a **preflight** that reports missing dependencies and how to fix them. If
it finds problems, it asks whether to continue (default: no). Run it standalone with `./preflight.sh`;
skip the check with `PREFLIGHT=0`; answer the prompt automatically with `YES=1` (also used when there's
no terminal, e.g. CI).

It then starts the runtime, and per language: starts its worker → runs the tests → stops the worker →
tears the runtime down on exit. Missing languages (`java`, `java-spring`) or toolchains are
**skipped**, not failed. Output in `results/`:

- `results/report.md` — summary table
- `results/<lang>/` — Surefire reports, `coverage-report/`, `worker.log`

## Run manually

**Prerequisites:** Docker, Java 21 + Maven, and the worker toolchain (Python 3 / .NET 8 / Node.js).

```bash
# 1. Runtime — camunda/camunda:8.9.6, no-auth, ports 26500 (gRPC) / 8080 (REST) / 9600 (monitoring)
./start-runtime.sh                       # stop with: ./start-runtime.sh stop

# 2. ONE worker (env vars point it at the runtime, no auth; otherwise it uses its own SaaS config)
source env.local.sh
cd python && python3 web_shop.py                          # see Python setup below
# or:  cd csharp && dotnet run                             # reads CAMUNDA_GRPC_ADDRESS
# or:  cd js && npx ts-node src/workers/exercise_5.ts

# 3. Tests — -Dlang must match the worker you started
cd cpt-test && mvn test -Dtest=Exercise05Test -Dlang=python
```

Run one worker at a time (they share the same job types). `-Dlang` naming an unavailable language
skips, not fails. CPT coverage report: `target/coverage-report/report.html`.

> The worker terminal may log `HTTP 500 … "Cluster was purged"` — normal: CPT wipes the runtime
> between tests, cancelling the worker's long-poll; the SDK retries. The result is in
> `target/surefire-reports` (`Failures: 0, Errors: 0`).

### Python worker setup

The Python SDK can't be installed system-wide on a Homebrew/Debian Python (PEP 668). Use a venv in
`python/` — both scripts auto-detect `python/.venv`, so no `PYTHON=` is needed afterward:

```bash
python3 -m venv python/.venv
source python/.venv/bin/activate
pip install -r python/requirements.txt
```

## Scenarios

| Scenario               | orderTotal | customerCredit | Path                                |
| ---------------------- | ---------- | -------------- | ----------------------------------- |
| `pay-with-credit-card` | 45.99      | 20             | deduct credit → charge card → done  |
| `pay-with-credit-only` | 45.99      | 100            | deduct credit → (skip card) → done  |

## Notes

- BPMN source is `assets/Payment Process.bpmn`; the deployed copy is
  `src/test/resources/PaymentProcess.bpmn` — update both if it changes.
- Scenarios are coded as `@Test` methods (not driven from the JSON file): the tests must exercise the
  real workers, so they only create instances and assert. The CPT JSON runner would complete jobs
  itself (bypassing the workers) and uses an incompatible schema. The JSON file is reference only.
- Bump `camunda.version` in `pom.xml` (and the image tag in `start-runtime.sh`) to track a newer release.
