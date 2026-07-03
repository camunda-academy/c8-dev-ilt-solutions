import { createCamundaClient } from "@camunda8/orchestration-cluster-api";

const config = require('../../config.json');   /* API credentials */

// Prefer CAMUNDA_* environment variables (zero-config, e.g. a local unauthenticated runtime);
// fall back to config.json (SaaS/OAuth) when no CAMUNDA_* env vars are present.
const hasCamundaEnv = Object.keys(process.env).some(key => key.startsWith("CAMUNDA_"));

const client = createCamundaClient({
    config: hasCamundaEnv
        ? {
            CAMUNDA_REST_ADDRESS: process.env.CAMUNDA_REST_ADDRESS ?? "http://localhost:8080",
            CAMUNDA_AUTH_STRATEGY: (process.env.CAMUNDA_AUTH_STRATEGY ?? "NONE") as "NONE" | "OAUTH" | "BASIC",
            CAMUNDA_CLIENT_ID: process.env.CAMUNDA_CLIENT_ID,
            CAMUNDA_CLIENT_SECRET: process.env.CAMUNDA_CLIENT_SECRET,
            CAMUNDA_OAUTH_URL: process.env.CAMUNDA_OAUTH_URL,
            CAMUNDA_TOKEN_AUDIENCE: process.env.CAMUNDA_TOKEN_AUDIENCE ?? "zeebe.camunda.io",
        }
        : {
            CAMUNDA_REST_ADDRESS: config.CAMUNDA_REST_ADDRESS,
            CAMUNDA_AUTH_STRATEGY: "OAUTH",
            CAMUNDA_CLIENT_ID: config.CAMUNDA_CLIENT_ID,
            CAMUNDA_CLIENT_SECRET: config.CAMUNDA_CLIENT_SECRET,
            CAMUNDA_OAUTH_URL: config.CAMUNDA_OAUTH_URL,
            CAMUNDA_TOKEN_AUDIENCE: "zeebe.camunda.io",
        }
});

(async () => {

    const topology = await client.getTopology();

    console.log(topology);

    client.createJobWorker({
        jobType: 'credit-deduction',
        jobTimeoutMs: 20000,
        maxParallelJobs: 1,
        workerName: 'credit-deduction-worker',
        jobHandler: creditDeduction
    })

    client.createJobWorker({
        jobType: 'credit-card-charging',
        jobTimeoutMs: 20000,
        maxParallelJobs: 1,
        workerName: 'credit-card-worker',
        jobHandler: creditCardCharging
    })

})()

async function creditDeduction(job) {

    console.log("Deducting customer credit...");

    await job.complete();
}

async function creditCardCharging(job) {

    console.log("Charging card...");

    await job.complete();
}

function getCustomerCredit(customerId) {

      let credit = 0.0;

      const regEx = /\d+/;

      const match = customerId.match(regEx);

      if (match) { credit = parseFloat(match); }

      return credit;
}

function deductCredit(amount, credit) {

      let openAmount = 0.0;

      if (credit < amount) { openAmount = amount - credit; }

      return openAmount;
}
