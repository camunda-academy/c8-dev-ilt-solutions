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
 * Replays {@code assets/PaymentProcess test scenarios.json} (a Camunda Play export, already
 * recorded without {@code COMPLETE_JOB} instructions on this branch) via the CPT JSON test-case
 * replay API ({@code TestCasesReader} / {@code CamundaTestCaseRunner}, alpha in
 * 8.10.0-alpha3-rc2), so the REAL job worker (started manually, see {@code Exercise06Test})
 * completes the jobs instead of the test driver.
 *
 * <p>Covers both recorded scenarios, exercising every service task in the process:
 * {@code pay-with-credit-card} (credit-deduction + credit-card-charging) and
 * {@code pay-with-credit-only} (credit-deduction only).
 */
@CamundaProcessTest
class Exercise06ScenarioReplayTest {

  private static final String BPMN_FILE = "Payment Process.bpmn";
  private static final String SCENARIOS_FILE = "PaymentProcess test scenarios.json";

  // Injected by CPT.
  private CamundaClient client;
  private CamundaProcessTestContext processTestContext;

  @BeforeEach
  void deploy() {
    client
        .newDeployResourceCommand()
        .addResourceFile(Languages.repoRoot().resolve("assets").resolve(BPMN_FILE).toString())
        .send()
        .join();
  }

  @Test
  @DisplayName("replay pay-with-credit-card scenario, real worker completes both jobs")
  void replayPayWithCreditCard() throws IOException {
    replay("pay-with-credit-card");
  }

  @Test
  @DisplayName("replay pay-with-credit-only scenario, real worker completes credit-deduction")
  void replayPayWithCreditOnly() throws IOException {
    replay("pay-with-credit-only");
  }

  private void replay(String scenarioName) throws IOException {
    TestCase scenario = loadScenario(scenarioName);
    TestCase withoutCompleteJob = withoutCompleteJobInstructions(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutCompleteJob);
  }

  private TestCase loadScenario(String name) throws IOException {
    Path scenariosPath = Languages.repoRoot().resolve("assets").resolve(SCENARIOS_FILE);
    try (InputStream in = Files.newInputStream(scenariosPath)) {
      return new io.camunda.process.test.impl.testCases.TestCasesReader().read(in).getTestCases()
          .stream()
          .filter(tc -> tc.getName().equals(name))
          .findFirst()
          .orElseThrow(
              () -> new AssertionError("No test case named '" + name + "' in " + scenariosPath));
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
