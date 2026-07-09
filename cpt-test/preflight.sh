#!/usr/bin/env bash
#
# Preflight check for the CPT test runner.
#
# Reports which dependencies are present. For per-worktree setup it can fix itself automatically
# (python venv + pip install, npm install) — those are just "materialize this worktree's
# dependencies" and safe to (re)run any time. Anything it can't safely fix (missing Docker/Java/
# Maven/dotnet/Node itself, wrong .NET runtime) is reported with the command to fix it, and
# run-exercise.sh treats that as informational, never fatal.
#
# Usage:
#   ./preflight.sh            # check everything, auto-fixing what it can
#   ./preflight.sh python js  # check core tools + only these languages
#   WORKTREE=../tmp ./preflight.sh   # worker code lives in a separate checkout — see run-exercise.sh
#   NO_AUTOFIX=1 ./preflight.sh      # report only, don't install anything (old behavior)
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKTREE="$(cd "${WORKTREE:-${REPO_ROOT}}" && pwd)"

# Same Python resolution as run-exercise.sh: PYTHON= > active venv > python/.venv > python3.
if [[ -z "${PYTHON:-}" ]]; then
  if [[ -n "${VIRTUAL_ENV:-}" && -x "${VIRTUAL_ENV}/bin/python" ]]; then PYTHON="${VIRTUAL_ENV}/bin/python"
  elif [[ -x "${WORKTREE}/python/.venv/bin/python" ]]; then PYTHON="${WORKTREE}/python/.venv/bin/python"
  else PYTHON="python3"; fi
fi

GREEN=$'\033[0;32m'; RED=$'\033[0;31m'; YELLOW=$'\033[1;33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

CORE_MISSING=0
WORKER_MISSING=0
ok()   { printf '  %s✓%s %s\n' "${GREEN}" "${RESET}" "$1"; }
# bad <message> <fix>  — records a worker-level finding (use CORE_MISSING=1 for core tools).
bad()  { printf '  %s✗%s %s\n      %s↳ fix:%s %s\n' "${RED}" "${RESET}" "$1" "${YELLOW}" "${RESET}" "$2"; WORKER_MISSING=1; }

