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
 * Exercise 08 — Incidents: an invalid card expiry date fails the {@code credit-card-charging} job
 * and raises an incident.
 *
 * <p>Same {@code OrderProcess} / {@code PaymentProcess} pair as exercise 07 (see {@code
 * assets/Order Process.bpmn}, {@code assets/Payment Process.bpmn}) — nothing changed in the BPMN.
 * What changed is the worker: {@code CreditCardService.chargeAmount} validates {@code expiryDate}
 * with a naive "exactly 5 characters" check (e.g. {@code "09/28"} is valid, {@code "2027/09"} is
 * not); on an invalid date it fails the {@code credit-card-charging} job with zero retries, which
 * Zeebe turns into an incident on that job.
 *
 * <p>This test covers both the happy path (unchanged from exercise 07) and the incident path: an
 * order with an invalid {@code expiryDate} leaves {@code credit-card-charging} incident-blocked,
 * which in turn leaves {@code OrderProcess} stuck waiting on {@code paymentCompletedMessage} — the
 * incident is asserted but not resolved, since resolving it is outside this exercise's scope.
 *
 * <h2>How to run</h2>
 *
 * <ol>
 *   <li>Start the runtime: {@code ./start-runtime.sh} (Camunda on :26500 / :8080 / :9600).</li>
 *   <li>Start ONE worker implementation, pointing it at that runtime (see cpt-test/README.md).</li>
 *   <li>Run the test, naming the language you started:
 *       {@code mvn test -Dtest=Exercise08Test -Dlang=python}.</li>
 * </ol>
 *
 * <p>The {@code -Dlang} value must match the worker you started. If it names a language with no
 * implementation on this branch, the test is <em>skipped</em>, not failed.
 *
 * <p>CPT connects to the same runtime in {@code remote} mode (see
 * {@code camunda-container-runtime.properties}) and deletes all data between test methods, so each
 * scenario runs against a clean state.
 *
 * <p><b>Note on python:</b> unlike java/js/csharp/java-spring (which fail the job with {@code
 * retries=0} immediately), python's {@code credit_card_charging_handler} decrements {@code
 * job.retries - 1} instead — with Zeebe's default of 3 retries, it takes 3 failed attempts (with a
 * retry backoff in between) before the incident actually appears. {@code hasActiveIncidents()}
 * polls/awaits, so the test still passes, just slower on python than on the other languages.
 */
@CamundaProcessTest
class Exercise08Test {

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
            "Skipping Exercise08Test: none of the requested languages ("
                + String.join(", ", Languages.requested())
                + ") has an implementation on this branch. Available: "
                + Languages.ALL.stream().filter(Languages::isAvailable).toList());
  }

  @Test
  @DisplayName(
      "payWithCreditCard: credit (30) < total (45.99), valid expiry date -> both PaymentProcess"
          + " service tasks run, both processes complete")
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

    // then: OrderProcess runs end-to-end.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(
            ORDER_START, GENERATE_ORDER_ID, INVOKE_PAYMENT, PAYMENT_COMPLETED_CATCH, ORDER_END);
    CamundaAssert.assertThat(orderInstance).isCompleted();

    // and: the indirectly-started PaymentProcess ran the full path (deduct credit -> fee -> charge
    // card) and completed, with no incidents.
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
      "payWithInvalidExpiryDate: expiry date is not 5 characters -> credit-card-charging fails,"
          + " incident raised, OrderProcess left waiting")
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

    // then: PaymentProcess gets as far as credit-card-charging, which fails with zero retries and
    // raises an incident — the job (and the process) stay active, not completed.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).isActive();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasCompletedElements(PAYMENT_START, DEDUCT_CREDIT, GATEWAY_CREDIT_SUFFICIENT, ADD_CARD_FEE);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).hasActiveElements(CHARGE_CARD);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).hasActiveIncidents();

    // and: OrderProcess is left waiting — payment-completion never runs, so
    // paymentCompletedMessage is never published and the catch event never resolves. Resolving
    // the incident is outside this exercise's scope, so the test stops here.
    CamundaAssert.assertThat(orderInstance).isActive();
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(ORDER_START, GENERATE_ORDER_ID, INVOKE_PAYMENT);
    CamundaAssert.assertThat(orderInstance).isWaitingForMessage("paymentCompletedMessage");
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
