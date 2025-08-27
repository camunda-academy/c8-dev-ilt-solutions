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

    client.createJobWorker({
        type: 'payment-invocation',
        timeout: 20000,
        maxJobsToActivate: 1,
        worker: 'payment-invoke-worker',
        jobHandler: startPaymentProcess
    })

    client.createJobWorker({
        type: 'payment-completion',
        timeout: 20000,
        maxJobsToActivate: 1,
        worker: 'payment-completion-worker',
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

         await job.error({ errorCode: "creditCardChargeError",
                           errorMessage: "Invalid expiration date" });
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
