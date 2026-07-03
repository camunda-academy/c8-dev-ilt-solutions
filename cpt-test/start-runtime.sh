#!/usr/bin/env bash
#
# Starts a local Camunda 8 runtime that BOTH the job workers and the CPT tests connect to.
#
#   - The real job workers (python / csharp / js) are started manually by you and connect here.
#   - CPT runs in "remote" mode (see src/test/resources/camunda-container-runtime.properties)
#     and connects to this same runtime, drives the process, and deletes data between tests.
#
# The runtime runs in UNPROTECTED API mode (no auth) for local development, with the controlled
# clock enabled so CPT can manipulate time. It uses H2 as secondary storage (the 8.9 lightweight
# default) so NO Elasticsearch/OpenSearch container is required. Ports are fixed so workers and CPT
# have known addresses:
#
#   26500  Zeebe gRPC API        (gRPC workers, e.g. C# zb-client)
#   8080   Orchestration REST    (REST workers, e.g. python/js SDKs; also CPT client)
#   9600   Monitoring/management (CPT uses this to reset data + control the clock)
#
# Usage:
#   ./start-runtime.sh            # start (foreground; Ctrl+C to stop)
#   ./start-runtime.sh stop       # stop and remove the container
#
set -euo pipefail

IMAGE="camunda/camunda:8.10.0-alpha3-rc2"
NAME="cpt-camunda"

if [[ "${1:-start}" == "stop" ]]; then
  echo "Stopping ${NAME}..."
  docker rm -f "${NAME}" >/dev/null 2>&1 || true
  echo "Stopped."
  exit 0
fi

# Clean up any previous container so ports are free.
docker rm -f "${NAME}" >/dev/null 2>&1 || true

echo "Starting Camunda runtime (${IMAGE}) as '${NAME}'..."
echo "  gRPC : http://localhost:26500"
echo "  REST : http://localhost:8080"
echo "  Mgmt : http://localhost:9600"
echo
echo "Leave this running. In another terminal, start ONE worker implementation,"
echo "then run:  mvn test -Dtest=Exercise05Test -Dlang=<python|csharp|js>"
echo

exec docker run --rm --name "${NAME}" \
  -p 26500:26500 \
  -p 8080:8080 \
  -p 9600:9600 \
  -e SPRING_PROFILES_ACTIVE=broker \
  -e CAMUNDA_DATA_SECONDARYSTORAGE_TYPE=rdbms \
  -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_URL='jdbc:h2:mem:camunda;DB_CLOSE_DELAY=-1' \
  -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_USERNAME=sa \
  -e CAMUNDA_DATA_SECONDARYSTORAGE_RDBMS_PASSWORD= \
  -e CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTEDAPI=true \
  -e CAMUNDA_SECURITY_AUTHORIZATIONS_ENABLED=false \
  -e ZEEBE_CLOCK_CONTROLLED=true \
  "${IMAGE}"
