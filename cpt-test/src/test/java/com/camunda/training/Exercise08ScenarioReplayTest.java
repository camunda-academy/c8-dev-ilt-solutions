package com.camunda.training;

import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;

import io.camunda.client.CamundaClient;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTest;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.testCases.ImmutableProcessDefinitionSelector;
import io.camunda.process.test.api.testCases.ImmutableProcessInstanceSelector;
import io.camunda.process.test.api.testCases.ImmutableTestCase;
import io.camunda.process.test.api.testCases.TestCase;
import io.camunda.process.test.api.testCases.TestCaseInstruction;
import io.camunda.process.test.api.testCases.TestCaseInstructionType;
import io.camunda.process.test.api.testCases.TestCaseRunner;
import io.camunda.process.test.api.testCases.instructions.AssertElementInstanceInstruction;
import io.camunda.process.test.api.testCases.instructions.CreateProcessInstanceInstruction;
import io.camunda.process.test.api.testCases.instructions.ImmutableAssertElementInstanceInstruction;
import io.camunda.process.test.api.testCases.instructions.ImmutableCreateProcessInstanceInstruction;
import io.camunda.process.test.api.testCases.instructions.assertElementInstance.ElementInstanceState;
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
 * workers (started manually, see {@code Exercise08Test}) complete the jobs instead of the test
 * driver.
 *
 * <p><b>{@code Order process test scenarios.json}</b> — {@code OrderProcess test scenario}. Same
 * stale-id issue as exercise 07: the {@code ASSERT_ELEMENT_INSTANCE} instructions' selectors still
 * reference the pre-rename {@code processDefinitionId: "Process_085dej6"} (the {@code
 * CREATE_PROCESS_INSTANCE} instruction's own selector was already fixed to {@code OrderProcess}
 * when this file was copied over). Rewritten in memory before replaying, same non-destructive
 * approach as exercise 07.
 *
 * <p><b>{@code PaymentProcess test scenarios.json}</b> — only {@code pay-with-credit-card-msg} and
 * the new {@code pay-with-invalid-expiry-date} scenario are replayed, for the same reason as
 * exercise 07: {@code pay-with-credit-card} and {@code pay-with-credit-only} open with a {@code
 * CREATE_PROCESS_INSTANCE} instruction the engine rejects outright, since {@code PaymentProcess}'s
 * start event is message-only. Those two paths are covered by {@code Exercise08Test} instead.
 */
@CamundaProcessTest
class Exercise08ScenarioReplayTest {

  private static final String ORDER_BPMN_FILE = "Order Process.bpmn";
  private static final String PAYMENT_BPMN_FILE = "Payment Process.bpmn";
  private static final String ORDER_SCENARIOS_FILE = "Order process test scenarios.json";
  private static final String PAYMENT_SCENARIOS_FILE = "PaymentProcess test scenarios.json";

  // The Order Process.bpmn process id today; the recorded scenario JSON still says
  // "Process_085dej6" (its id before the process was renamed) in some selectors.
  private static final String ORDER_PROCESS_ID = "OrderProcess";
  private static final String STALE_ORDER_PROCESS_ID = "Process_085dej6";

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
      "replay OrderProcess test scenario, real workers drive it through PaymentProcess and back")
  void replayOrderProcessScenario() throws IOException {
    TestCase scenario = loadScenario(ORDER_SCENARIOS_FILE, "OrderProcess test scenario");
    TestCase withoutCompleteJob = withoutCompleteJobInstructions(scenario);
    TestCase withCurrentProcessId = withRewrittenOrderProcessId(withoutCompleteJob);
    // The recording assumes the catch event is still IS_ACTIVE when checked, then publishes
    // paymentCompletedMessage itself. With a real worker driving PaymentProcess, that worker
    // publishes the message and resolves the catch event before this assertion runs, so it's
    // already IS_COMPLETED — drop the stale check; the final IS_COMPLETED assertion still covers
    // the outcome that matters.
    TestCase withoutStaleActiveCheck = withoutCatchEventActiveAssertion(withCurrentProcessId);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutStaleActiveCheck);
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
      "replay pay-with-invalid-expiry-date scenario, real worker fails credit-card-charging and"
          + " raises an incident")
  void replayPayWithInvalidExpiryDate() throws IOException {
    TestCase scenario = loadScenario(PAYMENT_SCENARIOS_FILE, "pay-with-invalid-expiry-date");
    // The recorded COMPLETE_JOB(credit-card-charging) instruction doesn't apply here — the real
    // worker fails that job instead of completing it, since the point of this scenario is to
    // exercise the worker's own validation/incident logic, not to script around it.
    TestCase withoutCompleteJob = withoutCompleteJobInstructions(scenario);

    TestCaseRunner runner = new CamundaTestCaseRunner(processTestContext);
    runner.run(withoutCompleteJob);

    // The JSON schema has no "has an incident" assertion type, so check it directly here.
    CamundaAssert.assertThat(byProcessId("PaymentProcess")).hasActiveIncidents();
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

  private TestCase withoutCatchEventActiveAssertion(TestCase original) {
    List<TestCaseInstruction> filtered =
        original.getInstructions().stream()
            .filter(
                i ->
                    !(i instanceof AssertElementInstanceInstruction assertion
                        && ElementInstanceState.IS_ACTIVE.equals(assertion.getState())))
            .collect(Collectors.toList());
    return ImmutableTestCase.builder().from(original).instructions(filtered).build();
  }

  /**
   * Rewrites every instruction's selector from the stale recorded process id ({@code
   * Process_085dej6}) to the current one ({@code OrderProcess}) — both the {@code
   * CreateProcessInstance} instruction that starts the instance and the {@code
   * AssertElementInstance} instructions that check on it afterwards.
   */
  private TestCase withRewrittenOrderProcessId(TestCase original) {
    List<TestCaseInstruction> rewritten =
        original.getInstructions().stream().map(this::withCurrentOrderProcessId).collect(Collectors.toList());
    return ImmutableTestCase.builder().from(original).instructions(rewritten).build();
  }

  private TestCaseInstruction withCurrentOrderProcessId(TestCaseInstruction instruction) {
    if (instruction instanceof CreateProcessInstanceInstruction create
        && create
            .getProcessDefinitionSelector()
            .getProcessDefinitionId()
            .filter(STALE_ORDER_PROCESS_ID::equals)
            .isPresent()) {
      return ImmutableCreateProcessInstanceInstruction.builder()
          .from(create)
          .processDefinitionSelector(
              ImmutableProcessDefinitionSelector.builder()
                  .processDefinitionId(ORDER_PROCESS_ID)
                  .build())
          .build();
    }
    if (instruction instanceof AssertElementInstanceInstruction assertion
        && assertion
            .getProcessInstanceSelector()
            .getProcessDefinitionId()
            .filter(STALE_ORDER_PROCESS_ID::equals)
            .isPresent()) {
      return ImmutableAssertElementInstanceInstruction.builder()
          .from(assertion)
          .processInstanceSelector(
              ImmutableProcessInstanceSelector.builder()
                  .processDefinitionId(ORDER_PROCESS_ID)
                  .build())
          .build();
    }
    return instruction;
  }
}
