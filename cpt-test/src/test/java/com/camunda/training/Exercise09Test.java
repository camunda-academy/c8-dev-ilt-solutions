package com.camunda.training;

import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercise 09 — BPMN errors: an invalid card expiry date throws a BPMN error out of {@code
 * credit-card-charging}, caught by a boundary event, routing {@code PaymentProcess} to a
 * dedicated failure path that {@code OrderProcess} also reacts to.
 *
 * <p>Unlike exercise 08 (where the same invalid date raised an incident), the worker here calls
 * {@code newThrowErrorCommand(job).errorCode("creditCardChargeError")} instead of failing the
 * job. A boundary error event on {@code credit-card-charging} (see {@code assets/Payment
 * Process.bpmn}) catches that error code and routes to a new {@code payment-failure} end event,
 * whose worker publishes {@code paymentFailedMessage} (correlated by {@code orderId}) instead of
 * {@code paymentCompletedMessage}. {@code OrderProcess} (see {@code assets/Order Process.bpmn})
 * now has an event-based gateway racing both messages, so a failed payment routes it to its own
 * {@code Event_0an4trg} ("Order failed") end event rather than hanging forever like exercise 08.
 *
 * <h2>How to run</h2>
 *
 * <ol>
 *   <li>Start the runtime: {@code ./start-runtime.sh} (Camunda on :26500 / :8080 / :9600).</li>
 *   <li>Start ONE worker implementation, pointing it at that runtime (see cpt-test/README.md).</li>
 *   <li>Run the test, naming the language you started:
 *       {@code mvn test -Dtest=Exercise09Test -Dlang=python}.</li>
 * </ol>
 *
 * <p>The {@code -Dlang} value must match the worker you started. If it names a language with no
 * implementation on this branch, the test is <em>skipped</em>, not failed.
 *
 * <p>CPT connects to the same runtime in {@code remote} mode (see
 * {@code camunda-container-runtime.properties}) and deletes all data between test methods, so each
 * scenario runs against a clean state.
 */
@CamundaProcessTest
class Exercise09Test {

  private static final String ORDER_PROCESS_ID = "OrderProcess";
  private static final String PAYMENT_PROCESS_ID = "PaymentProcess";
  private static final String ORDER_BPMN_FILE = "Order Process.bpmn";
  private static final String PAYMENT_BPMN_FILE = "Payment Process.bpmn";

  // OrderProcess element ids (from assets/Order Process.bpmn).
  private static final String ORDER_START = "StartEvent_1";
  private static final String GENERATE_ORDER_ID = "Activity_1omjax4"; // script task
  private static final String INVOKE_PAYMENT = "Activity_1s27spe"; // job type payment-invocation
  private static final String ORDER_GATEWAY = "Gateway_0moymkj"; // event-based gateway
  private static final String PAYMENT_COMPLETED_CATCH = "Event_0ccxxtz";
  private static final String ORDER_COMPLETED_END = "Event_0thk848";
  private static final String PAYMENT_FAILED_CATCH = "Event_1vantxm";
  private static final String ORDER_FAILED_END = "Event_0an4trg";

  // PaymentProcess element ids (from assets/Payment Process.bpmn).
  private static final String PAYMENT_START = "Event_19qu0oi";
  private static final String DEDUCT_CREDIT = "Activity_1hu6sex"; // job type credit-deduction
  private static final String GATEWAY_CREDIT_SUFFICIENT = "Gateway_1xthnkz";
  private static final String ADD_CARD_FEE = "Activity_1cfafq9"; // script task: openAmount * 1.02
  private static final String CHARGE_CARD = "Activity_16kuby1"; // job type credit-card-charging
  private static final String CHARGING_FAILED_BOUNDARY = "Event_1b7ninz"; // catches creditCardChargeError
  private static final String GATEWAY_MERGE = "Gateway_02roc4u";
  private static final String PAYMENT_END = "Event_03bvprt"; // job type payment-completion
  private static final String PAYMENT_FAILED_END = "Event_06hvl17"; // job type payment-failure

  // Injected by CPT.
  private CamundaClient client;

  /**
   * Guard the whole class: if the requested language has no implementation on this branch, skip
   * (rather than fail) so the suite keeps going for the languages that do exist.
   */
  @BeforeAll
  static void skipIfRequestedLanguageUnavailable() {
    List<String> available = Languages.requestedAndAvailable();
    Assumptions.assumeFalse(
        available.isEmpty(),
        () ->
            "Skipping Exercise09Test: none of the requested languages ("
                + String.join(", ", Languages.requested())
                + ") has an implementation on this branch. Available: "
                + Languages.ALL.stream().filter(Languages::isAvailable).toList());
  }

