package io.camunda.training;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.worker.JobWorker;
import io.camunda.training.services.CreditCardService;
import io.camunda.training.services.CustomerService;
import io.camunda.training.workers.CreditCardChargingWorker;
import io.camunda.training.workers.CreditDeductionWorker;
import io.camunda.training.workers.PaymentCompletionWorker;
import io.camunda.training.workers.PaymentInvocationWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

public class CamundaApplicationCpt {

    /**
     * CPT-only launcher.
     *
     * <p>
     * This class exists to keep trainee-facing code in {@link CamundaApplication}
     * simple,
     * while giving the CPT harness a dedicated entrypoint for local test runtime
     * execution.
     */

    private static final Logger logger = LoggerFactory.getLogger(CamundaApplicationCpt.class);

    public static void main(String[] args) throws Exception {
        CreditCardService creditCardService = new CreditCardService();
        CustomerService customerService = new CustomerService();

        try (CamundaClient client = createClient();

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
                        .open()) {
            logger.info("Workers started, waiting for jobs...");

            Thread.currentThread().join();
        }
    }

    private static CamundaClient createClient() throws Exception {
        if (hasLocalCamundaEnvironment()) {
            return CamundaClient.newClientBuilder().build();
        }

        Properties props = loadProperties();
        String clusterId = requireNonBlank("cluster id", resolveClusterId(props));
        String clientId = requireNonBlank("client id", resolveClientId(props));
        String clientSecret = requireNonBlank("client secret", resolveClientSecret(props));
        String region = requireNonBlank("cluster region", resolveRegion(props));

        return CamundaClient.newCloudClientBuilder()
                .withClusterId(clusterId)
                .withClientId(clientId)
                .withClientSecret(clientSecret)
                .withRegion(region)
                .build();
    }

    private static Properties loadProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = CamundaApplicationCpt.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        }
        return props;
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private static boolean hasLocalCamundaEnvironment() {
        String restAddress = firstNonBlank(env("CAMUNDA_REST_ADDRESS"), env("ZEEBE_REST_ADDRESS"));
        String authStrategy = env("CAMUNDA_AUTH_STRATEGY");
        String clientMode = env("CAMUNDA_CLIENT_MODE");

        return isNonBlank(restAddress)
                && ("NONE".equalsIgnoreCase(authStrategy) || "self-managed".equalsIgnoreCase(clientMode));
    }

    private static String resolveClusterId(Properties props) {
        return firstNonBlank(
                env("CAMUNDA_CLUSTER_ID"),
                env("CAMUNDA_CLIENT_CLOUD_CLUSTERID"),
                props.getProperty("camunda.client.cloud.cluster-id"));
    }

    private static String resolveClientId(Properties props) {
        return firstNonBlank(
                env("CAMUNDA_CLIENT_ID"),
                env("CAMUNDA_CLIENT_AUTH_CLIENTID"),
                env("ZEEBE_CLIENT_ID"),
                props.getProperty("camunda.client.auth.client-id"));
    }

    private static String resolveClientSecret(Properties props) {
        return firstNonBlank(
                env("CAMUNDA_CLIENT_SECRET"),
                env("CAMUNDA_CLIENT_AUTH_CLIENTSECRET"),
                env("ZEEBE_CLIENT_SECRET"),
                props.getProperty("camunda.client.auth.client-secret"));
    }

    private static String resolveRegion(Properties props) {
        return firstNonBlank(
                env("CAMUNDA_CLUSTER_REGION"),
                env("CAMUNDA_CLIENT_CLOUD_REGION"),
                props.getProperty("camunda.client.cloud.region"));
    }

    private static String requireNonBlank(String label, String value) {
        if (isNonBlank(value)) {
            return value;
        }
        throw new IllegalStateException(
                "Missing Camunda " + label
                        + " (env CAMUNDA_* preferred, ZEEBE_* fallback, then application.properties)");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (isNonBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
