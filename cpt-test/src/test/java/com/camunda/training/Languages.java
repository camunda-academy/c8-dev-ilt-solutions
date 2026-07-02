package com.camunda.training;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper for the {@code -Dlang} test parameter.
 *
 * <p>The same process behaves identically regardless of which language implements the workers,
 * so the tests are repeatable per language. The user starts ONE worker implementation manually
 * before running a test; this class only resolves which language was selected and verifies that
 * the corresponding language folder actually contains an implementation. Empty / work-in-progress
 * folders (e.g. {@code java}, {@code java-spring} on this branch) are reported as "not available"
 * so the test can be skipped (not failed).
 */
final class Languages {

  /** All language folders the training repo may contain. */
  static final List<String> ALL = List.of("java", "java-spring", "python", "csharp", "js");

  private Languages() {}

  /** Repo root = parent of the {@code cpt-test} module directory. */
  static Path repoRoot() {
    return Path.of(System.getProperty("user.dir")).getParent();
  }

  /**
   * Resolve the requested languages from {@code -Dlang}. {@code all} (the default) expands to every
   * language; otherwise a comma-separated list is honoured (e.g. {@code -Dlang=python,js}).
   */
  static List<String> requested() {
    String raw = System.getProperty("lang", "all").trim();
    if (raw.isEmpty() || raw.equalsIgnoreCase("all")) {
      return ALL;
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  /**
   * True if the language folder exists and contains a real implementation (any source file beyond
   * a {@code .gitkeep}). WIP/empty folders return false so the test is skipped.
   */
  static boolean isAvailable(String lang) {
    Path dir = repoRoot().resolve(lang);
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (Stream<Path> entries = Files.walk(dir)) {
      return entries
          .filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .anyMatch(name -> !name.equals(".gitkeep"));
    } catch (Exception e) {
      return false;
    }
  }

  /** The requested languages that actually have an implementation on this branch. */
  static List<String> requestedAndAvailable() {
    return requested().stream().filter(Languages::isAvailable).collect(Collectors.toList());
  }
}
