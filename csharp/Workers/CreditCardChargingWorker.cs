using Zeebe.Client;
using Zeebe.Client.Api.Responses;
using Zeebe.Client.Api.Worker;

namespace Camunda.Training.CSharp.Workers
{
    public class CreditCardChargingWorker(IZeebeClient client) : Worker("credit-card-charging", client)
    {
        public override void Handler(IJobClient jobClient, IJob activatedJob)
        {
            Console.WriteLine($"Handling credit-card-charging job: {activatedJob.Key}");

            jobClient.NewCompleteJobCommand(activatedJob.Key)
                         .Variables("{}")
                         .Send()
                         .Wait();
        }
    }
}