# credit_card_charging_handler.py

from camunda_orchestration_sdk import ConnectedJobContext
from services.credit_card_service import charge_credit_card

async def credit_card_charging_handler(job: ConnectedJobContext) -> dict[str, object]:
    # Job is already activated by the worker — handler entry = job locked
    print(job.type_)

    variables = job.variables.to_dict()
    charge_credit_card(variables["cardNumber"], variables["cvc"], variables["expiryDate"], variables["openAmount"])
    # Returning normally completes the job
