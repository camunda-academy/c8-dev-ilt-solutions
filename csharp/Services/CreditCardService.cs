namespace Camunda.Training.CSharp.Services;

public class CreditCardService
{
    public void ChargeAmount(string? cardNumber, string? cvc, string? expiryDate, double amount)
    {
        Console.Out.WriteLine("Credit card number: " + cardNumber + " CVC: " + cvc + " Expiry date: " + expiryDate +
                              " Amount to charge: " + amount);
    }
}


