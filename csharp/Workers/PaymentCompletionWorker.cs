using Camunda.Training.CSharp.Services;
using Newtonsoft.Json;
using Zeebe.Client;
using Zeebe.Client.Api.Responses;
using Zeebe.Client.Api.Worker;
using Zeebe.Client.Impl.Commands;

using Camunda.Training.CSharp.Services;

namespace Camunda.Training.CSharp.Workers;

public class PaymentCompletionWorker(IZeebeClient client) : Worker("payment-completion", client)
{
    public override void Handler(IJobClient jobClient, IJob activatedJob)
    {
        Console.WriteLine("Handling payment completion message");

        try
        {
            String jsonVariables = activatedJob.Variables;

            Dictionary<string, object> variables = JsonConvert.DeserializeObject<Dictionary<string, object>>(jsonVariables);

            if (variables.TryGetValue("orderId", out object orderIdObj) && orderIdObj is string orderIdString)
            {
                client.NewPublishMessageCommand()
                    .MessageName("paymentCompletedMessage")
                    .CorrelationKey(orderIdString)
                    .TimeToLive(TimeSpan.FromMinutes(5))
                    .Send()
                    .Wait();

                Console.WriteLine("Message payment completion sent");

                jobClient.NewCompleteJobCommand(activatedJob.Key)
                    .Send()
                    .Wait();
            }
            else
            {
                Console.WriteLine("orderId not found or not a string in the variables");
            }
        }
        catch (Exception ex)
        {
            Console.WriteLine($"Exception occurred: {ex.Message}");
        }
    }
}
