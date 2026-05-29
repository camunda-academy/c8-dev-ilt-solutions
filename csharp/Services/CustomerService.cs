namespace Camunda.Training.CSharp.Services;

public class CustomerService
{
    public double GetCustomerCredit(string? customerId)
    {
        var customerCredit = Convert.ToDouble(customerId?.Substring(customerId.Length - 2, 2));
        return customerCredit;
    }

    public double DeductCredit(double customerCredit, double amountToDeduct)
    {
        return amountToDeduct > customerCredit ? amountToDeduct - customerCredit : 0.0;
    }
}