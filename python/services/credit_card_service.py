# credit_card_service.py

def charge_credit_card(card_number: str, cvc: str, expiry_date: str, amount: float) -> None:
    """
    Simulates charging a credit card for the given amount.
    """
    if len(expiry_date) != 5:
        raise ValueError("expiryDate must be exactly 5 characters long")

    print(f"Card {card_number}, with CVC {cvc} and expiry date {expiry_date} was charged for {amount}")
