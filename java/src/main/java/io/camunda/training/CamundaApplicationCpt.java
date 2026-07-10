package io.camunda.training;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.training.services.CreditCardService;
import io.camunda.training.services.CustomerService;
import io.camunda.training.workers.CreditCardChargingWorker;
import io.camunda.training.workers.CreditDeductionWorker;
import io.camunda.training.workers.PaymentCompletionWorker;
import io.camunda.training.workers.PaymentFailureWorker;
import io.camunda.training.workers.PaymentInvocationWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

public class CamundaApplicationCpt {

  /**
   * CPT-only launcher.
   *
   * <p>This class exists to keep trainee-facing code in { CamundaApplication} simple,
   * while giving the CPT harness a dedicated entrypoint for local test runtime execution.
   */

        private static final Logger logger = LoggerFactory.getLogger(CamundaApplicationCpt.class);

        public static void main(String[] args) throws Exception {
                Properties props = new Properties();
                try (InputStream in = CamundaApplicationCpt.class.getResourceAsStream("/application.properties")) {
                        if (in != null) {
                                props.load(in);
                        }
                }

                boolean hasCamundaEnv = hasCamundaEnvironment();

                String clusterId = hasCamundaEnv
                                ? env("CAMUNDA_CLUSTER_ID")
                                : props.getProperty("camunda.client.cloud.cluster-id");
                String clientId = hasCamundaEnv
                                ? firstNonBlank(env("CAMUNDA_CLIENT_ID"), env("ZEEBE_CLIENT_ID"))
                                : props.getProperty("camunda.client.auth.client-id");
                String clientSecret = hasCamundaEnv
                                ? firstNonBlank(env("CAMUNDA_CLIENT_SECRET"), env("ZEEBE_CLIENT_SECRET"))
                                : props.getProperty("camunda.client.auth.client-secret");
                String region = hasCamundaEnv
                                ? firstNonBlank(env("CAMUNDA_CLUSTER_REGION"), env("ZEEBE_CLIENT_REGION"))
                                : props.getProperty("camunda.client.cloud.region");

                CreditCardService creditCardService = new CreditCardService();
                CustomerService customerService = new CustomerService();

                try (CamundaClient client = CamundaClient.newCloudClientBuilder()
                                .withClusterId(clusterId)
                                .withClientId(clientId)
                                .withClientSecret(clientSecret)
                                .withRegion(region)
                                .build();

                                JobWorker creditDeductionWorker = client.newWorker()
                                                .jobType("credit-deduction")
                                                .handler(new CreditDeductionWorker(customerService))
                                                .open();

                                JobWorker creditCardWorker = client.newWorker()
                                                .jobType("credit-card-charging")
                                                .handler(new CreditCardChargingWorker(creditCardService))
                                                .open();

                                JobWorker paymentInvocationWorker = client.newWorker()
                                                .jobType("payment-invocation")
                                                .handler(new PaymentInvocationWorker(client))
                                                .open();

                                JobWorker paymentCompletionWorker = client.newWorker()
                                                .jobType("payment-completion")
                                                .handler(new PaymentCompletionWorker(client))
                                                .open();

                                JobWorker paymentFailureWorker = client.newWorker()
                                                .jobType("payment-failure")
                                                .handler(new PaymentFailureWorker(client))
                                                .open()) {
                        logger.info("Workers started, waiting for jobs...");

                        Thread.currentThread().join();
                }
        }

        private static String env(String name) {
                return System.getenv(name);
        }

        private static boolean hasCamundaEnvironment() {
                return System.getenv().keySet().stream().anyMatch(key -> key.startsWith("CAMUNDA_"));
        }

        private static String firstNonBlank(String... values) {
                for (String value : values) {
                        if (value != null && !value.isBlank()) {
                                return value;
                        }
                }
                return null;
        }
}
