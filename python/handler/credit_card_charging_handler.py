# credit_card_charging_handler.py

from camunda_orchestration_sdk import ConnectedJobContext, JobFailRequest
from services.credit_card_service import charge_credit_card

async def credit_card_charging_handler(job: ConnectedJobContext) -> dict[str, object]:
    # Job is already activated by the worker — handler entry = job locked
    print(job.type_)
    variables = job.variables.to_dict()
    # expiryDate validierung
    try:
        charge_credit_card(variables["cardNumber"], variables["cvc"], variables["expiryDate"], variables["openAmount"])
        return
    except ValueError as e:
        print(e)
        await job.client.fail_job(job_key=job.job_key, data=JobFailRequest(job.retries - 1, str(e), 2000))
    
