using Camunda.Training.CSharp.Services;
using Newtonsoft.Json;
using Zeebe.Client;
using Zeebe.Client.Api.Responses;
using Zeebe.Client.Api.Worker;
using Zeebe.Client.Impl.Commands;

namespace Camunda.Training.CSharp.Workers
{
    public class PaymentMessageWorker : Worker
    {
        public PaymentMessageWorker(IZeebeClient client) : base("payment-invocation", client) { }
        public override void Handler(IJobClient jobClient, IJob activatedJob)
        {
            Console.WriteLine("Handling payment message");

            try
            {
                String jsonVariables = activatedJob.Variables;
                Dictionary<string, object> variables = JsonConvert.DeserializeObject<Dictionary<string, object>>(jsonVariables);

                if (variables.TryGetValue("orderId", out object orderIdObj) && orderIdObj is string orderIdString)
                {

                    client.NewPublishMessageCommand()
                            .MessageName("paymentRequestMessage")
                            .CorrelationKey(orderIdString)
                            .TimeToLive(TimeSpan.FromMinutes(5))
                            .Variables(jsonVariables)
                            .Send()
                            .Wait();

                    Console.WriteLine("Message paymentRequestMessage sent");
                    //No need to send back all the variables to the Order Process, since only the orderId is needed.
                    string orderIdJson = JsonConvert.SerializeObject(new { orderId = orderIdString });
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
}