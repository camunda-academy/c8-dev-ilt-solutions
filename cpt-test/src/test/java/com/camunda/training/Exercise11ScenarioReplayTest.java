package com.camunda.training;

import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaProcessTest;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.testCases.ImmutableTestCase;
import io.camunda.process.test.api.testCases.TestCase;
import io.camunda.process.test.api.testCases.TestCaseInstruction;
import io.camunda.process.test.api.testCases.TestCaseInstructionType;
import io.camunda.process.test.api.testCases.TestCaseRunner;
import io.camunda.process.test.api.testCases.instructions.CompleteJobInstruction;
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
 * workers (started manually, see {@code Exercise11Test}) complete the jobs instead of the test
 * driver — except the connector job ({@code io.camunda:http-json:1}), which nothing in this setup
 * runs (see {@code Exercise11Test}'s class javadoc); its {@code COMPLETE_JOB} instruction is kept
 * so the scenario stubs it out itself, the same fabricated-data approach the hand-written test
 * uses directly via the API. {@code COMPLETE_USER_TASK} instructions are also kept — nothing
 * automated completes the "Check failed payment data" user task either.
 *
 * <p><b>{@code Order process test scenarios.json}</b> — {@code OrderProcess test scenario}. Only
 * the success path is replayed here; the two user-task-driven paths are exercised only by the
 * hand-written test, since Play doesn't record user-task completions with specific variables.
 *
 * <p><b>{@code PaymentProcess test scenarios.json}</b> — carried over unchanged from exercise 10:
 * {@code pay-with-credit-card-msg}, {@code pay-with-invalid-expiry-date-resolved}, {@code
 * pay-with-invalid-expiry-date-unresolved}. {@code PaymentProcess} itself didn't change in this
 * exercise, so these scenarios need no adaptation.
 */
@CamundaProcessTest
class Exercise11ScenarioReplayTest {

  private static final String ORDER_BPMN_FILE = "Order Process.bpmn";
  private static final String PAYMENT_BPMN_FILE = "Payment Process.bpmn";
  private static final String CHECK_FAILED_PAYMENT_FORM_FILE = "Check Failed Payment Form.form";
  private static final String ORDER_SCENARIOS_FILE = "Order process test scenarios.json";
  private static final String PAYMENT_SCENARIOS_FILE = "PaymentProcess test scenarios.json";

  private static final String FETCH_PRODUCT_INFO_JOB_TYPE = "io.camunda:http-json:1";

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
      "replay OrderProcess test scenario, the scenario stubs the connector job and the real"
          + " worker drives it through PaymentProcess and back on the success path")
  void replayOrderProcessScenario() throws IOException {
    TestCase scenario = loadScenario(ORDER_SCENARIOS_FILE, "OrderProcess test scenario");
    TestCase withoutWorkerCompletedJobs = withoutCompleteJobInstructionsExcept(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutWorkerCompletedJobs);
  }

  @Test
  @DisplayName(
      "replay pay-with-credit-card-msg scenario, real worker completes both PaymentProcess jobs"
          + " on the first attempt")
  void replayPayWithCreditCardMsg() throws IOException {
    TestCase scenario = loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-credit-card-msg");
    TestCase withoutWorkerCompletedJobs = withoutCompleteJobInstructionsExcept(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutWorkerCompletedJobs);
  }

  @Test
  @DisplayName(
      "replay pay-with-invalid-expiry-date-resolved scenario, the scenario completes the user"
          + " task and the real worker retries credit-card-charging successfully")
  void replayPayWithInvalidExpiryDateResolved() throws IOException {
    TestCase scenario =
        loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-invalid-expiry-date-resolved");
    TestCase withoutWorkerCompletedJobs = withoutCompleteJobInstructionsExcept(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutWorkerCompletedJobs);
  }

  @Test
  @DisplayName(
      "replay pay-with-invalid-expiry-date-unresolved scenario, the scenario completes the user"
          + " task with errorResolved false and PaymentProcess routes to its failure end event")
  void replayPayWithInvalidExpiryDateUnresolved() throws IOException {
    TestCase scenario =
        loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-invalid-expiry-date-unresolved");
    TestCase withoutWorkerCompletedJobs = withoutCompleteJobInstructionsExcept(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutWorkerCompletedJobs);
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

  /**
   * Strips {@code COMPLETE_JOB} instructions for job types a real worker completes, but keeps the
   * one for {@code io.camunda:http-json:1} — nothing in this setup runs a Connector Runtime, so
   * the scenario has to stub that job itself (see class javadoc).
   */
  private TestCase withoutCompleteJobInstructionsExcept(TestCase original) {
    List<TestCaseInstruction> filtered =
        original.getInstructions().stream()
            .filter(
                i ->
                    !TestCaseInstructionType.COMPLETE_JOB.equals(i.getType())
                        || (i instanceof CompleteJobInstruction completeJob
                            && FETCH_PRODUCT_INFO_JOB_TYPE.equals(
                                completeJob.getJobSelector().getJobType().orElse(null))))
            .collect(Collectors.toList());
    return ImmutableTestCase.builder().from(original).instructions(filtered).build();
  }
}
