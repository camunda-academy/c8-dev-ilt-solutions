package com.camunda.training;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Runs all exercise tests together. Add new {@code ExerciseNNTest} classes here as they are created.
 *
 * <p>Run with: {@code mvn test -Dtest=AllExercisesTestSuite -Dlang=python} (or {@code -Dlang=all}).
 *
 * <p>Each test honours the {@code -Dlang} parameter and skips languages that have no implementation
 * on the current branch.
 */
@Suite
@SelectClasses({
  Exercise05Test.class,
  Exercise05ScenarioReplayTest.class,
  Exercise06Test.class,
  Exercise06ScenarioReplayTest.class,
  Exercise07Test.class,
  Exercise07ScenarioReplayTest.class,
  Exercise08Test.class,
  Exercise08ScenarioReplayTest.class,
  Exercise09Test.class,
  Exercise09ScenarioReplayTest.class
})
class AllExercisesTestSuite {}
