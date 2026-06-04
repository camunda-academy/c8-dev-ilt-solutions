using Camunda.Orchestration.Sdk;
using Camunda.Training.CSharp.Workers;

namespace Camunda.Training.CSharp
{
    public class Program
    {
        public static async Task Main(string[] args)
        {
            using var client = CreateClient();

            Console.WriteLine("Connecting to Camunda...");
            var topology = await client.GetTopologyAsync();
            Console.WriteLine($"Connected! Brokers: {topology.Brokers?.Count ?? 0}");

            // Register the workers against the running client.
            new CreditDeductionWorker(client);
            new CreditCardChargingWorker(client);
            new PaymentMessageWorker(client);
            new PaymentCompletionWorker(client);
            new PaymentFailureWorker(client);

            Console.WriteLine("Workers started. Press Ctrl+C to exit.");

            // Block until Ctrl+C, then stop all workers gracefully.
            using var cts = new CancellationTokenSource();
            Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
            await client.RunWorkersAsync(ct: cts.Token);
        }

        /// <summary>
        /// Creates the Camunda client using zero-config (CAMUNDA_* environment
        /// variables). If no Camunda environment variables are present, falls back
        /// to the "Camunda" section of appsettings.json.
        /// </summary>
        private static CamundaClient CreateClient()
        {
            if (HasCamundaEnvironment())
            {
                Console.WriteLine("Using zero-config (CAMUNDA_* environment variables)...");
                return CamundaClient.Create();
            }

            Console.WriteLine("No CAMUNDA_* environment variables found; falling back to appsettings.json...");
            var configuration = new ConfigurationBuilder()
                .SetBasePath(AppDomain.CurrentDomain.BaseDirectory)
                .AddJsonFile("appsettings.json", optional: false, reloadOnChange: true)
                .AddEnvironmentVariables()
                .Build();

            return CamundaClient.Create(new CamundaOptions
            {
                Configuration = configuration.GetSection("Camunda"),
            });
        }

        private static bool HasCamundaEnvironment()
        {
            return Environment.GetEnvironmentVariables()
                .Keys
                .Cast<string>()
                .Any(key => key.StartsWith("CAMUNDA_", StringComparison.OrdinalIgnoreCase));
        }
    }
}
