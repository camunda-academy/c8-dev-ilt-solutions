# Trainer Guide

Minimal runbook for trainers updating exercise solutions.

## Goal

Update an exercise branch safely, trigger CPT checks via pull request, and merge only after review.

## Update Flow

1. Start from the target exercise branch:

```bash
git checkout exercise-09
```

2. Create a feature branch:

```bash
git checkout -b feature/exercise-09
```

3. Make your code changes and commit:

```bash
git add .
git commit -m "Describe your change"
```

4. Push the feature branch:

```bash
git push -u origin feature/exercise-09
```

5. Open a pull request:
- Base branch: `exercise-09`
- Compare branch: `feature/exercise-09`

6. Wait for the `CPT tests` check and review results.

7. If failed:
- Open the failed run in Actions.
- Download the artifact (`cpt-results-...`) for logs and reports.
- Push fixes to the same feature branch.

8. Merge the PR after checks/review are complete.

## Notes

- Protected exercise branches should not be pushed directly.
- Use feature branch names that do not start with `exercise-` (for example `feature/...`) so
  branch protection on `exercise-*` does not block the push.
- CPT is triggered by pull requests to `exercise-*` branches.
- Language selection is change-aware in CI:
  - Changes limited to one language folder (for example `js/`) run only that language.
  - Shared changes (for example `assets/`, workflow files, or mixed areas) run all languages.

## New Exercise Checklist

When adding a new `exercise-NN` branch:

1. Ensure `.github/workflows/test.yml` exists on that branch.
2. Add/update exercise tests in the harness branch (`integration-test`) as needed.
3. Open a PR and verify the CPT check appears and runs.

## Reference

For harness implementation details, see:

- https://github.com/camunda-academy/c8-dev-ilt-solutions/blob/integration-test/cpt-test/README.md