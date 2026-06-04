
# Camunda 8 Training - C# Solution

This folder contains the solution code for the Camunda 8 training exercise, including the worker implementation.

It uses the new **Camunda C# SDK** ([`Camunda.Orchestration.Sdk`](https://docs.camunda.io/docs/apis-tools/csharp-sdk/installation/)), which is in technical preview until Camunda 8.10.

## Configuration

The client is created with [zero-config](https://docs.camunda.io/docs/apis-tools/csharp-sdk/quick-start-zero-config-recommended/): it reads `CAMUNDA_*` environment variables. If none are present, it falls back to the `Camunda` section of `appsettings.json` (with `appsettings.Development.json` layered on top for local secrets).

### Option 1 — Environment variables (recommended)

```sh
export CAMUNDA_REST_ADDRESS=https://<region>.zeebe.camunda.io/<cluster-id>
export CAMUNDA_AUTH_STRATEGY=OAUTH
export CAMUNDA_CLIENT_ID=***
export CAMUNDA_CLIENT_SECRET=***
export CAMUNDA_OAUTH_URL=https://login.cloud.camunda.io/oauth/token
export CAMUNDA_TOKEN_AUDIENCE=zeebe.camunda.io
```

### Option 2 — appsettings.json fallback

Fill in the `Camunda` section of `appsettings.json` (or keep secrets out of source control in the gitignored `appsettings.Development.json`):

```json
{
  "Camunda": {
    "RestAddress": "https://<region>.zeebe.camunda.io/<cluster-id>",
    "Auth": { "Strategy": "OAUTH", "ClientId": "***", "ClientSecret": "***" },
    "OAuth": { "Url": "https://login.cloud.camunda.io/oauth/token" },
    "TokenAudience": "zeebe.camunda.io"
  }
}
```

## Build and Run

To build the project:

```sh
dotnet build
```

To run the worker:

```sh
dotnet run
```
