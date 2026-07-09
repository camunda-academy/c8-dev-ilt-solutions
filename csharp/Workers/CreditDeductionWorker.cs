using Camunda.Orchestration.Sdk;
using Camunda.Training.CSharp.Services;

namespace Camunda.Training.CSharp.Workers
{
    // Input/output DTOs. Property names map to the BPMN process variables
    // (System.Text.Json matches them case-insensitively, so CustomerId <-> customerId).
    public record DeductionInput(string CustomerId, double OrderTotal);
    public record DeductionOutput(double OpenAmount1);

    public class CreditDeductionWorker(CamundaClient client) : Worker("credit-deduction", client)
    {
        public override Task<object?> Handler(ActivatedJob job, CancellationToken ct)
        {
            Console.WriteLine($"Handling credit-deduction job: {job.JobKey}");

            // Read the process variables into a typed DTO.
            var input = job.GetVariables<DeductionInput>();
            Console.WriteLine($"Variables: {input}");

            CustomerService customerService = new CustomerService();
            double customerCredit = customerService.GetCustomerCredit(input!.CustomerId);
            double openAmount = customerService.DeductCredit(customerCredit, input.OrderTotal);

            // Returning this DTO auto-completes the job with openAmount.
            return Task.FromResult<object?>(new DeductionOutput(openAmount));
        }
    }
}
