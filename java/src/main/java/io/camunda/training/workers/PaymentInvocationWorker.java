package io.camunda.training.workers;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PaymentInvocationWorker implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(PaymentInvocationWorker.class);

    private final CamundaClient camundaClient;

    public PaymentInvocationWorker(CamundaClient camundaClient) {
        this.camundaClient = camundaClient;
    }

    @Override
    public void handle(JobClient client, ActivatedJob job) throws Exception {
        logger.info("Job handled: {}", job.getType());

        // Start Payment Process via message
        camundaClient.newPublishMessageCommand()
                .messageName("paymentRequestMessage")
                .withoutCorrelationKey()
                .variables(job.getVariablesAsMap())
                .send().join();

        // Complete the job in the Order Process
        client.newCompleteCommand(job).send();
    }
}