# CLAUDE.md – Test Program Instructions

This file tells Claude how to create and maintain the Java test program for the `c8-dev-ilt-solutions` repository.

---

## Purpose

Create a Java test program using **Camunda Process Test (CPT)** that verifies the correctness of the Order/Payment process solutions across exercise branches.

Tests must be runnable:
- **Individually** – target a single exercise branch/scenario
- **All together** – run the full test suite in sequence

---

## Target Version

- **Camunda:** latest stable release
- **CPT library:** `io.camunda:camunda-process-test-java` — use the latest stable version

> Always resolve and use the latest stable Camunda/CPT version. Do not hardcode a specific version in this document; read it from the project's `camunda.version` property (or the latest stable release at build time).

> Do NOT use the deprecated Zeebe Process Test (ZPT) library. ZPT is removed in 8.10. Always use CPT.

**Official CPT documentation:** https://docs.camunda.io/docs/apis-tools/testing/getting-started/

---

## Repository Context

See `README.md` for the full project structure, business scenario (Order/Payment process), branch layout, and language folders. Do not restate those here.

CPT-specific notes for this test program:
- **Process resources are in the `assets/` folder** — the BPMN diagrams, DMN decision tables, and test scenarios all live there. The tests deploy and drive these resources.
- **The test program lives in the new `cpt-test/` folder** — put all sources of the testing program (Maven project, test classes, resources) under `cpt-test/`. Use the plain Java CPT variant (no Spring Boot).
- **Do NOT mock the job workers.** This is the key point: the purpose of these tests is to verify the real job workers implemented in the `java/`, `java-spring/`, `python/`, and `csharp/` folders. The workers must be running against the CPT runtime so the process is driven end-to-end by the actual implementations — not by mocks.

---

## CPT Library: Key Facts

| Topic                  | Detail                                                  |
| ---------------------- | ------------------------------------------------------- |
| Library                | `io.camunda:camunda-process-test-java`                  |
| Annotation             | `@CamundaProcessTest`                                   |
| Client injection       | `private CamundaClient client;` (injected by CPT)       |
| Context injection      | `private CamundaProcessTestContext processTestContext;` |
| Assertions entry point | `CamundaAssert.assertThat(processInstance)`             |
| Resource deployment    | `@TestDeployment(resources = "...")` or via client      |
| Framework              | JUnit 5 (mandatory — use `org.junit.jupiter.api.Test`)  |
| Runtime                | Testcontainers (default) — Docker must be running       |

### Maven Dependency

```xml
<dependency>
  <groupId>io.camunda</groupId>
  <artifactId>camunda-process-test-java</artifactId>
  <version>${camunda.version}</version>
  <scope>test</scope>
</dependency>
```

Set `camunda.version` to the latest stable Camunda release.

### Minimal Test Class Structure

```java
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTest;
import io.camunda.process.test.api.CamundaProcessTestContext;
import org.junit.jupiter.api.Test;

@CamundaProcessTest
public class ExerciseTest {

    // Injected by CPT
    private CamundaClient client;
    private CamundaProcessTestContext processTestContext;

    @Test
    @TestDeployment(resources = {"order-process.bpmn", "payment.dmn"})
    void shouldCompleteOrderProcess() {
        ProcessInstanceEvent instance = client
            .newCreateInstanceCommand()
            .bpmnProcessId("order-process")
            .latestVersion()
            .variables(Map.of("orderId", "123", "amount", 99.99))
            .send()
            .join();

        CamundaAssert.assertThat(instance).isCompleted();
    }
}
```

---

## Test Design Guidelines

- **One test class per exercise** (e.g. `Exercise01Test.java`, `Exercise05Test.java`)
- **One test suite class** (e.g. `AllExercisesTestSuite.java`) that runs all tests together using JUnit 5 `@Suite`
- Deploy BPMN/DMN from the `assets/` folder — reference them from the classpath (copy or symlink into `cpt-test/src/test/resources/`)
- Drive test inputs from the scenarios in the `assets/` folder where possible
- Use `CamundaAssert` for all process state assertions — it handles async behavior automatically
- **Run the real job workers — do NOT mock them.** Start the language implementation (from `java/`, `java-spring/`, `python/`, or `csharp/`) so its workers connect to the CPT runtime and execute the service tasks for real. The test only creates the process instance and asserts the outcome; the actual work is done by the implementation under test.
- Use `processTestContext.increaseTime(...)` to trigger timer events if any exercise uses them
- Follow the given/when/then pattern in each test method

