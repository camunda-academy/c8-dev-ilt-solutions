# payment_failure_handler.py

from camunda_orchestration_sdk import ConnectedJobContext, MessagePublicationRequest, MessagePublicationRequestVariables
from services.credit_card_service import charge_credit_card

async def payment_failure_handler(job: ConnectedJobContext) -> dict[str, object]:
    # Job is already activated by the worker — handler entry = job locked
    print(job.type_)
    variables = job.variables.to_dict()
    order_id = variables["orderId"]

    await job.client.publish_message(
        data=MessagePublicationRequest(
            name="paymentFailedMessage",
            correlation_key=order_id,
            time_to_live=60000
        )
    )
    # Returning normally completes the job
