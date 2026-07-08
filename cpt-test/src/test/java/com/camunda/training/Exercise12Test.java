package com.camunda.training;

import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byProcessId;
import static io.camunda.process.test.api.assertions.UserTaskSelectors.byElementId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercise 12 — DMN: {@code OrderProcess} looks up a discount percentage via a business rule task
 * before invoking payment.
 *
 * <p>Identical to exercise 11 except for two new steps between {@code Fetch product info} and
 * {@code Invoke payment} (see {@code assets/Order Process.bpmn}): a business rule task ({@code
 * Activity_1816dga}, "Determine discount") evaluates the {@code orderDiscount} decision in {@code
 * assets/Order Discount DRD.dmn} against {@code productPrice}, producing a {@code discount}
 * percentage (0/2/3/4, based on price bands); a script task ({@code Activity_0gzb3lz}, "Subtract
 * discount") then computes {@code discountedAmount = productPrice - (productPrice * discount /
 * 100)}. {@code Invoke payment}'s {@code ioMapping} now maps {@code discountedAmount} onto {@code
 * orderTotal}, not {@code productPrice} directly.
 *
 * <p>The stubbed connector response (see below and exercise 11's javadoc) uses {@code
 * productPrice = 45.99}, which falls in the DMN's {@code [40.00..60.00[} band -> {@code discount
 * = 2} -> {@code discountedAmount = 45.99 - (45.99 * 2 / 100) = 45.0702}. That's still more than
 * {@code cust30}'s credit (30), so the fee/charge path is still exercised exactly like exercises
 * 10 and 11.
 *
 * <p>This test still does NOT call the real dummyjson.com API — same reasoning as exercise 11
 * (no Connector Runtime in this setup, and the endpoint needs a real, expiring bearer token). The
 * business rule task DOES run for real: DMN decision evaluation happens in the broker itself, no
 * separate runtime needed, so {@code Order Discount DRD.dmn} just needs to be deployed alongside
 * the BPMN files.
 *
 * <h2>How to run</h2>
 *
 * <ol>
 *   <li>Start the runtime: {@code ./start-runtime.sh} (Camunda on :26500 / :8080 / :9600).</li>
 *   <li>Start ONE worker implementation, pointing it at that runtime (see cpt-test/README.md).</li>
 *   <li>Run the test, naming the language you started:
 *       {@code mvn test -Dtest=Exercise12Test -Dlang=python}.</li>
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
class Exercise12Test {

  private static final String ORDER_PROCESS_ID = "OrderProcess";
  private static final String PAYMENT_PROCESS_ID = "PaymentProcess";
  private static final String ORDER_BPMN_FILE = "Order Process.bpmn";
  private static final String PAYMENT_BPMN_FILE = "Payment Process.bpmn";
  private static final String ORDER_DISCOUNT_DMN_FILE = "Order Discount DRD.dmn";
  private static final String CHECK_FAILED_PAYMENT_FORM_FILE = "Check Failed Payment Form.form";

  private static final String FETCH_PRODUCT_INFO_JOB_TYPE = "io.camunda:http-json:1";
  // Fabricated stand-in for a real dummyjson.com response — see class javadoc. Falls in the DMN's
  // [40.00..60.00[ band, so discount = 2 and discountedAmount = 45.0702.
  private static final double STUBBED_PRODUCT_PRICE = 45.99;
  private static final double EXPECTED_DISCOUNTED_AMOUNT = 45.0702;

  // OrderProcess element ids (from assets/Order Process.bpmn — unchanged from exercise 11 except
  // for the two new elements below).
  private static final String ORDER_START = "StartEvent_1";
  private static final String GENERATE_ORDER_ID = "Activity_1omjax4"; // script task
  private static final String FETCH_PRODUCT_INFO = "Activity_1uf1u3f"; // connector service task
  private static final String DETERMINE_DISCOUNT = "Activity_1816dga"; // business rule task
  private static final String SUBTRACT_DISCOUNT = "Activity_0gzb3lz"; // script task
  private static final String INVOKE_PAYMENT = "Activity_1s27spe"; // job type payment-invocation
  private static final String ORDER_GATEWAY = "Gateway_0moymkj"; // event-based gateway
  private static final String PAYMENT_COMPLETED_CATCH = "Event_0bpmwgs";
  private static final String ORDER_COMPLETED_END = "Event_0ttkttq";
  private static final String PAYMENT_FAILED_CATCH = "Event_1vantxm";
  private static final String ORDER_FAILED_END = "Event_0an4trg";

