package io.camunda.training.workers;

import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.client.api.worker.JobHandler;
import io.camunda.training.services.CreditCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

public class CreditCardChargingWorker implements JobHandler {

    private static final Logger logger = LoggerFactory.getLogger(CreditCardChargingWorker.class);

    private final CreditCardService creditCardService;

    public CreditCardChargingWorker(CreditCardService creditCardService) {
        this.creditCardService = creditCardService;
    }

    @Override
    public void handle(final JobClient client, final ActivatedJob job) throws Exception {
        logger.info("Job handled: {}", job.getType());

        Map<String, Object> variables = job.getVariablesAsMap();
        String cardNumber = (String) variables.get("cardNumber");
        String expiryDate = (String) variables.get("expiryDate");
        String cvc = (String) variables.get("cvc");
        Double openAmount = ((Number) variables.get("openAmount")).doubleValue();

        try {
            creditCardService.chargeAmount(cardNumber, cvc, expiryDate, openAmount);

            client.newCompleteCommand(job).send();
        } catch (IllegalArgumentException illegalArgumentException) {
            client.newThrowErrorCommand(job)
                    .errorCode("creditCardChargeError")
                    .send();
        } catch (Exception e) {
            client.newFailCommand(job)
                    .retries(0)
                    .retryBackoff(Duration.ZERO)
                    .errorMessage(e.getMessage())
                    .send();
        }
    }
}
