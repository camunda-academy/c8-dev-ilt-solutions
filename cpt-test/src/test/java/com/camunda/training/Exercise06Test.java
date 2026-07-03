package com.camunda.training;

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
 * Exercise 06 — Payment Process with server-side credit lookup and a card fee.
 *
 * <p>Verifies the {@code PaymentProcess} (see {@code assets/Payment Process.bpmn}) end-to-end
 * against the REAL job workers — the workers are NOT mocked. Unlike exercise 05, the caller no
 * longer supplies {@code customerCredit} directly: {@code credit-deduction} looks the customer's
 * credit up from {@code customerId} (the last two characters, e.g. {@code "cust30"} -> credit 30)
 * and returns {@code openAmount = orderTotal - credit} as a process variable. A new script task
 * ({@code Add credit card fee}) applies a 2% surcharge to {@code openAmount} before
 * {@code credit-card-charging} runs.
 *
 * <h2>How to run</h2>
 *
 * <ol>
 *   <li>Start the runtime: {@code ./start-runtime.sh} (Camunda on :26500 / :8080 / :9600).</li>
 *   <li>Start ONE worker implementation, pointing it at that runtime (see cpt-test/README.md).</li>
 *   <li>Run the test, naming the language you started:
 *       {@code mvn test -Dtest=Exercise06Test -Dlang=python}.</li>
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
class Exercise06Test {

  private static final String PROCESS_ID = "PaymentProcess";
  private static final String BPMN_FILE = "Payment Process.bpmn";

  // BPMN element ids (from assets/Payment Process.bpmn).
  private static final String START = "Event_19qu0oi";
  private static final String DEDUCT_CREDIT = "Activity_1hu6sex"; // job type credit-deduction
  private static final String GATEWAY_CREDIT_SUFFICIENT = "Gateway_1xthnkz";
  private static final String ADD_CARD_FEE = "Activity_1cfafq9"; // script task: openAmount * 1.02
  private static final String CHARGE_CARD = "Activity_16kuby1"; // job type credit-card-charging
  private static final String GATEWAY_MERGE = "Gateway_02roc4u";
  private static final String END = "Event_03bvprt";

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
            "Skipping Exercise06Test: none of the requested languages ("
                + String.join(", ", Languages.requested())
                + ") has an implementation on this branch. Available: "
                + Languages.ALL.stream().filter(Languages::isAvailable).toList());
  }

  @Test
  @DisplayName(
      "pay-with-credit-card: credit (30) < total (45.99) -> card fee applied, both service tasks"
          + " run, process completes")
  void payWithCreditCard() {
    // given: deploy the process and pick a customer whose credit is NOT enough (cust30 -> 30).
    deploy();

    // when: a payment is requested. The real workers complete both jobs; the script task applies
    // the 2% card fee to openAmount = 45.99 - 30 = 15.99 in between.
    ProcessInstanceEvent instance =
        createInstance(
            Map.of(
                "orderTotal", 45.99,
                "customerId", "cust30",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "09/28"));

    // then: the full path is taken (deduct credit -> fee -> charge card) and the process
    // completes, with the fee having been applied on top of the deducted amount.
    CamundaAssert.assertThat(instance).isCompleted();
    CamundaAssert.assertThat(instance)
        .hasCompletedElements(
            START,
            DEDUCT_CREDIT,
            GATEWAY_CREDIT_SUFFICIENT,
            ADD_CARD_FEE,
            CHARGE_CARD,
            GATEWAY_MERGE,
            END);
    // Exact equality would be brittle here: each language computes 45.99 - 30 with its own
    // floating-point rounding (e.g. Python yields 15.990000000000002, not 15.99), so compare
    // with a small tolerance instead of an exact match.
    CamundaAssert.assertThat(instance)
        .hasVariableSatisfies(
            "openAmount",
            Double.class,
            openAmount -> assertThat(openAmount).isCloseTo(15.99 * 1.02, within(0.01)));
  }

  @Test
  @DisplayName(
      "pay-with-credit-only: credit (70) >= total (45.99) -> card charging and fee are skipped,"
          + " process completes")
  void payWithCreditOnly() {
    // given: deploy the process and pick a customer whose credit IS enough (cust70 -> 70).
    deploy();

    // when: a payment is requested. Only the credit-deduction worker is involved.
    ProcessInstanceEvent instance =
        createInstance(
            Map.of(
                "orderTotal", 45.99,
                "customerId", "cust70",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "09/28"));

    // then: the card fee and card charging are skipped (gateway "Yes" branch) and the process
    // completes with a non-positive openAmount.
    CamundaAssert.assertThat(instance).isCompleted();
    CamundaAssert.assertThat(instance)
        .hasCompletedElements(START, DEDUCT_CREDIT, GATEWAY_CREDIT_SUFFICIENT, GATEWAY_MERGE, END);
    CamundaAssert.assertThat(instance).hasNotActivatedElements(ADD_CARD_FEE, CHARGE_CARD);
  }

  private void deploy() {
    client
        .newDeployResourceCommand()
        .addResourceFile(Languages.repoRoot().resolve("assets").resolve(BPMN_FILE).toString())
        .send()
        .join();
  }

  private ProcessInstanceEvent createInstance(Map<String, Object> variables) {
    return client
        .newCreateInstanceCommand()
        .bpmnProcessId(PROCESS_ID)
        .latestVersion()
        .variables(variables)
        .send()
        .join();
  }
}
