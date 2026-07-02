package com.camunda.training;

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
 * Exercise 05 — Payment Process.
 *
 * <p>Verifies the {@code PaymentProcess} (see {@code assets/Payment Process.bpmn}) end-to-end
 * against the REAL job workers — the workers are NOT mocked. The two service tasks
 * ({@code credit-deduction}, {@code credit-card-charging}) are completed by whichever language
 * implementation you started manually (python / csharp / js).
 *
 * <h2>How to run</h2>
 *
 * <ol>
 *   <li>Start the runtime: {@code ./start-runtime.sh} (Camunda on :26500 / :8080 / :9600).</li>
 *   <li>Start ONE worker implementation, pointing it at that runtime (see cpt-test/README.md).</li>
 *   <li>Run the test, naming the language you started:
 *       {@code mvn test -Dtest=Exercise05Test -Dlang=python}.</li>
 * </ol>
 *
 * <p>The {@code -Dlang} value must match the worker you started. If it names a language with no
 * implementation on this branch (e.g. {@code java}), the test is <em>skipped</em>, not failed.
 *
 * <p>CPT connects to the same runtime in {@code remote} mode (see
 * {@code camunda-container-runtime.properties}) and deletes all data between test methods, so each
 * scenario runs against a clean state.
 */
@CamundaProcessTest
class Exercise05Test {

  private static final String PROCESS_ID = "PaymentProcess";
  private static final String BPMN_FILE = "Payment Process.bpmn";

  // BPMN element ids (from assets/Payment Process.bpmn).
  private static final String START = "Event_19qu0oi";
  private static final String DEDUCT_CREDIT = "Activity_1hu6sex"; // job type credit-deduction
  private static final String CHARGE_CARD = "Activity_16kuby1"; // job type credit-card-charging
  private static final String GATEWAY_CREDIT_SUFFICIENT = "Gateway_1xthnkz";
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
            "Skipping Exercise05Test: none of the requested languages ("
                + String.join(", ", Languages.requested())
                + ") has an implementation on this branch. Available: "
                + Languages.ALL.stream().filter(Languages::isAvailable).toList());
  }

  @Test
  @DisplayName("pay-with-credit-card: credit < total → both service tasks run, process completes")
  void payWithCreditCard() {
    // given: deploy the process and pick inputs where credit is NOT enough.
    deploy();

    // when: a payment is requested. The real workers complete both jobs.
    ProcessInstanceEvent instance =
        createInstance(Map.of("orderTotal", 45.99, "customerCredit", 20));

    // then: the full path is taken (deduct credit -> charge card) and the process completes.
    CamundaAssert.assertThat(instance).isCompleted();
    CamundaAssert.assertThat(instance)
        .hasCompletedElements(
            START,
            DEDUCT_CREDIT,
            GATEWAY_CREDIT_SUFFICIENT,
            CHARGE_CARD,
            GATEWAY_MERGE,
            END);
  }

  @Test
  @DisplayName("pay-with-credit-only: credit >= total → card charging is skipped, process completes")
  void payWithCreditOnly() {
    // given: deploy the process and pick inputs where credit IS enough.
    deploy();

    // when: a payment is requested. Only the credit-deduction worker is involved.
    ProcessInstanceEvent instance =
        createInstance(Map.of("orderTotal", 45.99, "customerCredit", 100));

    // then: card charging is skipped (gateway "Yes" branch) and the process completes.
    CamundaAssert.assertThat(instance).isCompleted();
    CamundaAssert.assertThat(instance)
        .hasCompletedElements(START, DEDUCT_CREDIT, GATEWAY_CREDIT_SUFFICIENT, GATEWAY_MERGE, END);
    CamundaAssert.assertThat(instance).hasNotActivatedElements(CHARGE_CARD);
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
