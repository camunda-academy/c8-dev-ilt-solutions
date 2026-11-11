# Java solution for JDK 8 (exercise 10)

Variant of the `java/` solution of this exercise that compiles on **JDK 8**. It uses the Camunda client **8.10.9**.

> **Not supported by Camunda.** The Camunda docs list no JDK 8 baseline for `camunda-client-java`. Use `java/` with JDK 21 for the course. The Spring variant (`java-spring/`) needs Java 17 or later and has no JDK 8 variant. The automated checks (CPT) do not run this folder.

## What differs from `java/`

| File | Change | Why |
| --- | --- | --- |
| `pom.xml` | `camunda-bom` `8.10.9` | latest 8.10 client (the exercise branch pins `8.10.8`) |
| `pom.xml` | `maven.compiler.source` and `target` set to `8` (was `21`) | compile for Java 8 |
| `pom.xml` | `logback-classic` `1.3.15` (was `1.5.x`) | logback 1.5 needs Java 11, 1.3.x is the last line for Java 8 |
| `CreditDeductionWorker.java` | `Collections.singletonMap(...)` instead of `Map.of(...)` | `Map.of` needs Java 9 |
| `CamundaApplication.java` | `value.trim().isEmpty()` instead of `value.isBlank()` | `isBlank` needs Java 11 |
| `CamundaApplicationCpt.java` | removed | launcher for the automated checks (CPT), not part of the exercise, uses `isBlank` |

Everything else is unchanged, including the Camunda client calls. When `java/` changes, this folder has to be updated by hand.

## Prerequisites

- JDK 8 (for example Temurin 8) and Maven 3.9 (Maven itself runs on Java 8)
- A Camunda 8 SaaS cluster with an API client. Put its values in `src/main/resources/application.properties` (`client-id`, `client-secret`, `cluster-id`, `region`). Do not commit them.

## Run

```bash
mvn compile exec:java
```

## What was tested

Run end to end on Temurin 8 (`1.8.0_512`) with Camunda client 8.10.9 against a Camunda 8.10 SaaS cluster: the exercise 10 flow with the BPMN and form of this branch (Publish message connector, error boundary, user task), started and checked through the REST API.

| Instance | Path | Result |
| --- | --- | --- |
| A, valid expiry date | normal card charge | Payment and Order completed |
| B, invalid date, fixed in the user task | error boundary, user task, charge again | Order completed |
| C, invalid date, not resolved | error boundary, user task | Payment failed, Order failed |

No incidents, no warnings in the worker log. The connector steps run on the SaaS-hosted connectors, not on JDK 8.

The 8.10.9 client jar is compiled for Java 8 (class file version 52). Its dependency `camunda-xml-model` 7.24 is class file version 55 (Java 11). No `UnsupportedClassVersionError` occurred in the exercise 10 run, but code that loads those classes would fail on JDK 8, so other client features may not work.
