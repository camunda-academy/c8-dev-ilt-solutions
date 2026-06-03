package io.camunda.training.workers;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.training.services.CreditCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CreditCardChargingWorker {

  Logger logger = LoggerFactory.getLogger(CreditCardChargingWorker.class);

  private final CreditCardService creditCardService;

  @Autowired
  public CreditCardChargingWorker(CreditCardService creditCardService) {
    this.creditCardService = creditCardService;
  }

  @JobWorker(type = "credit-card-charging", autoComplete = false)
  public void handleCreditCardCharging(JobClient client, ActivatedJob job,
                                       @Variable String cardNumber, @Variable String expiryDate, @Variable String cvc,
                                       @Variable Double openAmount) {
    logger.info("Job handled: {}", job.getType());

    try {
      creditCardService.chargeAmount(cardNumber, cvc, expiryDate, openAmount);

      client.newCompleteCommand(job).send();
    } catch (IllegalArgumentException exception) {
      client.newFailCommand(job.getKey())
              .retries(0)
              .retryBackoff(Duration.ZERO)
              .errorMessage(exception.getMessage())
              .send().join();
    }
  }
}