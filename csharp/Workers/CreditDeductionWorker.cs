using Camunda.Orchestration.Sdk;
using Camunda.Training.CSharp.Services;

namespace Camunda.Training.CSharp.Workers
{
    public class CreditDeductionWorker(CamundaClient client) : Worker("credit-deduction", client)
    {
        public override Task<object?> Handler(ActivatedJob job, CancellationToken ct)
        {
            Console.WriteLine($"Handling credit-deduction job: {job.JobKey}");

            // Read the process variables for this job as a dictionary.
            var variables = job.GetVariables<Dictionary<string, object>>();
            if (variables != null
                && variables.TryGetValue("customerId", out object? customerIdObj)
                && variables.TryGetValue("orderTotal", out object? orderTotalObj))
            {
                string? customerId = customerIdObj?.ToString();
                double orderTotal = Convert.ToDouble(orderTotalObj);

                PrintProcessVariables(variables);

                CustomerService customerService = new CustomerService();
                double customerCredit = customerService.GetCustomerCredit(customerId);
                double openAmount = customerService.DeductCredit(customerCredit, orderTotal);

                // Returning this object auto-completes the job with openAmount.
                return Task.FromResult<object?>(new { openAmount });
            }

            Console.WriteLine("The required keys do not exist in the dictionary.");
            return Task.FromResult<object?>(null);
        }
    }
}
