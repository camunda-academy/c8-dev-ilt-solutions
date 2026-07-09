#!/usr/bin/env bash
#
# Runs ONE exercise against every available language. The exercise is determined by the branch
# checked out in WORKTREE (e.g. a branch named "exercise-07" tests Exercise07Test).
# (A future run-all.sh may loop this over all exercises.)
#
# For each available language implementation it:
#   1. (once) starts a local Camunda runtime via Docker
#   2. starts that language's worker in the background, pointed at the runtime (no auth)
#   3. runs the CPT tests against it  (mvn test -Dtest=ExerciseNNTest -Dlang=<lang>)
#   4. stops the worker
# and finally writes a combined Markdown report plus per-language Surefire + coverage reports.
#
# Usage:
#   ./run-exercise.sh                 # run every available language (auto-detected)
#   ./run-exercise.sh python js       # run only the named languages (still skips if unavailable)
#   WORKTREE=../tmp ./run-exercise.sh # worker code + assets/ live in a separate checkout (e.g. a
#                                      # `git worktree` of an exercise-NN branch, when this harness
#                                      # lives on its own branch — see cpt-test/README.md).
#                                      # Defaults to this script's own repo (cpt-test's sibling dir).
#                                      # The exercise under test is read from WORKTREE's checked-out
#                                      # branch name (must match exercise-NN); if that branch has no
#                                      # ExerciseNNTest class yet, the run is skipped with a clear
#                                      # message rather than failing.
#
# Languages with no implementation on this branch (java, java-spring) or whose toolchain/deps are
# not installed are SKIPPED (reported, not failed). One worker runs at a time so they don't compete
# for jobs.
#
set -uo pipefail

# ---- paths -------------------------------------------------------------------
CPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${CPT_DIR}/.." && pwd)"
REQUESTED_WORKTREE="${WORKTREE:-${REPO_ROOT}}"
if ! WORKTREE="$(cd "${REQUESTED_WORKTREE}" 2>/dev/null && pwd)"; then
  echo "[err] WORKTREE (${REQUESTED_WORKTREE}) does not exist. Set WORKTREE=<path> to a" \
       "'git worktree add' checkout of the exercise-NN branch you want to test" \
       "(see cpt-test/README.md)." >&2
  exit 1
fi
RESULTS_DIR="${CPT_DIR}/results"
REPORT_MD="${RESULTS_DIR}/report.md"

# ---- which exercise ------------------------------------------------------------
# Derived from WORKTREE's checked-out branch name (e.g. "exercise-07" -> "07" -> Exercise07Test).
# Override with WORKTREE_BRANCH= when WORKTREE is checked out via `actions/checkout` with a `ref:`
# (leaves a detached HEAD, so `git branch --show-current` returns nothing there).
WORKTREE_BRANCH="${WORKTREE_BRANCH:-$(git -C "${WORKTREE}" branch --show-current 2>/dev/null)}"
EXERCISE_NUM="$(sed -nE 's/^exercise-([0-9]+)$/\1/p' <<<"${WORKTREE_BRANCH}")"
if [[ -z "${EXERCISE_NUM}" ]]; then
  echo "[err] WORKTREE (${WORKTREE}) is not checked out to an 'exercise-NN' branch (got: '${WORKTREE_BRANCH:-<none>}')." >&2
  exit 1
fi
EXERCISE_CLASS="Exercise${EXERCISE_NUM}Test"
EXERCISE_REPLAY_CLASS="Exercise${EXERCISE_NUM}ScenarioReplayTest"
TEST_CLASSES="${EXERCISE_CLASS}"
[[ -f "${CPT_DIR}/src/test/java/com/camunda/training/${EXERCISE_REPLAY_CLASS}.java" ]] \
  && TEST_CLASSES="${EXERCISE_CLASS},${EXERCISE_REPLAY_CLASS}"
if [[ ! -f "${CPT_DIR}/src/test/java/com/camunda/training/${EXERCISE_CLASS}.java" ]]; then
  echo "[err] No ${EXERCISE_CLASS}.java in cpt-test yet — exercise-${EXERCISE_NUM} has no CPT test written." >&2
  exit 1
fi
# JS worker filenames are unpadded (exercise_5.ts, exercise_10.ts — never exercise_05.ts), unlike
# the branch name / Java class name, which keep the leading zero (exercise-05, Exercise05Test).
JS_WORKER_FILE="exercise_$((10#${EXERCISE_NUM})).ts"

# ---- runtime config ----------------------------------------------------------
IMAGE="camunda/camunda:8.10.0-alpha3-rc2"
CONTAINER="cpt-camunda"
REST="http://localhost:8080"
GRPC="http://localhost:26500"

