using Camunda.Orchestration.Sdk;

namespace Camunda.Training.CSharp.Workers
{
    public class PaymentMessageWorker(CamundaClient client) : Worker("payment-invocation", client)
    {
        public override async Task<object?> Handler(ActivatedJob job, CancellationToken ct)
        {
            Console.WriteLine("Handling payment message");

            // Read all variables; forward them to the Payment Process via the message.
            var variables = job.GetVariables<Dictionary<string, object>>();
            if (variables != null
                && variables.TryGetValue("orderId", out object? orderIdObj)
                && orderIdObj?.ToString() is string orderId)
            {
                await client.PublishMessageAsync(new MessagePublicationRequest
                {
                    Name = "paymentRequestMessage",
                    CorrelationKey = orderId,
                    TimeToLive = (long)TimeSpan.FromMinutes(5).TotalMilliseconds,
                    Variables = variables,
                }, ct);

                Console.WriteLine("Message paymentRequestMessage sent");
            }
            else
            {
                Console.WriteLine("orderId not found or not a string in the variables");
            }

            // Returning null auto-completes the job with no variables.
            return null;
        }
    }
}
