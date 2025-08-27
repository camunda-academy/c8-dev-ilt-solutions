using Camunda.Training.CSharp.Workers;
using Zeebe.Client;
using Zeebe.Client.Impl.Builder;


namespace Camunda.Training.CSharp
{
    public class Program
    {
        public static async Task Main(string[] args)
        {
            var configuration = BuildConfiguration();
            var clientId = configuration["ZeebeClientConfig:ClientId"];
            var clientSecret = configuration["ZeebeClientConfig:ClientSecret"];
            var contactPoint = configuration["ZeebeClientConfig:ContactPoint"];

            var client = CamundaCloudClientBuilder
            .Builder()
            .UseClientId(clientId)
            .UseClientSecret(clientSecret)
            .UseContactPoint(contactPoint)
            .Build();

            Console.WriteLine("Connecting to Camunda...");
            var topology = await client.TopologyRequest().Send();
            Console.WriteLine($"Connected! {topology}");

            var creditDeductionWorker = new CreditDeductionWorker(client);
            var creditCardChargingWorker = new CreditCardChargingWorker(client);
            var paymentInvocationWorker = new PaymentMessageWorker(client);
            var paymentCompletionWorker = new PaymentCompletionWorker(client);
            var paymentFailureWorker = new PaymentFailureWorker(client);

            Console.WriteLine("Workers started. Press Ctrl+C to exit.");

            using var signal = new EventWaitHandle(false, EventResetMode.AutoReset);
            signal.WaitOne();
        }

        private static IConfiguration BuildConfiguration()
        {
            return new ConfigurationBuilder()
            .SetBasePath(AppDomain.CurrentDomain.BaseDirectory)
            .AddJsonFile("appsettings.json", optional: false, reloadOnChange: true)
            .Build();
        }
    }
}