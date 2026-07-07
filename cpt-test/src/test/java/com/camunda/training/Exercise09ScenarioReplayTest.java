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
 * workers (started manually, see {@code Exercise09Test}) complete the jobs instead of the test
 * driver.
 *
 * <p><b>{@code Order process test scenarios.json}</b> — {@code OrderProcess test scenario}. Only
 * the success path is replayed here (equivalent to {@code Exercise09Test.payWithCreditCard}); the
 * failure path (event-based gateway routing to {@code paymentFailedMessage}) is exercised only by
 * the hand-written test, since Play doesn't record BPMN-error-driven paths.
 *
 * <p><b>{@code PaymentProcess test scenarios.json}</b> — {@code pay-with-credit-card-msg} (happy
 * path) and {@code pay-with-invalid-expiry-date} (the boundary-event failure path introduced in
 * this exercise). Both start via {@code PUBLISH_MESSAGE}, since {@code PaymentProcess}'s start
 * event is message-only — a plain {@code CREATE_PROCESS_INSTANCE} instruction (as Play would
 * record before this exercise's redesign) is rejected by the engine outright.
 */
@CamundaProcessTest
class Exercise09ScenarioReplayTest {

  private static final String ORDER_BPMN_FILE = "Order Process.bpmn";
  private static final String PAYMENT_BPMN_FILE = "Payment Process.bpmn";
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
      "replay pay-with-credit-card-msg scenario, real worker completes both PaymentProcess jobs")
  void replayPayWithCreditCardMsg() throws IOException {
    TestCase scenario = loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-credit-card-msg");
    TestCase withoutCompleteJob = withoutCompleteJobInstructions(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutCompleteJob);
  }

  @Test
  @DisplayName(
      "replay pay-with-invalid-expiry-date scenario, real worker throws a BPMN error and"
          + " PaymentProcess completes on its failure end event")
  void replayPayWithInvalidExpiryDate() throws IOException {
    TestCase scenario = loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-invalid-expiry-date");
    // The recorded COMPLETE_JOB(credit-card-charging) instruction doesn't apply here — the real
    // worker throws a BPMN error out of that job instead of completing it, since the point of
    // this scenario is to exercise the worker's own error-throwing logic, not to script around it.
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
