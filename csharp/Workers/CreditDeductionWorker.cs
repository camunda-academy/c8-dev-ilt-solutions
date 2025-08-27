using Zeebe.Client;
using Zeebe.Client.Api.Responses;
using Zeebe.Client.Api.Worker;

namespace Camunda.Training.CSharp.Workers
{
    public class CreditDeductionWorker(IZeebeClient client) : Worker("credit-deduction", client)
    {
        public override void Handler(IJobClient jobClient, IJob activatedJob)
        {
            Console.WriteLine($"Handling credit-deduction job: {activatedJob.Key}");

            jobClient.NewCompleteJobCommand(activatedJob.Key)
                     .Variables("{\"processed\": true}")
                     .Send()
                     .GetAwaiter()
                     .GetResult();
        }
    }
}