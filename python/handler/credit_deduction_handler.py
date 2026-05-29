# credit_deduction_handler.py

from camunda_orchestration_sdk import ConnectedJobContext
from services.credit_service import deduct_credit


async def credit_deduction_handler(job: ConnectedJobContext) -> dict[str, object]:
    # Job is already activated by the worker — handler entry = job locked
    print(job.type_)
    variables = job.variables.to_dict()
    open_amount = deduct_credit(variables["customerId"], variables["orderTotal"])
    print(open_amount)
    return {"openAmount": open_amount}



