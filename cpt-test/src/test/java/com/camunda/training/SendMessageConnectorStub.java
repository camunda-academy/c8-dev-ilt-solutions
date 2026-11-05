package com.camunda.training;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.command.PublishMessageCommandStep1.PublishMessageCommandStep3;
import io.camunda.client.api.worker.JobWorker;
import java.util.Map;

/**
 * Stands in for the Camunda "Publish message" connector (the Send Message connector, job type
 * {@code io.camunda:sendMessage:1}) used by the Order and Payment processes from exercise 7
 * onwards: the {@code Invoke payment} send task and the {@code Payment completed} / {@code Payment
 * failed} message end events.
 *
 * <p>The tests run against a plain Camunda runtime without a Connectors runtime, so nothing would
 * otherwise handle those jobs. This worker does what the connector does in publish mode: it reads
 * the connector inputs from the job ({@code messageName}, optional {@code correlationKey}, optional
 * {@code variables}), publishes the message and completes the job. That exercises the BPMN wiring
 * (message names, correlation keys, payload expressions) the same way the real connector would; it
 * does not test the connector implementation itself.
 *
 * <p>On branches that still use the {@code payment-invocation} / {@code payment-completion} /
 * {@code payment-failure} job workers (exercise-07) no such jobs exist, so the stub stays idle.
 */
final class SendMessageConnectorStub {

  static final String JOB_TYPE = "io.camunda:sendMessage:1";

  private SendMessageConnectorStub() {}

  @SuppressWarnings("unchecked")
  static JobWorker open(CamundaClient client) {
    return client
        .newWorker()
        .jobType(JOB_TYPE)
        .handler(
            (jobClient, job) -> {
              Map<String, Object> inputs = job.getVariablesAsMap();

              PublishMessageCommandStep3 message =
                  inputs.get("correlationKey") == null
                      ? client
                          .newPublishMessageCommand()
                          .messageName((String) inputs.get("messageName"))
                          .withoutCorrelationKey()
                      : client
                          .newPublishMessageCommand()
                          .messageName((String) inputs.get("messageName"))
                          .correlationKey(String.valueOf(inputs.get("correlationKey")));
              if (inputs.get("variables") instanceof Map<?, ?> payload) {
                message = message.variables((Map<String, Object>) payload);
              }
              message.send().join();

              jobClient.newCompleteCommand(job).send().join();
            })
        .open();
  }
}
