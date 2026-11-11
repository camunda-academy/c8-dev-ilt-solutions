package io.camunda.training.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomerService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);
    private final Pattern pattern = Pattern.compile("(.*?)(\\d*)");

    public Double deductCredit(String customerId, Double amount) {
        Double credit = getCustomerCredit(customerId);
        Double openAmount;
        Double deductedCredit;

        if (credit > amount) {
            deductedCredit = amount;
            openAmount = 0.0;
        } else {
            openAmount = amount - credit;
            deductedCredit = credit;
        }

        logger.info("Charged {} from the credit, open amount is {}", deductedCredit, openAmount);

        return openAmount;
    }

    public Double getCustomerCredit(String customerId) {
        Double credit = 0.0;
        Matcher matcher = pattern.matcher(customerId);

        if (matcher.matches() && matcher.group(2) != null && !matcher.group(2).isEmpty()) {
            credit = Double.valueOf(matcher.group(2));
        }

        logger.info("Customer {} has a credit of {}", customerId, credit);

        return credit;
    }
}
