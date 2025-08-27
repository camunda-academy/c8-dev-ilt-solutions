
using Camunda.Training.CSharp.Exceptions;
namespace Camunda.Training.CSharp.Services;

public class CreditCardService
{
  public void ChargeAmount(string? cardNumber, string? cvc, string? expiryDate, double amount)
  {
    if (expiryDate?.Length == 5)
    {
      Console.Out.WriteLine("Credit card number: " + cardNumber + " CVC: " + cvc + " Expiry date: " + expiryDate +
          " Amount to charge: " + amount);
    }
    else
    {
      string errorMessage = "Invalid credit card expiry date: " + expiryDate;
      Console.Error.WriteLine(errorMessage);
      throw new InvalidCreditCardException(errorMessage);
    }
  }
}


