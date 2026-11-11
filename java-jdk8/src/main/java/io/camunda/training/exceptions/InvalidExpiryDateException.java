package io.camunda.training.exceptions;

/** Thrown when the expiry date of a credit card is invalid. */
public class InvalidExpiryDateException extends RuntimeException {

    public InvalidExpiryDateException(String message) {
        super(message);
    }
}
