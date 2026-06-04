using Camunda.Orchestration.Sdk;

namespace Camunda.Training.CSharp.Workers;

public class PaymentFailureWorker(CamundaClient client) : Worker("payment-failure", client)
{
    public override async Task<object?> Handler(ActivatedJob job, CancellationToken ct)
    {
        Console.WriteLine("Handling payment failure");

        var variables = job.GetVariables<Dictionary<string, object>>();
        if (variables != null
            && variables.TryGetValue("orderId", out object? orderIdObj)
            && orderIdObj?.ToString() is string orderId)
        {
            // Notify the waiting Order Process that payment has failed.
            // No need to send variables back; only the orderId correlation is needed.
            await client.PublishMessageAsync(new MessagePublicationRequest
            {
                Name = "paymentFailedMessage",
                CorrelationKey = orderId,
                TimeToLive = (long)TimeSpan.FromMinutes(5).TotalMilliseconds,
            }, ct);

            Console.WriteLine("Message paymentFailedMessage sent");
        }
        else
        {
            Console.WriteLine("orderId not found or not a string in the variables");
        }

        // Returning null auto-completes the job with no variables.
        return null;
    }
}