# Python interpreter for the worker. Precedence: explicit PYTHON= > active venv > the project's
# python/.venv (created by preflight if missing) > python3 on PATH. Re-resolved after preflight
# runs (below), since preflight may have just created python/.venv.
resolve_python() {
  if [[ -n "${PYTHON_OVERRIDE:-}" ]]; then
    PYTHON="${PYTHON_OVERRIDE}"
  elif [[ -n "${VIRTUAL_ENV:-}" && -x "${VIRTUAL_ENV}/bin/python" ]]; then
    PYTHON="${VIRTUAL_ENV}/bin/python"
  elif [[ -x "${WORKTREE}/python/.venv/bin/python" ]]; then
    PYTHON="${WORKTREE}/python/.venv/bin/python"
  else
    PYTHON="python3"
  fi
}
PYTHON_OVERRIDE="${PYTHON:-}"
resolve_python

# ---- which languages ---------------------------------------------------------
ALL_LANGS=(python csharp js java-spring)
if [[ $# -gt 0 ]]; then REQUESTED=("$@"); else REQUESTED=("${ALL_LANGS[@]}"); fi

# ---- state for cleanup -------------------------------------------------------
WORKER_PID=""
STARTED_RUNTIME="false"

log()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[skip]\033[0m %s\n' "$*"; }
err()  { printf '\033[1;31m[err]\033[0m %s\n' "$*"; }

stop_worker() {
  if [[ -n "${WORKER_PID}" ]] && kill -0 "${WORKER_PID}" 2>/dev/null; then
    # kill the worker and any children (SDK spawns poll loops)
    pkill -P "${WORKER_PID}" 2>/dev/null || true
    kill "${WORKER_PID}" 2>/dev/null || true
    wait "${WORKER_PID}" 2>/dev/null || true
  fi
  WORKER_PID=""
}

cleanup() {
  stop_worker
  if [[ "${STARTED_RUNTIME}" == "true" ]]; then
    log "Stopping Camunda runtime"
    docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

# ---- runtime helpers ---------------------------------------------------------
runtime_ready() { [[ "$(curl -s -o /dev/null -w '%{http_code}' "${REST}/v2/topology" 2>/dev/null)" == "200" ]]; }

start_runtime() {
  if runtime_ready; then
    log "Reusing Camunda runtime already listening on ${REST}"
    return 0
  fi
  log "Starting Camunda runtime (${IMAGE})"
  docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  docker run -d --name "${CONTAINER}" \
    -p 26500:26500 -p 8080:8080 -p 9600:9600 \
    -e SPRING_PROFILES_ACTIVE=broker \
    -e CAMUNDA_DATA_SECONDARYSTORAGE_TYPE=rdbms \
    -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL='jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1' \
    -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME=sa \
    -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD= \
    -e CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=true \
    -e CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED=false \
    -e ZEEBE_CLOCK_CONTROLLED=true \
    "${IMAGE}" >/dev/null
  STARTED_RUNTIME="true"

  printf 'Waiting for runtime'
  for _ in $(seq 1 60); do
    if runtime_ready; then echo " ready."; return 0; fi
    if ! docker ps --filter "name=${CONTAINER}" --format '{{.Names}}' | grep -q "${CONTAINER}"; then
      echo; err "Runtime container exited during startup. Logs:"; docker logs "${CONTAINER}" 2>&1 | tail -15; return 1
    fi
    printf '.'; sleep 2
  done
  echo; err "Runtime did not become ready in time."; return 1
}

# ---- per-language definitions ------------------------------------------------
# lang_available <lang>  -> 0 if runnable; else sets SKIP_REASON and returns 1
# lang_start     <lang>  -> starts the worker in the background, sets WORKER_PID
SKIP_REASON=""
lang_available() {
  local lang="$1"
  SKIP_REASON=""
  case "${lang}" in
    python)
      [[ -f "${WORKTREE}/python/web_shop.py" ]] || { SKIP_REASON="no python/web_shop.py"; return 1; }
      command -v "${PYTHON}" >/dev/null || { SKIP_REASON="interpreter '${PYTHON}' not found"; return 1; }
      "${PYTHON}" -c "import camunda_orchestration_sdk" >/dev/null 2>&1 \
        || { SKIP_REASON="camunda_orchestration_sdk not importable by ${PYTHON} (pip install it, or set PYTHON=/path/to/venv/python)"; return 1; }
      ;;
    csharp)
      ls "${WORKTREE}"/csharp/*.csproj >/dev/null 2>&1 || { SKIP_REASON="no csharp/*.csproj"; return 1; }
      command -v dotnet >/dev/null || { SKIP_REASON="dotnet not found"; return 1; }
      ;;
    js)
      [[ -f "${WORKTREE}/js/src/workers/${JS_WORKER_FILE}" ]] || { SKIP_REASON="no js/src/workers/${JS_WORKER_FILE}"; return 1; }
      command -v npx >/dev/null || { SKIP_REASON="npx not found"; return 1; }
      [[ -d "${WORKTREE}/js/node_modules" ]] || { SKIP_REASON="js/node_modules missing (run 'npm install' in js/)"; return 1; }
      ;;
    java-spring)
      [[ -f "${WORKTREE}/java-spring/pom.xml" ]] || { SKIP_REASON="no java-spring/pom.xml"; return 1; }
      command -v mvn >/dev/null || { SKIP_REASON="mvn not found"; return 1; }
      ;;
    *) SKIP_REASON="unknown language"; return 1 ;;
  esac
}

lang_start() {
  local lang="$1" logfile="$2"
  case "${lang}" in
    python)
      ( cd "${WORKTREE}/python" && \
        CAMUNDA_REST_ADDRESS="${REST}" CAMUNDA_AUTH_STRATEGY=NONE \
        "${PYTHON}" web_shop.py ) >"${logfile}" 2>&1 &
      ;;
    csharp)
      ( cd "${WORKTREE}/csharp" && \
        CAMUNDA_GRPC_ADDRESS="${GRPC}" CAMUNDA_AUTH_STRATEGY=NONE \
        dotnet run ) >"${logfile}" 2>&1 &
      ;;
    js)
      ( cd "${WORKTREE}/js" && \
        CAMUNDA_REST_ADDRESS="${REST}" CAMUNDA_AUTH_STRATEGY=NONE \
        npx ts-node --transpile-only "src/workers/${JS_WORKER_FILE}" ) >"${logfile}" 2>&1 &
      ;;
    java-spring)
      ( cd "${WORKTREE}/java-spring" && \
        CAMUNDA_CLIENT_MODE=self-managed CAMUNDA_CLIENT_AUTH_METHOD=none \
        mvn -q -B spring-boot:run ) >"${logfile}" 2>&1 &
      ;;
  esac
  WORKER_PID="$!"
}

# ---- report state ------------------------------------------------------------
declare -a R_LANG R_TESTS R_PASS R_FAIL R_SKIP R_STATUS

add_row() { R_LANG+=("$1"); R_TESTS+=("$2"); R_PASS+=("$3"); R_FAIL+=("$4"); R_SKIP+=("$5"); R_STATUS+=("$6"); }

# Sum the Surefire .txt summary line ("Tests run: N, Failures: F, Errors: E, Skipped: S") across
# EVERY test class's report file in the directory (one -Dtest= run may cover several classes).
parse_surefire() {
  local dir="$1" line run fail errs skip
  local total_run=0 total_fail=0 total_skip=0
  while IFS= read -r line; do
    run="$(sed -E 's/.*Tests run: ([0-9]+).*/\1/' <<<"${line}")"
    fail="$(sed -E 's/.*Failures: ([0-9]+).*/\1/' <<<"${line}")"
    errs="$(sed -E 's/.*Errors: ([0-9]+).*/\1/' <<<"${line}")"
    skip="$(sed -E 's/.*Skipped: ([0-9]+).*/\1/' <<<"${line}")"
    total_run=$((total_run + run))
    total_fail=$((total_fail + fail + errs))
    total_skip=$((total_skip + skip))
  done < <(grep -hE 'Tests run: [0-9]+' "${dir}"/*.txt 2>/dev/null)
  echo "${total_run} ${total_fail} ${total_skip}"
}

# ---- main --------------------------------------------------------------------
mkdir -p "${RESULTS_DIR}"

# Preflight (skip with PREFLIGHT=0). Auto-fixes what it safely can (python venv, npm install) —
# see preflight.sh. If it reports problems it couldn't fix, ask before continuing.
#   exit 0 = all good   1 = some worker deps missing   2 = a core dependency missing
if [[ "${PREFLIGHT:-1}" != "0" && -x "${CPT_DIR}/preflight.sh" ]]; then
  PYTHON="${PYTHON}" WORKTREE="${WORKTREE}" "${CPT_DIR}/preflight.sh" "${REQUESTED[@]}"
  pf_rc=$?
  resolve_python  # preflight may have just created python/.venv — pick it up
  echo
  if [[ "${pf_rc}" -ne 0 ]]; then
    if [[ ! -t 0 || "${YES:-0}" == "1" ]]; then
      # Non-interactive (no TTY) or YES=1: don't hang — proceed, noting the findings.
      warn "Preflight found issues (see above) — continuing (non-interactive / YES=1)."
    else
      reply=""
      read -r -p "Preflight found issues (see above). Continue anyway? [y/N] " reply
      if [[ ! "${reply}" =~ ^[Yy]$ ]]; then
        echo "Aborted. Fix the items above (or re-run with YES=1 to skip this prompt)."
        exit 1
      fi
    fi
  fi
fi

start_runtime || exit 1

OVERALL_RC=0
for lang in "${REQUESTED[@]}"; do
  if ! printf '%s\n' "${ALL_LANGS[@]}" | grep -qx "${lang}"; then
    warn "${lang}: not a known language (expected one of: ${ALL_LANGS[*]})"; add_row "${lang}" "-" "-" "-" "-" "SKIPPED (unknown)"; continue
  fi
  if ! lang_available "${lang}"; then
    warn "${lang}: ${SKIP_REASON}"; add_row "${lang}" "-" "-" "-" "-" "SKIPPED — ${SKIP_REASON}"; continue
  fi

  LANG_RESULTS="${RESULTS_DIR}/${lang}"
  WORKER_LOG="${LANG_RESULTS}/worker.log"
  mkdir -p "${LANG_RESULTS}"

  log "[${lang}] starting worker"
  lang_start "${lang}" "${WORKER_LOG}"

  # Give the worker time to connect; bail early if it died (e.g. missing deps at runtime).
  sleep 8
  if ! kill -0 "${WORKER_PID}" 2>/dev/null; then
    err "[${lang}] worker exited during startup — see ${WORKER_LOG}"; tail -5 "${WORKER_LOG}" 2>/dev/null
    # Pull the first non-empty log line as a short hint (usually the actual error summary);
    # strip any '|' so it can't break the Markdown table.
    hint="$(grep -v '^[[:space:]]*$' "${WORKER_LOG}" 2>/dev/null | head -1 | tr '|' '/' | cut -c1-80)"
    add_row "${lang}" "-" "-" "-" "-" "SKIPPED — worker failed to start: ${hint:-see worker.log}"; WORKER_PID=""; continue
  fi

  log "[${lang}] running tests"
  ( cd "${CPT_DIR}" && mvn -B test "-Dtest=${TEST_CLASSES}" \
      "-Dlang=${lang}" "-DworktreeDir=${WORKTREE}" ) \
    >"${LANG_RESULTS}/maven.log" 2>&1
  MVN_RC=$?

  stop_worker

  # Keep this language's surefire reports + coverage report.
  cp -R "${CPT_DIR}/target/surefire-reports/." "${LANG_RESULTS}/surefire-reports/" 2>/dev/null || true
  [[ -d "${CPT_DIR}/target/coverage-report" ]] && cp -R "${CPT_DIR}/target/coverage-report" "${LANG_RESULTS}/coverage-report" 2>/dev/null || true

  read -r tests failed skipped < <(parse_surefire "${CPT_DIR}/target/surefire-reports")
  passed=$(( tests - failed - skipped ))
  if [[ "${MVN_RC}" -eq 0 && "${failed}" -eq 0 ]]; then status="PASSED"; else status="FAILED"; OVERALL_RC=1; fi
  add_row "${lang}" "${tests}" "${passed}" "${failed}" "${skipped}" "${status}"
  log "[${lang}] ${status} (tests=${tests} passed=${passed} failed=${failed} skipped=${skipped})"
done

# ---- write report ------------------------------------------------------------
{
  echo "# CPT test report"
  echo
  echo "_Generated: $(date '+%Y-%m-%d %H:%M:%S')_  •  Process: \`PaymentProcess\`  •  Runtime: \`${IMAGE}\`"
  echo
  echo "| Language | Tests | Passed | Failed | Skipped | Status |"
  echo "| -------- | ----- | ------ | ------ | ------- | ------ |"
  for i in "${!R_LANG[@]}"; do
    printf '| %s | %s | %s | %s | %s | %s |\n' \
      "${R_LANG[$i]}" "${R_TESTS[$i]}" "${R_PASS[$i]}" "${R_FAIL[$i]}" "${R_SKIP[$i]}" "${R_STATUS[$i]}"
  done
  echo
  echo "Per-language details (Surefire reports, worker logs, CPT coverage HTML) are under"
  echo "\`cpt-test/results/<language>/\`."
} > "${REPORT_MD}"

log "Report written to ${REPORT_MD}"
echo
cat "${REPORT_MD}"
echo

exit "${OVERALL_RC}"