  @Test
  @DisplayName(
      "payWithCreditCard: credit (30) < total (45.99), valid expiry date -> both PaymentProcess"
          + " service tasks run, both processes complete on the success path")
  void payWithCreditCard() {
    // given: deploy both processes and pick a customer whose credit is NOT enough (cust30 -> 30).
    deploy();

    // when: an order is placed with a valid (5-character) expiry date.
    ProcessInstanceEvent orderInstance =
        createOrderInstance(
            Map.of(
                "orderTotal", 45.99,
                "customerId", "cust30",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "09/28"));

    // then: OrderProcess takes the success branch off the event-based gateway.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(
            ORDER_START,
            GENERATE_ORDER_ID,
            INVOKE_PAYMENT,
            ORDER_GATEWAY,
            PAYMENT_COMPLETED_CATCH,
            ORDER_COMPLETED_END);
    CamundaAssert.assertThat(orderInstance).isCompleted();
    CamundaAssert.assertThat(orderInstance).hasNotActivatedElements(PAYMENT_FAILED_CATCH, ORDER_FAILED_END);

    // and: the indirectly-started PaymentProcess ran the full path (deduct credit -> fee -> charge
    // card) and completed on its success end event, with no boundary error triggered.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasCompletedElements(
            PAYMENT_START,
            DEDUCT_CREDIT,
            GATEWAY_CREDIT_SUFFICIENT,
            ADD_CARD_FEE,
            CHARGE_CARD,
            GATEWAY_MERGE,
            PAYMENT_END);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).isCompleted();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasNotActivatedElements(CHARGING_FAILED_BOUNDARY, PAYMENT_FAILED_END);
    // Exact equality would be brittle here: each language computes 45.99 - 30 with its own
    // floating-point rounding, so compare with a small tolerance instead of an exact match.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasVariableSatisfies(
            "openAmount",
            Double.class,
            openAmount -> assertThat(openAmount).isCloseTo(15.99 * 1.02, within(0.01)));
  }

  @Test
  @DisplayName(
      "payWithInvalidExpiryDate: expiry date is not 5 characters -> credit-card-charging throws a"
          + " BPMN error, both processes complete on the failure path")
  void payWithInvalidExpiryDate() {
    // given: deploy both processes and pick a customer whose credit is NOT enough, so the flow
    // reaches credit-card-charging (cust30 -> 30 < 45.99).
    deploy();

    // when: an order is placed with an invalid (non-5-character) expiry date.
    ProcessInstanceEvent orderInstance =
        createOrderInstance(
            Map.of(
                "orderTotal", 45.99,
                "customerId", "cust30",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "2027/09"));

    // then: PaymentProcess's boundary event catches the BPMN error thrown by credit-card-charging
    // and completes on its failure end event instead — no incident, no active elements left over.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasCompletedElements(
            PAYMENT_START,
            DEDUCT_CREDIT,
            GATEWAY_CREDIT_SUFFICIENT,
            ADD_CARD_FEE,
            CHARGING_FAILED_BOUNDARY,
            PAYMENT_FAILED_END);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).isCompleted();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).hasNoActiveIncidents();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).hasNotActivatedElements(PAYMENT_END);

    // and: OrderProcess's event-based gateway takes the failure branch, driven by
    // paymentFailedMessage, and completes on its own failure end event.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(
            ORDER_START,
            GENERATE_ORDER_ID,
            INVOKE_PAYMENT,
            ORDER_GATEWAY,
            PAYMENT_FAILED_CATCH,
            ORDER_FAILED_END);
    CamundaAssert.assertThat(orderInstance).isCompleted();
    CamundaAssert.assertThat(orderInstance)
        .hasNotActivatedElements(PAYMENT_COMPLETED_CATCH, ORDER_COMPLETED_END);
  }

  private void deploy() {
    client
        .newDeployResourceCommand()
        .addResourceFile(Languages.repoRoot().resolve("assets").resolve(ORDER_BPMN_FILE).toString())
        .addResourceFile(
            Languages.repoRoot().resolve("assets").resolve(PAYMENT_BPMN_FILE).toString())
        .send()
        .join();
  }

  private ProcessInstanceEvent createOrderInstance(Map<String, Object> variables) {
    return client
        .newCreateInstanceCommand()
        .bpmnProcessId(ORDER_PROCESS_ID)
        .latestVersion()
        .variables(variables)
        .send()
        .join();
  }
}