### Running the workers under test

The job workers live in the language folders, not in `cpt-test/`. Make them connect to the CPT runtime before driving the process. Two options:

- **Same JVM (Java / Java-Spring):** start the worker application inside the test (e.g. in a `@BeforeEach`), pointing its `CamundaClient` at the CPT runtime addresses exposed via `processTestContext`.
- **Separate process (Python / C# / standalone Java):** start the worker as an external process pointing at the CPT runtime's REST/gRPC addresses (from `processTestContext`), then run the test against it.

Whatever the language, the workers must be live and connected so the process completes through the real implementation.

### Test matrix: same tests across languages

The same process behaves identically regardless of which language implements the workers. So the tests must be **repeatable per programming language**: the same exercise solution can be validated against the `java`, `java-spring`, `python`, or `csharp` implementation — one at a time, or all of them.

Requirements:
- Make the **language under test** a parameter (e.g. a `LANG` env var / system property / JUnit parameterized input), so the same test logic runs against any chosen implementation.
- Support testing **one language**, a **subset**, or **all languages** in a single run.
- **Skip languages that have no content.** Some language folders are still work-in-progress and contain no code. Before running, detect whether the selected language folder for that exercise actually has an implementation; if it is empty/WIP, skip it (mark as skipped, not failed) and continue with the others.
- Example combinations to support:
  - exercise-05 + `java` only
  - exercise-05 + `python` only
  - exercise-05 + all available languages


### Useful Assertions

```java
CamundaAssert.assertThat(instance).isActive();
CamundaAssert.assertThat(instance).isCompleted();
CamundaAssert.assertThat(instance).isTerminated();
CamundaAssert.assertThat(instance).hasActiveElements("task-id");
CamundaAssert.assertThat(instance).hasCompletedElements("task-id");
CamundaAssert.assertThat(instance).hasVariables(Map.of("key", "value"));
```

---

## Running Tests

Select the **exercise(s)** and the **language(s)** to test. The language is passed as a parameter (e.g. `-Dlang=...`).

### Run one exercise against one language

```bash
mvn test -Dtest=Exercise05Test -Dlang=java
```

### Run one exercise against another language

```bash
mvn test -Dtest=Exercise05Test -Dlang=python
```

### Run one exercise against all available languages

```bash
mvn test -Dtest=Exercise05Test -Dlang=all
```

### Run all exercises against all available languages

```bash
mvn test -Dlang=all
```

> `all` runs every language that has an implementation for that exercise. WIP/empty language folders are skipped automatically.

---

## Prerequisites for Running

- Java 8+ (for the plain Java CPT client)
- Docker running locally (CPT uses Testcontainers by default)
- Maven installed
- No Camunda SaaS cluster needed — CPT spins up its own isolated runtime via Docker

> Note: CPT's Testcontainers runtime takes ~30–60 seconds to start on the first run.
> Enable **shared runtime** in `camunda-container-runtime.properties` to reuse one runtime across all test classes and speed up the full suite.

### Shared Runtime (recommended for the full suite)

```properties
# src/test/resources/camunda-container-runtime.properties
runtimeMode=shared
```

---

## Important Notes for Claude

- Always use `@CamundaProcessTest` — never `@ZeebeProcessTest` (deprecated)
- Always use `io.camunda.client.CamundaClient` — never the old `io.camunda.zeebe.client.ZeebeClient`
- Always use `org.junit.jupiter.api.Test` — not JUnit 4
- BPMN/DMN come from the `assets/` folder — make them available on the classpath under `cpt-test/src/test/resources/`
- Never mock the job workers — always run the real implementations from the language folders
- CPT automatically resets all process data between test methods — no manual cleanup needed
- CPT generates a **coverage report** in `target/coverage-report/report.html` after each run — mention it in output

---

## Reference Links

- [CPT Getting Started](https://docs.camunda.io/docs/apis-tools/testing/getting-started/)
- [CPT Assertions](https://docs.camunda.io/docs/apis-tools/testing/assertions/)
- [CPT Utilities](https://docs.camunda.io/docs/apis-tools/testing/utilities/)
- [CPT Configuration](https://docs.camunda.io/docs/apis-tools/testing/configuration/)
- [CPT Example on GitHub](https://github.com/camunda/camunda/tree/main/testing/camunda-process-test-example)


