package io.camunda.training.workers;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.training.services.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CreditDeductionWorker {

  Logger logger = LoggerFactory.getLogger(CreditDeductionWorker.class);

  private final CustomerService customerService;

  @Autowired
  public CreditDeductionWorker(CustomerService customerService) {
    this.customerService = customerService;
  }

  @JobWorker(type = "credit-deduction", autoComplete = false)
  public void handleCreditDeduction(JobClient client, ActivatedJob job, @Variable String customerId,
                                    @Variable Number orderTotal) {
    logger.info("Job handled: {}", job.getType());

    Double openAmount = customerService.deductCredit(customerId, orderTotal.doubleValue());

    client.newCompleteCommand(job).variables(Map.of("openAmount", openAmount)).send();
  }
}



