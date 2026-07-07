package com.camunda.training;

import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTest;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.testCases.ImmutableTestCase;
import io.camunda.process.test.api.testCases.TestCase;
import io.camunda.process.test.api.testCases.TestCaseInstruction;
import io.camunda.process.test.api.testCases.TestCaseInstructionType;
import io.camunda.process.test.api.testCases.TestCaseRunner;
import io.camunda.process.test.impl.testCases.CamundaTestCaseRunner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Replays two Camunda Play exports via the CPT JSON test-case replay API ({@code
 * TestCasesReader} / {@code CamundaTestCaseRunner}, alpha in 8.10.0-alpha3-rc2), so the REAL job
 * workers (started manually, see {@code Exercise10Test}) complete the jobs instead of the test
 * driver. {@code COMPLETE_USER_TASK} instructions are NOT stripped — nothing automated completes
 * the "Check failed payment data" user task, so the scenario itself has to play that role, exactly
 * like {@code Exercise10Test} does directly via the API.
 *
 * <p><b>{@code Order process test scenarios.json}</b> — {@code OrderProcess test scenario}. Only
 * the success path is replayed here; the two user-task-driven paths are exercised only by the
 * hand-written test, since Play doesn't record user-task completions with specific variables.
 *
 * <p><b>{@code PaymentProcess test scenarios.json}</b> — three scenarios: {@code
 * pay-with-credit-card-msg} (happy path, no user task), {@code
 * pay-with-invalid-expiry-date-resolved} (boundary event -> user task completed with a corrected
 * {@code expiryDate} and {@code errorResolved: true} -> retried charge succeeds), and {@code
 * pay-with-invalid-expiry-date-unresolved} (user task completed with {@code errorResolved:
 * false} -> routes to {@code payment-failure}). All three start via {@code PUBLISH_MESSAGE},
 * since {@code PaymentProcess}'s start event is message-only.
 */
@CamundaProcessTest
class Exercise10ScenarioReplayTest {

  private static final String ORDER_BPMN_FILE = "Order Process.bpmn";
  private static final String PAYMENT_BPMN_FILE = "Payment Process.bpmn";
  private static final String CHECK_FAILED_PAYMENT_FORM_FILE = "Check Failed Payment Form.form";
  private static final String ORDER_SCENARIOS_FILE = "Order process test scenarios.json";
  private static final String PAYMENT_SCENARIOS_FILE = "PaymentProcess test scenarios.json";

  // Injected by CPT.
  private CamundaClient client;
  private CamundaProcessTestContext processTestContext;

  @BeforeEach
  void deploy() {
    client
        .newDeployResourceCommand()
        .addResourceFile(Languages.repoRoot().resolve("assets").resolve(ORDER_BPMN_FILE).toString())
        .addResourceFile(
            Languages.repoRoot().resolve("assets").resolve(PAYMENT_BPMN_FILE).toString())
        .addResourceFile(
            Languages.repoRoot()
                .resolve("assets")
                .resolve(CHECK_FAILED_PAYMENT_FORM_FILE)
                .toString())
        .send()
        .join();
  }

  @Test
  @DisplayName(
      "replay OrderProcess test scenario, real workers drive it through PaymentProcess and back"
          + " on the success path")
  void replayOrderProcessScenario() throws IOException {
    TestCase scenario = loadScenario(ORDER_SCENARIOS_FILE, "OrderProcess test scenario");
    TestCase withoutCompleteJob = withoutCompleteJobInstructions(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutCompleteJob);
  }

  @Test
  @DisplayName(
      "replay pay-with-credit-card-msg scenario, real worker completes both PaymentProcess jobs"
          + " on the first attempt")
  void replayPayWithCreditCardMsg() throws IOException {
    TestCase scenario = loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-credit-card-msg");
    TestCase withoutCompleteJob = withoutCompleteJobInstructions(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutCompleteJob);
  }

  @Test
  @DisplayName(
      "replay pay-with-invalid-expiry-date-resolved scenario, the scenario completes the user"
          + " task and the real worker retries credit-card-charging successfully")
  void replayPayWithInvalidExpiryDateResolved() throws IOException {
    TestCase scenario =
        loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-invalid-expiry-date-resolved");
    TestCase withoutCompleteJob = withoutCompleteJobInstructions(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutCompleteJob);
  }

  @Test
  @DisplayName(
      "replay pay-with-invalid-expiry-date-unresolved scenario, the scenario completes the user"
          + " task with errorResolved false and PaymentProcess routes to its failure end event")
  void replayPayWithInvalidExpiryDateUnresolved() throws IOException {
    TestCase scenario =
        loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-invalid-expiry-date-unresolved");
    TestCase withoutCompleteJob = withoutCompleteJobInstructions(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutCompleteJob);
  }

  private TestCase loadScenario(String fileName, String scenarioName) throws IOException {
    Path scenariosPath = Languages.repoRoot().resolve("assets").resolve(fileName);
    try (InputStream in = Files.newInputStream(scenariosPath)) {
      return new io.camunda.process.test.impl.testCases.TestCasesReader().read(in).getTestCases()
          .stream()
          .filter(tc -> tc.getName().equals(scenarioName))
          .findFirst()
          .orElseThrow(
              () ->
                  new AssertionError(
                      "No test case named '" + scenarioName + "' in " + scenariosPath));
    }
  }

  private TestCase withoutCompleteJobInstructions(TestCase original) {
    List<TestCaseInstruction> filtered =
        original.getInstructions().stream()
            .filter(i -> !TestCaseInstructionType.COMPLETE_JOB.equals(i.getType()))
            .collect(Collectors.toList());
    return ImmutableTestCase.builder().from(original).instructions(filtered).build();
  }
}
