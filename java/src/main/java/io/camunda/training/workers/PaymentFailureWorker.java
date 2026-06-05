package io.camunda.training.workers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PaymentFailureWorker implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(PaymentFailureWorker.class);

    private final CamundaClient camundaClient;

    public PaymentFailureWorker(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    @Override
    public void handle(JobClient client, ActivatedJob job) throws Exception {
        logger.info("Job handled: {}", job.getType());

        String orderId = (String) job.getVariablesAsMap().get("orderId");

        // Tell Order Process to continue
        camundaClient.newPublishMessageCommand()
                .messageName("paymentFailedMessage")
                .correlationKey(orderId)
                .send().join();

        // Complete the job in the Payment Process
        client.newCompleteCommand(job).send();
    }
}
