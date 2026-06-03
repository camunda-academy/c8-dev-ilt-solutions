package io.camunda.training.services;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CustomerService {

  private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);

  /**
   * The customer credit is derived from the last digits of the customer ID
   */
  private final Pattern pattern = Pattern.compile("(.*?)(\\d*)");

  /**
   * Deduct the credit for the given customer and amount
   *
   * @param customerId
   * @param amount
   * @return the open order amount
   */
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

  /**
   * Return the current customer credit
   *
   * @param customerId
   * @return the current credit of the given customer
   */
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