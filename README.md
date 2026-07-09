# Camunda 8 – Platform for Developers: ILT Solutions

This repository contains the **official exercise solutions** for the Camunda 8 Instructor-Led Training (ILT) course *"Camunda 8 – Platform for Developers"*.

Each exercise solution is organized by programming language and is designed to run as a **Camunda job worker** connecting to a Camunda 8 SaaS cluster.

---

## Business Scenario

The exercises are built around an **Order / Payment process**. Throughout the course, participants progressively implement and extend this process, adding service tasks, decision logic, forms, and error handling step by step.

---

## Repository Structure

```
c8-dev-ilt-solutions/
├── assets/          # Shared process resources (see below)
├── csharp/          # C# solution
├── java/            # Java solution
├── java-spring/     # Java Spring solution
├── js/              # JavaScript (Node.js) solution
└── python/          # Python solution
```

### `assets/`

Contains all the process resources shared across language implementations:

- **BPMN diagrams** – process models to deploy to Camunda
- **DMN diagrams** – decision tables used within the process
- **Forms** – Camunda forms for human tasks
- **Test scenarios** – predefined inputs and expected outcomes for testing

> These resources are language-agnostic and are used by all language implementations.

### Language folders

Each folder (`java/`, `js/`, `python/`, etc.) contains the complete job worker implementation for that language. Refer to the `README.md` inside each folder for setup and run instructions specific to that language.

---

## Branch Structure

Each branch in this repository corresponds to the **solution of a specific exercise**:

| Branch        | Content                             |
| ------------- | ----------------------------------- |
| `main`        | Base structure and shared resources |
| `exercise-01` | Solution for Exercise 01            |
| `exercise-02` | Solution for Exercise 02            |
| `exercise-NN` | Solution for Exercise NN            |
| ...           | ...                                 |

To switch to the solution of a specific exercise:

```bash
git checkout exercise-NN
```

> **Tip:** If you want to compare your own solution with the reference one, check out the relevant branch and diff it against your work.

---

## Trainer Operations

For the trainer runbook (exercise update flow, PR flow, and CPT checks), see:

- [docs/trainer-guide.md](docs/trainer-guide.md)

---

## Prerequisites

Before running any of the solutions, make sure you have:

- A running **Camunda 8 SaaS** cluster ([console.camunda.io](https://console.camunda.io))
- A set of **API client credentials** for your cluster:
  - `ZEEBE_ADDRESS`
  - `ZEEBE_CLIENT_ID`
  - `ZEEBE_CLIENT_SECRET`
  - `CAMUNDA_CLUSTER_REGION` (if required by your client libraries)
- The **BPMN/DMN/Forms** from the `assets/` folder deployed to your cluster

> Each language folder's `README.md` explains how to configure these credentials for that specific implementation.

---

## Deploying Assets

Before running a job worker, deploy the relevant process resources from the `assets/` folder to your Camunda cluster using one of these options:

- **Camunda Web Modeler** – upload and deploy directly from the UI

---

## License

This repository is intended for **training purposes only** and is maintained by the [Camunda Academy](https://academy.camunda.com) team.


