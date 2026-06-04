using Camunda.Orchestration.Sdk;
using Camunda.Training.CSharp.Services;
using Camunda.Training.CSharp.Exceptions;

namespace Camunda.Training.CSharp.Workers
{
    // Input DTO. Property names map to the BPMN process variables
    // (System.Text.Json matches them case-insensitively, so CardNumber <-> cardNumber).
    public record ChargingInput(double OpenAmount, string CardNumber, string Cvc, string ExpiryDate);

    public class CreditCardChargingWorker(CamundaClient client) : Worker("credit-card-charging", client)
    {
        public override Task<object?> Handler(ActivatedJob job, CancellationToken ct)
        {
            Console.WriteLine($"Handling credit-card-charging job: {job.JobKey}");

            // Read the process variables into a typed DTO.
            var input = job.GetVariables<ChargingInput>();
            Console.WriteLine($"Variables: {input}");

            CreditCardService creditCardService = new CreditCardService();
            try
            {
                creditCardService.ChargeAmount(input!.CardNumber, input.Cvc, input.ExpiryDate, input.OpenAmount);
            }
            catch (InvalidCreditCardException ex)
            {
                // Throw a BPMN error so the process routes to the "Charging failed"
                // boundary event (errorCode must match the one modelled in the BPMN).
                throw new BpmnErrorException("creditCardChargeError", ex.Message);
            }
            catch (Exception ex)
            {
                // Generic errors still fail the job: no retries left -> incident.
                throw new JobFailureException(ex.Message, retries: 0);
            }

            // Returning null auto-completes the job with no variables.
            return Task.FromResult<object?>(null);
        }
    }
}
