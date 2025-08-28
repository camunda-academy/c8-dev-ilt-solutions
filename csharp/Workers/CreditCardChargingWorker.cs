
using Zeebe.Client;
using Zeebe.Client.Api.Responses;
using Zeebe.Client.Api.Worker;
using Newtonsoft.Json;
using Camunda.Training.CSharp.Services;
using Camunda.Training.CSharp.Exceptions;

namespace Camunda.Training.CSharp.Workers
{
    public class CreditCardChargingWorker(IZeebeClient client) : Worker("credit-card-charging", client)
    {
        public override void Handler(IJobClient jobClient, IJob activatedJob)
        {
            Console.WriteLine($"Handling credit-card-charging job: {activatedJob.Key}");

            try
            {
                String jsonVariables = activatedJob.Variables;

                // Deserialize JSON string to Dictionary
                Dictionary<string, object> variables = JsonConvert.DeserializeObject<Dictionary<string, object>>(jsonVariables);
                if (variables.TryGetValue("openAmount", out object openAmountObj)
                    && variables.TryGetValue("cardNumber", out object cardNumberObj)
                    && variables.TryGetValue("cvc", out object cvcObj)
                    && variables.TryGetValue("expiryDate", out object expiryDateObj))
                {
                    // Assuming openAmount is a double

                    double openAmount = Convert.ToDouble(openAmountObj);
                    string? cardNumber = cardNumberObj as string;
                    string? cvc = cvcObj as string;
                    string? expiryDate = expiryDateObj as string;

                    PrintProcessVariables(variables);

                    // Create an instance of CreditCardService and call ChargeAmount method
                    CreditCardService creditCardService = new CreditCardService();
                    creditCardService.ChargeAmount(cardNumber, cvc, expiryDate, openAmount);

                    // Complete the job
                    jobClient.NewCompleteJobCommand(activatedJob.Key)
                                .Send()
                                .Wait();
                }
                else
                {
                    Console.WriteLine("The required keys do not exist in the dictionary.");
                }
            }
            catch (InvalidCreditCardException ex)
            {
                Console.WriteLine($"InvalidCreditCardException occurred: {ex.Message}");
                jobClient.NewThrowErrorCommand(activatedJob.Key)
                    .ErrorCode("creditCardChargeError")
                    .ErrorMessage(ex.Message)
                    .Send()
                    .Wait();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Exception occurred: {ex.Message}");
                jobClient.NewFailCommand(activatedJob.Key)
                    .Retries(0)
                    .ErrorMessage(ex.Message)
                    .Send()
                    .Wait();
            }
        }
    }
}