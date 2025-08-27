
using Zeebe.Client;
using Zeebe.Client.Api.Responses;
using Zeebe.Client.Api.Worker;
using Newtonsoft.Json;
using Camunda.Training.CSharp.Services;

namespace Camunda.Training.CSharp.Workers
{
    public class CreditDeductionWorker(IZeebeClient client) : Worker("credit-deduction", client)
    {
        public override void Handler(IJobClient jobClient, IJob activatedJob)
        {
            Console.WriteLine($"Handling credit-deduction job: {activatedJob.Key}");

            try
            {
                String jsonVariables = activatedJob.Variables;
                Dictionary<string, object> variables = JsonConvert.DeserializeObject<Dictionary<string, object>>(jsonVariables);
                if (variables.TryGetValue("customerId", out object customerIdObj) && variables.TryGetValue("orderTotal", out object orderTotalObj))
                {
                    // Assuming customerId is a string and orderTotal is a double
                    string customerId = customerIdObj as string;
                    double orderTotal = Convert.ToDouble(orderTotalObj);
                    PrintProcessVariables(variables);
                    CustomerService customerService = new CustomerService();
                    double customerCredit = customerService.GetCustomerCredit(customerId);
                    double openAmount = customerService.DeductCredit(customerCredit, orderTotal);

                    string newVariables = JsonConvert.SerializeObject(new { openAmount });
                    jobClient.NewCompleteJobCommand(activatedJob.Key)
                             .Variables(newVariables)
                             .Send()
                             .Wait();
                }
                else
                {
                    Console.WriteLine("The required keys do not exist in the dictionary.");
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Exception occurred: {ex.Message}");
            }
        }
    }
}