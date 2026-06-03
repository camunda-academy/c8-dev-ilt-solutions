package io.camunda.training.workers;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentFailureWorker {

  Logger logger = LoggerFactory.getLogger(PaymentFailureWorker.class);

  private final CamundaClient camundaClient;

  @Autowired
  public PaymentFailureWorker(CamundaClient camundaClient) {
    this.camundaClient = camundaClient;
  }

  @JobWorker(type = "payment-failure", autoComplete = false)
  public void handlePaymentCompletion(JobClient jobClient, ActivatedJob job,
                                      @Variable String orderId) {
    logger.info("Job handled: {}", job.getType());

    // Tell Order Process to continue
    camundaClient.newPublishMessageCommand()
            .messageName("paymentFailedMessage")
            .correlationKey(orderId)
            .send().join();

    // Complete the job in the Payment Process
    jobClient.newCompleteCommand(job).send();
  }
}