  // PaymentProcess element ids (from assets/Payment Process.bpmn — unchanged from exercise 10/11).
  private static final String PAYMENT_START = "Event_19qu0oi";
  private static final String DEDUCT_CREDIT = "Activity_1hu6sex"; // job type credit-deduction
  private static final String GATEWAY_CREDIT_SUFFICIENT = "Gateway_1xthnkz";
  private static final String ADD_CARD_FEE = "Activity_1cfafq9"; // script task: openAmount * 1.02
  private static final String RETRY_MERGE = "Gateway_00z7p4e"; // merges first attempt + retry
  private static final String CHARGE_CARD = "Activity_16kuby1"; // job type credit-card-charging
  private static final String CHARGING_FAILED_BOUNDARY = "Event_1605nvk"; // catches creditCardChargeError
  private static final String CHECK_FAILED_PAYMENT = "Activity_1884gkf"; // user task
  private static final String RESOLVABLE_GATEWAY = "Gateway_0kf89ck";
  private static final String GATEWAY_MERGE = "Gateway_02roc4u";
  private static final String PAYMENT_END = "Event_03bvprt"; // job type payment-completion
  private static final String PAYMENT_FAILED_END = "Event_0rols7b"; // job type payment-failure

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
            "Skipping Exercise12Test: none of the requested languages ("
                + String.join(", ", Languages.requested())
                + ") has an implementation on this branch. Available: "
                + Languages.ALL.stream().filter(Languages::isAvailable).toList());
  }

  @Test
  @DisplayName(
      "payWithCreditCard: valid expiry date from the start -> credit-card-charging succeeds on"
          + " the first attempt, no user task involved")
  void payWithCreditCard() {
    // given: deploy both processes, the discount DMN, and pick a customer whose credit is NOT
    // enough (cust30 -> 30).
    deploy();

    // when: an order is placed by product id with a valid (5-character) expiry date.
    ProcessInstanceEvent orderInstance =
        createOrderInstance(
            Map.of(
                "productId", "1",
                "customerId", "cust30",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "09/28"));
    completeFetchProductInfoJob();

    // then: OrderProcess evaluates the discount, then takes the success branch off the
    // event-based gateway.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(
            ORDER_START,
            GENERATE_ORDER_ID,
            FETCH_PRODUCT_INFO,
            DETERMINE_DISCOUNT,
            SUBTRACT_DISCOUNT,
            INVOKE_PAYMENT,
            ORDER_GATEWAY,
            PAYMENT_COMPLETED_CATCH,
            ORDER_COMPLETED_END);
    CamundaAssert.assertThat(orderInstance).isCompleted();
    CamundaAssert.assertThat(orderInstance)
        .hasVariableSatisfies(
            "discount", Integer.class, discount -> assertThat(discount).isEqualTo(2));
    CamundaAssert.assertThat(orderInstance)
        .hasVariableSatisfies(
            "discountedAmount",
            Double.class,
            discountedAmount ->
                assertThat(discountedAmount).isCloseTo(EXPECTED_DISCOUNTED_AMOUNT, within(0.001)));

    // and: PaymentProcess completed without ever touching the boundary event or the user task,
    // using the discounted amount as orderTotal.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasCompletedElements(
            PAYMENT_START,
            DEDUCT_CREDIT,
            GATEWAY_CREDIT_SUFFICIENT,
            ADD_CARD_FEE,
            RETRY_MERGE,
            CHARGE_CARD,
            GATEWAY_MERGE,
            PAYMENT_END);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).isCompleted();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasNotActivatedElements(CHARGING_FAILED_BOUNDARY, CHECK_FAILED_PAYMENT, PAYMENT_FAILED_END);
  }

  @Test
  @DisplayName(
      "payWithInvalidExpiryDateResolved: invalid expiry date -> user task corrects it and marks"
          + " it resolved -> credit-card-charging is retried and succeeds")
  void payWithInvalidExpiryDateResolved() {
    // given: deploy both processes, the discount DMN, and pick a customer whose credit is NOT
    // enough (cust30 -> 30).
    deploy();

    // when: an order is placed by product id with an invalid (non-5-character) expiry date.
    ProcessInstanceEvent orderInstance =
        createOrderInstance(
            Map.of(
                "productId", "1",
                "customerId", "cust30",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "2027/09"));
    completeFetchProductInfoJob();

    // and: the user reviews the failed payment, fixes the expiry date, and marks it resolved.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasCompletedElements(PAYMENT_START, DEDUCT_CREDIT, GATEWAY_CREDIT_SUFFICIENT, ADD_CARD_FEE);
    CamundaAssert.assertThat(byElementId(CHECK_FAILED_PAYMENT)).isCreated();
    completeCheckFailedPaymentTask(Map.of("expiryDate", "09/28", "errorResolved", true));

    // then: credit-card-charging is retried (without a second boundary-event trip) and succeeds,
    // so PaymentProcess completes on its success end event, same as the happy path.
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
            CHECK_FAILED_PAYMENT,
            RESOLVABLE_GATEWAY,
            RETRY_MERGE,
            CHARGE_CARD,
            GATEWAY_MERGE,
            PAYMENT_END);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).isCompleted();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).hasNotActivatedElements(PAYMENT_FAILED_END);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).hasCompletedElement(CHARGE_CARD, 1);

    // and: OrderProcess sees the eventual success, not the intermediate failure.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(
            ORDER_START,
            GENERATE_ORDER_ID,
            FETCH_PRODUCT_INFO,
            DETERMINE_DISCOUNT,
            SUBTRACT_DISCOUNT,
            INVOKE_PAYMENT,
            ORDER_GATEWAY,
            PAYMENT_COMPLETED_CATCH,
            ORDER_COMPLETED_END);
    CamundaAssert.assertThat(orderInstance).isCompleted();
    CamundaAssert.assertThat(orderInstance)
        .hasNotActivatedElements(PAYMENT_FAILED_CATCH, ORDER_FAILED_END);
  }

  @Test
  @DisplayName(
      "payWithInvalidExpiryDateUnresolved: invalid expiry date -> user task marks it unresolved"
          + " -> PaymentProcess and OrderProcess both complete on their failure paths")
  void payWithInvalidExpiryDateUnresolved() {
    // given: deploy both processes, the discount DMN, and pick a customer whose credit is NOT
    // enough (cust30 -> 30).
    deploy();

    // when: an order is placed by product id with an invalid (non-5-character) expiry date.
    ProcessInstanceEvent orderInstance =
        createOrderInstance(
            Map.of(
                "productId", "1",
                "customerId", "cust30",
                "cardNumber", "1234 5678",
                "cvc", "123",
                "expiryDate", "2027/09"));
    completeFetchProductInfoJob();

    // and: the user reviews the failed payment but cannot fix it.
    CamundaAssert.assertThat(byElementId(CHECK_FAILED_PAYMENT)).isCreated();
    completeCheckFailedPaymentTask(Map.of("expiryDate", "2027/09", "errorResolved", false));

    // then: PaymentProcess routes to its failure end event without ever retrying the charge.
    // Gateway_00z7p4e still activates once — it also sits on the FIRST attempt's path (from the
    // card-fee script task), before credit-card-charging ever fails.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID))
        .hasCompletedElements(
            PAYMENT_START,
            DEDUCT_CREDIT,
            GATEWAY_CREDIT_SUFFICIENT,
            ADD_CARD_FEE,
            RETRY_MERGE,
            CHARGING_FAILED_BOUNDARY,
            CHECK_FAILED_PAYMENT,
            RESOLVABLE_GATEWAY,
            PAYMENT_FAILED_END);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).isCompleted();
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).hasNotActivatedElements(PAYMENT_END);
    CamundaAssert.assertThat(byProcessId(PAYMENT_PROCESS_ID)).hasTerminatedElements(CHARGE_CARD);

    // and: OrderProcess's event-based gateway takes the failure branch.
    // hasCompletedElements() first: if the process gets stuck, this pinpoints which element
    // never finished — asserting isCompleted() first would just time out with a generic
    // "was active" message and no indication of where.
    CamundaAssert.assertThat(orderInstance)
        .hasCompletedElements(
            ORDER_START,
            GENERATE_ORDER_ID,
            FETCH_PRODUCT_INFO,
            DETERMINE_DISCOUNT,
            SUBTRACT_DISCOUNT,
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
        .addResourceFile(
            Languages.repoRoot().resolve("assets").resolve(ORDER_DISCOUNT_DMN_FILE).toString())
        .addResourceFile(
            Languages.repoRoot()
                .resolve("assets")
                .resolve(CHECK_FAILED_PAYMENT_FORM_FILE)
                .toString())
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

  /**
   * Stands in for the real dummyjson.com connector call (see class javadoc): activates the {@code
   * io.camunda:http-json:1} job directly and completes it with fixed, fabricated product data,
   * exercising the real DMN evaluation and {@code discountedAmount -> orderTotal} ioMapping
   * without any external HTTP call.
   */
  private void completeFetchProductInfoJob() {
    ActivatedJob job =
        client
            .newActivateJobsCommand()
            .jobType(FETCH_PRODUCT_INFO_JOB_TYPE)
            .maxJobsToActivate(1)
            .timeout(Duration.ofSeconds(60))
            .send()
            .join()
            .getJobs()
            .get(0);
    client
        .newCompleteCommand(job)
        .variables(
            Map.of(
                "productName", "Test Product",
                "productDescription", "A fabricated product for CPT testing",
                "productPrice", STUBBED_PRODUCT_PRICE))
        .send()
        .join();
  }

  /**
   * Plays the human's role: finds the active "Check failed payment data" user task and completes
   * it with the given variables. Nothing in this exercise completes this task automatically.
   */
  private void completeCheckFailedPaymentTask(Map<String, Object> variables) {
    long userTaskKey =
        client
            .newUserTaskSearchRequest()
            .filter(f -> f.elementId(CHECK_FAILED_PAYMENT))
            .send()
            .join()
            .items()
            .get(0)
            .getUserTaskKey();
    client.newCompleteUserTaskCommand(userTaskKey).variables(variables).send().join();
  }
}
