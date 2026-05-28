# example_handler.py

from camunda_orchestration_sdk import ConnectedJobContext


async def credit_card_charging_handler(job: ConnectedJobContext) -> dict[str, object]:
    # Job is already activated by the worker — handler entry = job locked
    print("Hello world")
    # Returning normally completes the job
