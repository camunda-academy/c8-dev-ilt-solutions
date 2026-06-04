using Camunda.Orchestration.Sdk;

namespace Camunda.Training.CSharp.Workers
{
    public class CreditDeductionWorker(CamundaClient client) : Worker("credit-deduction", client)
    {
        public override Task<object?> Handler(ActivatedJob job, CancellationToken ct)
        {
            Console.WriteLine($"Handling credit-deduction job: {job.JobKey}");

            // Returning these variables auto-completes the job.
            return Task.FromResult<object?>(new { processed = true });
        }
    }
}
