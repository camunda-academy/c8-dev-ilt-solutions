package io.camunda.training.workers;

import io.camunda.client.CamundaClient;
import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PaymentInvocationWorker {

  Logger logger = LoggerFactory.getLogger(PaymentCompletionWorker.class);

  private final CamundaClient camundaClient;

  @Autowired
  public PaymentInvocationWorker(CamundaClient camundaClient) {
    this.camundaClient = camundaClient;
  }

  @JobWorker(type = "payment-invocation", autoComplete = false)
  public void handlePaymentInvocation(JobClient jobClient, ActivatedJob job) {
    logger.info("Job handled: {}", job.getType());

    // Start Payment Process via message
    camundaClient.newPublishMessageCommand()
            .messageName("paymentRequestMessage")
            .withoutCorrelationKey()
            .variables(job.getVariablesAsMap())
            .send().join();

    // Complete the job in the Order Process
    jobClient.newCompleteCommand(job).send();
  }
}