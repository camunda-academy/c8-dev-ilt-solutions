package io.camunda.training.workers;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CreditDeductionWorker {
  Logger logger = LoggerFactory.getLogger(CreditDeductionWorker.class);

  @JobWorker(type = "credit-deduction", autoComplete = false)
  public void handleCreditDeduction(final JobClient jobClient, final ActivatedJob job) {
    logger.info("Job handled: {}", job.getType());

    jobClient.newCompleteCommand(job).send();
  }
}
