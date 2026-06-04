using Camunda.Orchestration.Sdk;
using Camunda.Training.CSharp.Services;

namespace Camunda.Training.CSharp.Workers
{
    public class CreditCardChargingWorker(CamundaClient client) : Worker("credit-card-charging", client)
    {
        public override Task<object?> Handler(ActivatedJob job, CancellationToken ct)
        {
            Console.WriteLine($"Handling credit-card-charging job: {job.JobKey}");

            // Read the process variables for this job as a dictionary.
            var variables = job.GetVariables<Dictionary<string, object>>();
            if (variables != null
                && variables.TryGetValue("openAmount", out object? openAmountObj)
                && variables.TryGetValue("cardNumber", out object? cardNumberObj)
                && variables.TryGetValue("cvc", out object? cvcObj)
                && variables.TryGetValue("expiryDate", out object? expiryDateObj))
            {
                double openAmount = Convert.ToDouble(openAmountObj);
                string? cardNumber = cardNumberObj?.ToString();
                string? cvc = cvcObj?.ToString();
                string? expiryDate = expiryDateObj?.ToString();

                PrintProcessVariables(variables);

                CreditCardService creditCardService = new CreditCardService();
                creditCardService.ChargeAmount(cardNumber, cvc, expiryDate, openAmount);

                // Returning null auto-completes the job with no variables.
                return Task.FromResult<object?>(null);
            }

            Console.WriteLine("The required keys do not exist in the dictionary.");
            return Task.FromResult<object?>(null);
        }
    }
}
