const config = require('../../config.json');   /* API credentials */
const { Camunda8 } = require('@camunda8/sdk'); /* npm i @camunda8/sdk */

let client;

async function connect() {

    const c8 = new Camunda8(config);

    return c8;
}

(async () => {

    const c8 = await connect();

    client = c8.getCamundaRestClient();

    const topology = await client.getTopology();

    console.log(topology);

    client.createJobWorker({
        type: 'credit-deduction',
        timeout: 20000,
        maxJobsToActivate: 1,
        worker: 'credit-deduction-worker',
        jobHandler: creditDeduction
    })

    client.createJobWorker({
        type: 'credit-card-charging',
        timeout: 20000,
        maxJobsToActivate: 1,
        worker: 'credit-card-worker',
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
