package io.camunda.training.workers;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CreditCardChargingWorker {
  Logger logger = LoggerFactory.getLogger(CreditCardChargingWorker.class);

  @JobWorker(type = "credit-card-charging", autoComplete = false)
  public void handleCreditCardCharging(final JobClient jobClient, final ActivatedJob job) {
    logger.info("Job handled: {}", job.getType());

    jobClient.newCompleteCommand(job).send();
  }
}
