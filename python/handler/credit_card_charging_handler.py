# credit_card_charging_handler.py

from camunda_orchestration_sdk import ConnectedJobContext, JobError, JobErrorRequest, JobFailRequest, JobFailure
from services.credit_card_service import charge_credit_card

async def credit_card_charging_handler(job: ConnectedJobContext) -> dict[str, object]:
    # Job is already activated by the worker — handler entry = job locked
    print(job.type_)
    variables = job.variables.to_dict()
    # expiryDate validierung
    try:
        charge_credit_card(variables["cardNumber"], variables["cvc"], variables["expiryDate"], variables["openAmount"])
    except ValueError as e:
        print(e)
        raise JobError(error_code="invalidExpiryDate", message=str(e))
    except Exception as e:
        raise JobFailure(job.retries - 1, str(e), 2000)
        
