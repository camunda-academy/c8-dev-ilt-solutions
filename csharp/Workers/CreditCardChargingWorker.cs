using Camunda.Orchestration.Sdk;
using Camunda.Training.CSharp.Services;

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
            creditCardService.ChargeAmount(input!.CardNumber, input.Cvc, input.ExpiryDate, input.OpenAmount);

            // Returning null auto-completes the job with no variables.
            return Task.FromResult<object?>(null);
        }
    }
}
