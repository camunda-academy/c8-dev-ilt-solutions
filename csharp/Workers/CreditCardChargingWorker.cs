using Camunda.Orchestration.Sdk;

namespace Camunda.Training.CSharp.Workers
{
    public class CreditCardChargingWorker(CamundaClient client) : Worker("credit-card-charging", client)
    {
        public override Task<object?> Handler(ActivatedJob job, CancellationToken ct)
        {
            Console.WriteLine($"Handling credit-card-charging job: {job.JobKey}");

            // Returning null auto-completes the job with no variables.
            // We'll change this in the next lesson to return some variables for the process to use.
            return Task.FromResult<object?>(null);
        }
    }
}
