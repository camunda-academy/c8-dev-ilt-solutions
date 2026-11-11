package io.camunda.training.workers;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreditDeductionWorker implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(CreditDeductionWorker.class);

    @Override
    public void handle(JobClient client, ActivatedJob job) throws Exception {
        logger.info("Job handled: {}", job.getType());

        client.newCompleteCommand(job).send();
    }
}
