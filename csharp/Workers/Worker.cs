using Zeebe.Client;
using Zeebe.Client.Api.Responses;
using Zeebe.Client.Api.Worker;

namespace Camunda.Training.CSharp.Workers
{
    public abstract class Worker
    {
        protected readonly IZeebeClient client;
        protected readonly string jobType;

        protected Worker(string jobType, IZeebeClient client)
        {
            this.jobType = jobType;
            this.client = client;

            StartWorker();
        }

        private void StartWorker()
        {
            client.NewWorker()
                .JobType(jobType)
                .Handler(Handler)
                .MaxJobsActive(5)
                .Timeout(TimeSpan.FromSeconds(30))
                .Name($"{jobType}-worker")
                .Open();

            Console.WriteLine($"Worker '{jobType}' started");
        }

        protected void PrintProcessVariables(Dictionary<string, object> variables)
        {
            Console.WriteLine($"Process variables for {jobType}:");
            foreach (var variable in variables)
            {
                Console.WriteLine($"   {variable.Key}: {variable.Value}");
            }
        }

        public abstract void Handler(IJobClient jobClient, IJob activatedJob);
    }
}