ALL_LANGS=(python csharp js java java-spring)
if [[ $# -gt 0 ]]; then LANGS=("$@"); else LANGS=("${ALL_LANGS[@]}"); fi

printf '%sPreflight — CPT test dependencies%s\n\n' "${BOLD}" "${RESET}"

# ---- core tools (needed regardless of language) ------------------------------
printf '%sCore (required):%s\n' "${BOLD}" "${RESET}"

if command -v docker >/dev/null 2>&1; then
  if docker info >/dev/null 2>&1; then ok "Docker running"
  else bad "Docker installed but not running" "start Docker Desktop / the Docker daemon"; CORE_MISSING=1; fi
else bad "Docker not found" "install Docker Desktop: https://www.docker.com/products/docker-desktop/"; CORE_MISSING=1; fi

if command -v java >/dev/null 2>&1; then
  jver="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  if [[ "${jver:-0}" -ge 17 ]]; then ok "Java ${jver}"
  else bad "Java ${jver} (need 17+)" "install a JDK 21, e.g. 'brew install temurin@21'"; CORE_MISSING=1; fi
else bad "Java not found" "install a JDK 21, e.g. 'brew install temurin@21'"; CORE_MISSING=1; fi

if command -v mvn >/dev/null 2>&1; then ok "Maven $(mvn -v 2>/dev/null | sed -nE 's/Apache Maven ([0-9.]+).*/\1/p' | head -1)"
else bad "Maven not found" "install Maven, e.g. 'brew install maven'"; CORE_MISSING=1; fi

command -v curl >/dev/null 2>&1 && ok "curl" || { bad "curl not found" "install curl"; CORE_MISSING=1; }

# ---- per-language workers (optional; skipped if absent) ----------------------
printf '\n%sWorkers (each optional — missing ones are skipped by run-exercise.sh):%s\n' "${BOLD}" "${RESET}"

check_python() {
  printf '%spython%s\n' "${BOLD}" "${RESET}"
  if [[ ! -f "${WORKTREE}/python/web_shop.py" ]]; then printf '  %s—%s no implementation in python/\n' "${YELLOW}" "${RESET}"; return; fi
  if ! command -v python3 >/dev/null 2>&1; then bad "python3 not found" "install Python 3"; return; fi
  if "${PYTHON}" -c "import camunda_orchestration_sdk" >/dev/null 2>&1; then
    ok "${PYTHON} + camunda_orchestration_sdk"
    return
  fi
  if [[ "${NO_AUTOFIX:-0}" == "1" ]]; then
    local fix
    if [[ -x "${WORKTREE}/python/.venv/bin/python" ]]; then
      fix="run with: PYTHON=${WORKTREE}/python/.venv/bin/python ./run-exercise.sh   (or 'source ${WORKTREE}/python/.venv/bin/activate' first)"
    else
      fix="python3 -m venv ${WORKTREE}/python/.venv && source ${WORKTREE}/python/.venv/bin/activate && pip install -r ${WORKTREE}/python/requirements.txt"
    fi
    bad "camunda_orchestration_sdk not importable by ${PYTHON}" "${fix}"
    return
  fi
  # Auto-fix: create/reuse a venv (Homebrew/Debian Python is PEP 668 'externally managed', so a
  # plain system-wide pip install is blocked) and install the worker's requirements into it.
  [[ -x "${WORKTREE}/python/.venv/bin/python" ]] || python3 -m venv "${WORKTREE}/python/.venv" >/dev/null 2>&1
  if "${WORKTREE}/python/.venv/bin/pip" install -q -r "${WORKTREE}/python/requirements.txt" >/dev/null 2>&1; then
    PYTHON="${WORKTREE}/python/.venv/bin/python"
    ok "${PYTHON} + camunda_orchestration_sdk (installed into python/.venv)"
  else
    bad "camunda_orchestration_sdk not importable by ${PYTHON}, and auto-install into python/.venv failed" \
        "python3 -m venv ${WORKTREE}/python/.venv && source ${WORKTREE}/python/.venv/bin/activate && pip install -r ${WORKTREE}/python/requirements.txt"
  fi
}

check_csharp() {
  printf '%scsharp%s\n' "${BOLD}" "${RESET}"
  local csproj; csproj="$(ls "${WORKTREE}"/csharp/*.csproj 2>/dev/null | head -1)"
  if [[ -z "${csproj}" ]]; then printf '  %s—%s no implementation in csharp/\n' "${YELLOW}" "${RESET}"; return; fi
  if ! command -v dotnet >/dev/null 2>&1; then bad "dotnet not found" "install the .NET SDK: https://dotnet.microsoft.com/download"; return; fi
  # Compare the project's target framework to the installed runtimes.
  local tfm major runtimes
  tfm="$(sed -nE 's@.*<TargetFramework>net([0-9]+)\.0</TargetFramework>.*@\1@p' "${csproj}" | head -1)"
  runtimes="$(dotnet --list-runtimes 2>/dev/null | sed -nE 's/Microsoft\.NETCore\.App ([0-9]+)\..*/\1/p' | sort -u | tr '\n' ' ')"
  if [[ -n "${tfm}" ]] && ! grep -qw "${tfm}" <<<"${runtimes}"; then
    bad "project targets net${tfm}.0 but installed .NET runtimes are: ${runtimes:-none}" \
        "install the .NET ${tfm} runtime, or change <TargetFramework> in $(basename "${csproj}") to a net version you have"
  else
    ok "dotnet (runtimes: ${runtimes:-?}; project target: net${tfm:-?}.0)"
  fi
}

check_js() {
  printf '%sjs%s\n' "${BOLD}" "${RESET}"
  compgen -G "${WORKTREE}/js/src/workers/exercise_*.ts" >/dev/null 2>&1 \
    || { printf '  %s—%s no implementation in js/\n' "${YELLOW}" "${RESET}"; return; }
  if ! command -v npx >/dev/null 2>&1; then bad "npx/Node.js not found" "install Node.js: https://nodejs.org/"; return; fi
  if [[ -d "${WORKTREE}/js/node_modules" ]]; then ok "Node.js + js/node_modules"; return; fi
  if [[ "${NO_AUTOFIX:-0}" == "1" ]]; then
    bad "js/node_modules missing" "cd ${WORKTREE}/js && npm install"
    return
  fi
  # Auto-fix: install once per worktree (npm install is idempotent/safe to re-run).
  if ( cd "${WORKTREE}/js" && npm install >/dev/null 2>&1 ); then
    ok "Node.js + js/node_modules (installed)"
  else
    bad "js/node_modules missing, and 'npm install' failed" "cd ${WORKTREE}/js && npm install"
  fi
}


check_java() {
  printf '%sjava%s\n' "${BOLD}" "${RESET}"
  if [[ ! -f "${WORKTREE}/java/pom.xml" ]]; then printf '  %s—%s no implementation in java/\n' "${YELLOW}" "${RESET}"; return; fi
  if ! command -v mvn >/dev/null 2>&1; then bad "mvn not found" "install Maven, e.g. 'brew install maven'"; return; fi
  if grep -q '^camunda.client.mode=saas' "${WORKTREE}/java/src/main/resources/application.properties" 2>/dev/null; then
    printf '  %s—%s plain Java worker is SaaS-only on this branch\n' "${YELLOW}" "${RESET}"
    return
  fi
  ok "mvn (java/pom.xml present)"
}

check_java_spring() {
  printf '%sjava-spring%s\n' "${BOLD}" "${RESET}"
  if [[ ! -f "${WORKTREE}/java-spring/pom.xml" ]]; then printf '  %s—%s no implementation in java-spring/\n' "${YELLOW}" "${RESET}"; return; fi
  if ! command -v mvn >/dev/null 2>&1; then bad "mvn not found" "install Maven, e.g. 'brew install maven'"; return; fi
  ok "mvn (java-spring/pom.xml present)"
}

for lang in "${LANGS[@]}"; do
  case "${lang}" in
    python)      check_python ;;
    csharp)      check_csharp ;;
    js)          check_js ;;
    java)        check_java ;;
    java-spring) check_java_spring ;;
    *)      printf '%s%s%s\n  %s—%s unknown language\n' "${BOLD}" "${lang}" "${RESET}" "${YELLOW}" "${RESET}" ;;
  esac
done

# ---- summary -----------------------------------------------------------------
printf '\n'
if [[ "${CORE_MISSING}" -eq 1 ]]; then
  printf '%sCore dependencies are missing — the tests cannot run until those are fixed.%s\n' "${YELLOW}" "${RESET}"
elif [[ "${WORKER_MISSING}" -eq 1 ]]; then
  printf '%sCore dependencies OK.%s Workers marked ✗ above will be skipped (not failed).\n' "${GREEN}" "${RESET}"
else
  printf '%sAll dependencies present.%s\n' "${GREEN}" "${RESET}"
fi

# Exit code communicates findings to callers (the check is still informational, never fatal here):
#   0 = all good   1 = some worker deps missing   2 = a core dependency missing
if [[ "${CORE_MISSING}" -eq 1 ]]; then exit 2
elif [[ "${WORKER_MISSING}" -eq 1 ]]; then exit 1
else exit 0; fi
