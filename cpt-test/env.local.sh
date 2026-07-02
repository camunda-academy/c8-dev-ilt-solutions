# Source this before starting a worker, to point it at the local CPT runtime
# (cpt-test/start-runtime.sh) over an unauthenticated connection:
#
#   source cpt-test/env.local.sh
#   cd python && python3 web_shop.py      # or:  cd csharp && dotnet run
#                                         # or:  cd js && npx ts-node src/workers/exercise_5.ts
#
# The workers connect zero-config: when these CAMUNDA_* variables are present they are used and
# no authentication is applied; when they are absent each worker falls back to its own SaaS config
# file (config.ini / appsettings.json / config.json), which is never modified.

# REST API (Python / JS SDKs). The Python SDK appends /v2 automatically.
export CAMUNDA_REST_ADDRESS="http://localhost:8080"

# gRPC API (C# zb-client, which connects over gRPC rather than REST).
export CAMUNDA_GRPC_ADDRESS="http://localhost:26500"

# No authentication — the local runtime runs in unprotected-API mode.
export CAMUNDA_AUTH_STRATEGY="NONE"

echo "Local Camunda env set: REST=$CAMUNDA_REST_ADDRESS gRPC=$CAMUNDA_GRPC_ADDRESS auth=NONE"
