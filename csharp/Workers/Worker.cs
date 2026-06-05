using Camunda.Orchestration.Sdk;

namespace Camunda.Training.CSharp.Workers
{
    public abstract class Worker
    {
        protected readonly CamundaClient client;
        protected readonly string jobType;

        protected Worker(string jobType, CamundaClient client)
        {
            this.jobType = jobType;
            this.client = client;

            StartWorker();
        }

        private void StartWorker()
        {
            client.CreateJobWorker(
                new JobWorkerConfig
                {
                    JobType = jobType,
                    JobTimeoutMs = 30_000,
                    MaxConcurrentJobs = 5,
                    WorkerName = $"{jobType}-worker",
                },
                Handler);

            Console.WriteLine($"Worker '{jobType}' started");
        }

        protected void PrintProcessVariables(IReadOnlyDictionary<string, object> variables)
        {
            Console.WriteLine($"Process variables for {jobType}:");
            foreach (var variable in variables)
            {
                Console.WriteLine($"   {variable.Key}: {variable.Value}");
            }
        }

        /// <summary>
        /// Handles an activated job. The returned object auto-completes the job
        /// with those variables; return <c>null</c> to complete with no variables.
        /// </summary>
        public abstract Task<object?> Handler(ActivatedJob job, CancellationToken ct);
    }
}
