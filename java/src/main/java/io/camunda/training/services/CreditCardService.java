package io.camunda.training.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreditCardService {

    private static final Logger logger = LoggerFactory.getLogger(CreditCardService.class);

    public void chargeAmount(String cardNumber, String cvc, String expiryDate, Double amount) {
        if (isExpiryDateValid(expiryDate)) {
            logger.info("Charging card {} that expires on {} and has a CVC {} with an amount of {} {}",
                    cardNumber, expiryDate, cvc, amount, System.lineSeparator());

            logger.info("Payment completed");
        } else {
            throw new IllegalArgumentException("Expiry date invalid: " + expiryDate);
        }
    }

    private boolean isExpiryDateValid(String expiryDate) {
        return expiryDate.length() == 5;
    }
}
