# c8-dev-ilt-solutions — `integration-test` branch

**This is instructor/maintainer tooling, not a training exercise.** If you're a trainee looking for
exercise solutions, check out an `exercise-NN` branch instead (`git checkout exercise-05`, etc.) —
this branch has no worker code and isn't part of the course material.

This branch holds the CPT (Camunda Process Test) suite that verifies every exercise's solutions
still work, end-to-end, against the real job workers — automatically on every push. See
[`cpt-test/README.md`](cpt-test/README.md) for how to run it, and
[`cpt-test/ALL-EXERCISES-PLAN.md`](cpt-test/ALL-EXERCISES-PLAN.md) for the full design, the
GitHub Actions setup, and the checklist for adding a new exercise.
