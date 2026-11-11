package io.camunda.training;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.training.workers.CreditCardChargingWorker;
import io.camunda.training.workers.CreditDeductionWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

public class CamundaApplication {

  private static final Logger logger = LoggerFactory.getLogger(CamundaApplication.class);

  public static void main(String[] args) throws Exception {
    Properties props = new Properties();
    try (InputStream in = CamundaApplication.class.getResourceAsStream("/application.properties")) {
      props.load(in);
    }

    try (CamundaClient client = CamundaClient.newCloudClientBuilder()
            .withClusterId(props.getProperty("camunda.client.cloud.cluster-id"))
            .withClientId(props.getProperty("camunda.client.auth.client-id"))
            .withClientSecret(props.getProperty("camunda.client.auth.client-secret"))
            .withRegion(props.getProperty("camunda.client.cloud.region"))
            .build();

         JobWorker creditDeductionWorker = client.newWorker()
                 .jobType("credit-deduction")
                 .handler(new CreditDeductionWorker())
                 .open();

         JobWorker creditCardWorker = client.newWorker()
                 .jobType("credit-card-charging")
                 .handler(new CreditCardChargingWorker())
                 .open()) {
      logger.info("Workers started, waiting for jobs...");

      Thread.currentThread().join();
    }
  }
}
