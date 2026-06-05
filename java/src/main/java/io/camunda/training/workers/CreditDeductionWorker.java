package io.camunda.training.workers;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.camunda.training.services.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class CreditDeductionWorker implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(CreditDeductionWorker.class);

    private final CustomerService customerService;

    public CreditDeductionWorker(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Override
    public void handle(JobClient client, ActivatedJob job) throws Exception {
        logger.info("Job handled: {}", job.getType());

        Map<String, Object> variables = job.getVariablesAsMap();
        String customerId = (String) variables.get("customerId");
        Number orderTotal = (Number) variables.get("orderTotal");

        Double openAmount = customerService.deductCredit(customerId, orderTotal.doubleValue());

        client.newCompleteCommand(job).variables(Map.of("openAmount", openAmount)).send();
    }
}
