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
 * Exercise 07 — Order Process invoking Payment Process asynchronously via messages.
 *
 * <p>Verifies {@code OrderProcess} (see {@code assets/Order Process.bpmn}) and {@code
 * PaymentProcess} (see {@code assets/Payment Process.bpmn}) end-to-end against the REAL job
 * workers — nothing is mocked. Unlike exercise 06, {@code PaymentProcess} is no longer started
 * directly: its start event is a message start event ({@code paymentRequestMessage}), triggered
 * by {@code OrderProcess}'s {@code payment-invocation} send task. When {@code PaymentProcess}
 * reaches its {@code payment-completion} end event, it publishes {@code paymentCompletedMessage}
 * (correlated by {@code orderId}), which resolves {@code OrderProcess}'s intermediate catch event
 * and lets it complete.
 *
 * <p>Because {@code PaymentProcess} is started indirectly, the test never gets a {@link
 * ProcessInstanceEvent} handle for it — it is asserted via {@code
 * CamundaAssert.assertThat(byProcessId("PaymentProcess"))}, which looks the instance up and
 * auto-awaits its completion.
 *
 * <h2>How to run</h2>
 *
 * <ol>
 *   <li>Start the runtime: {@code ./start-runtime.sh} (Camunda on :26500 / :8080 / :9600).</li>
 *   <li>Start ONE worker implementation, pointing it at that runtime (see cpt-test/README.md).</li>
 *   <li>Run the test, naming the language you started:
 *       {@code mvn test -Dtest=Exercise07Test -Dlang=python}.</li>
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
class Exercise07Test {

  private static final String ORDER_PROCESS_ID = "OrderProcess";
  private static final String PAYMENT_PROCESS_ID = "PaymentProcess";
  private static final String ORDER_BPMN_FILE = "Order Process.bpmn";
  private static final String PAYMENT_BPMN_FILE = "Payment Process.bpmn";

  // OrderProcess element ids (from assets/Order Process.bpmn).
  private static final String ORDER_START = "StartEvent_1";
  private static final String GENERATE_ORDER_ID = "Activity_1omjax4"; // script task
  private static final String INVOKE_PAYMENT = "Activity_1s27spe"; // job type payment-invocation
  private static final String PAYMENT_COMPLETED_CATCH = "Event_0bpmwgs";
  private static final String ORDER_END = "Event_0ttkttq";

  // PaymentProcess element ids (from assets/Payment Process.bpmn).
  private static final String PAYMENT_START = "Event_19qu0oi";
  private static final String DEDUCT_CREDIT = "Activity_1hu6sex"; // job type credit-deduction
  private static final String GATEWAY_CREDIT_SUFFICIENT = "Gateway_1xthnkz";
  private static final String ADD_CARD_FEE = "Activity_1cfafq9"; // script task: openAmount * 1.02
  private static final String CHARGE_CARD = "Activity_16kuby1"; // job type credit-card-charging
  private static final String GATEWAY_MERGE = "Gateway_02roc4u";
  private static final String PAYMENT_END = "Event_03bvprt"; // job type payment-completion

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
            "Skipping Exercise07Test: none of the requested languages ("
                + String.join(", ", Languages.requested())
                + ") has an implementation on this branch. Available: "
                + Languages.ALL.stream().filter(Languages::isAvailable).toList());
  }

  @Test
  @DisplayName(
      "payWithCreditCard: credit (30) < total (45.99) -> card fee applied, both PaymentProcess"
          + " service tasks run, both processes complete")
  void payWithCreditCard() {
    // given: deploy both processes and pick a customer whose credit is NOT enough (cust30 -> 30).
    deploy();

    // when: an order is placed. OrderProcess invokes PaymentProcess via message; the real workers
    // complete both PaymentProcess jobs, then PaymentProcess signals completion back.
    ProcessInstanceEvent orderInstance =
        createOrderInstance(
            Map.of(
                "orderTotal", 45.99,
                "customerId", "cust30",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "09/28"));

    // then: OrderProcess runs end-to-end.
    CamundaAssert.assertThat(orderInstance).isCompleted();
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(
            ORDER_START, GENERATE_ORDER_ID, INVOKE_PAYMENT, PAYMENT_COMPLETED_CATCH, ORDER_END);

    // and: the indirectly-started PaymentProcess ran the full path (deduct credit -> fee -> charge
    // card) and completed, with the fee applied on top of the deducted amount.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).isCompleted();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasCompletedElements(
            PAYMENT_START,
            DEDUCT_CREDIT,
            GATEWAY_CREDIT_SUFFICIENT,
            ADD_CARD_FEE,
            CHARGE_CARD,
            GATEWAY_MERGE,
            PAYMENT_END);
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
      "payWithCreditOnly: credit (99) >= total (45.99) -> card fee and charging skipped, both"
          + " processes complete")
  void payWithCreditOnly() {
    // given: deploy both processes and pick a customer whose credit IS enough (cust99 -> 99).
    // Credit is read from the customerId's LAST TWO characters (see e.g. python's
    // credit_service.get_customer_credit), so it must stay a two-digit suffix here.
    deploy();

    // when: an order is placed. Only the credit-deduction worker is involved in PaymentProcess.
    ProcessInstanceEvent orderInstance =
        createOrderInstance(
            Map.of(
                "orderTotal", 45.99,
                "customerId", "cust99",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "09/28"));

    // then: OrderProcess runs end-to-end.
    CamundaAssert.assertThat(orderInstance).isCompleted();
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(
            ORDER_START, GENERATE_ORDER_ID, INVOKE_PAYMENT, PAYMENT_COMPLETED_CATCH, ORDER_END);

    // and: the indirectly-started PaymentProcess skipped the card fee and charging (gateway "Yes"
    // branch) and completed.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).isCompleted();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasCompletedElements(
            PAYMENT_START, DEDUCT_CREDIT, GATEWAY_CREDIT_SUFFICIENT, GATEWAY_MERGE, PAYMENT_END);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasNotActivatedElements(ADD_CARD_FEE, CHARGE_CARD);
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
