import { createCamundaClient } from "@camunda8/orchestration-cluster-api";

const config = require('../../config.json');   /* API credentials */

const client = createCamundaClient({
    config: {
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

    client.createJobWorker({
        jobType: 'payment-invocation',
        jobTimeoutMs: 20000,
        maxParallelJobs: 1,
        workerName: 'payment-invoke-worker',
        jobHandler: startPaymentProcess
    })

    client.createJobWorker({
        jobType: 'payment-completion',
        jobTimeoutMs: 20000,
        maxParallelJobs: 1,
        workerName: 'payment-completion-worker',
        jobHandler: respondToOrderProcess
    })

})()

async function creditDeduction(job) {

    console.log("Deducting customer credit...");

    const customerId = job.variables.customerId;
    const orderTotal = Number(job.variables.orderTotal);

    const customerCredit = getCustomerCredit(customerId);
    const openAmount = deductCredit(orderTotal, customerCredit);

    console.log("Deducted from customer's total credit: " + customerCredit + " EUR. Remaining amount is: " + openAmount + " EUR");

    await job.complete({openAmount: openAmount, customerCredit: customerCredit});
}

async function creditCardCharging(job) {

    console.log("Charging card...");

    const cardNumber = job.variables.cardNumber,
          expiryDate = job.variables.expiryDate,
          amount = job.variables.openAmount,
          cvc = job.variables.cvc;

    if (isInvalidExpiryDate(expiryDate)) {

         console.log("Invalid expiration date: " + expiryDate);

         await job.fail({ errorMessage: 'Invalid expiration date',
                          retries: 0 });
    } else {

         console.log("Charged card " + cardNumber + " that expires on " + expiryDate + " and has cvc " + cvc + " with amount of " + amount + " EUR");

         await job.complete();
    }
}

async function startPaymentProcess(job) {

    await client.publishMessage({ name: 'paymentRequestMessage',
	                                variables: job.variables });

    console.log("Starting payment process...");

    await job.complete();
}

async function respondToOrderProcess(job) {

    await client.publishMessage({ correlationKey: job.variables.orderId,
                                  name: 'paymentCompletedMessage',
	                                variables: job.variables });

    console.log("Responding to order process...");

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

function isInvalidExpiryDate(expiryDate) {

    if (expiryDate.length != 5) {
        return true;
    } else {
        return false;
    }
}
