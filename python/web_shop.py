# workers.py

import asyncio
import configparser
from pathlib import Path
from camunda_orchestration_sdk import CamundaAsyncClient, WorkerConfig

from handler.payment_completion_handler import payment_completion_handler
from handler.payment_invocation_handler import payment_invocation_handler
from services.credit_service import get_customer_credit, deduct_credit
from services.credit_card_service import charge_credit_card
from handler.example_handler import example_job_handler
from handler.credit_deduction_handler import credit_deduction_handler
from handler.credit_card_charging_handler import credit_card_charging_handler
import os



async def main():

    # Get Config

    config = configparser.ConfigParser()
    config_path = Path(__file__).parent / "config.ini"
    config.read(config_path)

    client_id = config["camunda"]["camunda.client.auth.client-id"]
    client_secret = config["camunda"]["camunda.client.auth.client-secret"]
    cluster_id = config["camunda"]["camunda.client.cloud.cluster-id"]
    region = config["camunda"]["camunda.client.cloud.region"]

    # Set up client
    async with CamundaAsyncClient(
        configuration={
            "CAMUNDA_REST_ADDRESS": f"https://{region}.zeebe.camunda.io/{cluster_id}",
            "CAMUNDA_CLIENT_ID": client_id,
            "CAMUNDA_CLIENT_SECRET": client_secret,
            "CAMUNDA_AUTH_STRATEGY": "OAUTH",
            "CAMUNDA_OAUTH_URL": "https://login.cloud.camunda.io/oauth/token",
            "CAMUNDA_TOKEN_AUDIENCE": "zeebe.camunda.io",
        }
    ) as camunda_client:
        # Worker 1 (example):
        camunda_client.create_job_worker(
            WorkerConfig(job_type="example-job-type", job_timeout_milliseconds=10_000),
            example_job_handler
        )
        # Add further workers below
        camunda_client.create_job_worker(
            WorkerConfig(job_type="credit-deduction", 
                         job_timeout_milliseconds=10_000,
                         fetch_variables=["orderTotal", "customerId"]), 
            credit_deduction_handler
        )
        camunda_client.create_job_worker(
            WorkerConfig(job_type="credit-card-charging",
                          job_timeout_milliseconds=10_000,
                          fetch_variables=["cardNumber", "cvc", "expiryDate", "openAmount"]),
            credit_card_charging_handler
        )
        camunda_client.create_job_worker(
            WorkerConfig(job_type="payment-invocation",
                          job_timeout_milliseconds=10_000),
            payment_invocation_handler
        )
        camunda_client.create_job_worker(
            WorkerConfig(job_type="payment-completion",
                          job_timeout_milliseconds=10_000,
                          fetch_variables=["orderId"]),
            payment_completion_handler
        )
        # Keep all workers running until cancelled
        print("Workers starting...")
        await camunda_client.run_workers()


if __name__ == "__main__":
    asyncio.run(main())
