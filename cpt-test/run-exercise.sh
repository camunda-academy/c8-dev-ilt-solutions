#!/usr/bin/env bash
#
# Runs ONE exercise (Exercise05) against every available language.
# (A future run-all.sh may loop this over all exercises.)
#
# For each available language implementation it:
#   1. (once) starts a local Camunda runtime via Docker
#   2. starts that language's worker in the background, pointed at the runtime (no auth)
#   3. runs the CPT tests against it  (mvn test -Dtest=Exercise05Test -Dlang=<lang>)
#   4. stops the worker
# and finally writes a combined Markdown report plus per-language Surefire + coverage reports.
#
# Usage:
#   ./run-exercise.sh                 # run every available language (auto-detected)
#   ./run-exercise.sh python js       # run only the named languages (still skips if unavailable)
#
# Languages with no implementation on this branch (java, java-spring) or whose toolchain/deps are
# not installed are SKIPPED (reported, not failed). One worker runs at a time so they don't compete
# for jobs.
#
set -uo pipefail

# ---- paths -------------------------------------------------------------------
CPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${CPT_DIR}/.." && pwd)"
RESULTS_DIR="${CPT_DIR}/results"
REPORT_MD="${RESULTS_DIR}/report.md"

# ---- runtime config ----------------------------------------------------------
IMAGE="camunda/camunda:8.10.0-alpha3-rc2"
CONTAINER="cpt-camunda"
REST="http://localhost:8080"
GRPC="http://localhost:26500"

# Python interpreter for the worker. Precedence: explicit PYTHON= > active venv > the project's
# python/.venv (created per the preflight's suggestion) > python3 on PATH.
if [[ -z "${PYTHON:-}" ]]; then
  if [[ -n "${VIRTUAL_ENV:-}" && -x "${VIRTUAL_ENV}/bin/python" ]]; then
    PYTHON="${VIRTUAL_ENV}/bin/python"
  elif [[ -x "${REPO_ROOT}/python/.venv/bin/python" ]]; then
    PYTHON="${REPO_ROOT}/python/.venv/bin/python"
  else
    PYTHON="python3"
  fi
fi

# ---- which languages ---------------------------------------------------------
ALL_LANGS=(python csharp js)
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
    -e SPRING_PROFILES_ACTIVE=broker,consolidated-auth,operate,tasklist,identity \
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
      [[ -f "${REPO_ROOT}/python/web_shop.py" ]] || { SKIP_REASON="no python/web_shop.py"; return 1; }
      command -v "${PYTHON}" >/dev/null || { SKIP_REASON="interpreter '${PYTHON}' not found"; return 1; }
      "${PYTHON}" -c "import camunda_orchestration_sdk" >/dev/null 2>&1 \
        || { SKIP_REASON="camunda_orchestration_sdk not importable by ${PYTHON} (pip install it, or set PYTHON=/path/to/venv/python)"; return 1; }
      ;;
    csharp)
      ls "${REPO_ROOT}"/csharp/*.csproj >/dev/null 2>&1 || { SKIP_REASON="no csharp/*.csproj"; return 1; }
      command -v dotnet >/dev/null || { SKIP_REASON="dotnet not found"; return 1; }
      ;;
    js)
      [[ -f "${REPO_ROOT}/js/src/workers/exercise_5.ts" ]] || { SKIP_REASON="no js worker source"; return 1; }
      command -v npx >/dev/null || { SKIP_REASON="npx not found"; return 1; }
      [[ -d "${REPO_ROOT}/js/node_modules" ]] || { SKIP_REASON="js/node_modules missing (run 'npm install' in js/)"; return 1; }
      ;;
    *) SKIP_REASON="unknown language"; return 1 ;;
  esac
}

lang_start() {
  local lang="$1" logfile="$2"
  case "${lang}" in
    python)
      ( cd "${REPO_ROOT}/python" && \
        CAMUNDA_REST_ADDRESS="${REST}" CAMUNDA_AUTH_STRATEGY=NONE \
        "${PYTHON}" web_shop.py ) >"${logfile}" 2>&1 &
      ;;
    csharp)
      ( cd "${REPO_ROOT}/csharp" && \
        CAMUNDA_GRPC_ADDRESS="${GRPC}" CAMUNDA_AUTH_STRATEGY=NONE \
        dotnet run ) >"${logfile}" 2>&1 &
      ;;
    js)
      ( cd "${REPO_ROOT}/js" && \
        CAMUNDA_REST_ADDRESS="${REST}" CAMUNDA_AUTH_STRATEGY=NONE \
        npx ts-node src/workers/exercise_5.ts ) >"${logfile}" 2>&1 &
      ;;
  esac
  WORKER_PID="$!"
}

# ---- report state ------------------------------------------------------------
declare -a R_LANG R_TESTS R_PASS R_FAIL R_SKIP R_STATUS

add_row() { R_LANG+=("$1"); R_TESTS+=("$2"); R_PASS+=("$3"); R_FAIL+=("$4"); R_SKIP+=("$5"); R_STATUS+=("$6"); }

# Parse the Surefire .txt summary line: "Tests run: N, Failures: F, Errors: E, Skipped: S"
parse_surefire() {
  local dir="$1" line
  line="$(grep -hE 'Tests run: [0-9]+' "${dir}"/*.txt 2>/dev/null | tail -1)"
  [[ -z "${line}" ]] && { echo "0 0 0 0"; return; }
  local run fail errs skip
  run="$(sed -E 's/.*Tests run: ([0-9]+).*/\1/' <<<"${line}")"
  fail="$(sed -E 's/.*Failures: ([0-9]+).*/\1/' <<<"${line}")"
  errs="$(sed -E 's/.*Errors: ([0-9]+).*/\1/' <<<"${line}")"
  skip="$(sed -E 's/.*Skipped: ([0-9]+).*/\1/' <<<"${line}")"
  echo "${run} $((fail + errs)) ${skip}"
}

# ---- main --------------------------------------------------------------------
mkdir -p "${RESULTS_DIR}"

# Preflight (skip with PREFLIGHT=0). If it reports problems, ask before continuing.
#   exit 0 = all good   1 = some worker deps missing   2 = a core dependency missing
if [[ "${PREFLIGHT:-1}" != "0" && -x "${CPT_DIR}/preflight.sh" ]]; then
  PYTHON="${PYTHON}" "${CPT_DIR}/preflight.sh" "${REQUESTED[@]}"
  pf_rc=$?
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
  ( cd "${CPT_DIR}" && mvn -B test -Dtest=Exercise05Test "-Dlang=${lang}" ) \
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
