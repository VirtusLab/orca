#!/usr/bin/env bash
#
# Creates a throwaway Java project in a temp directory (or a path of your
# choice) with a seeded git history and the `ship.sc` flow from the README.
# Use it to exercise Orca end-to-end against a clean, disposable repo.
#
# Usage:
#   examples/create-test-project.sh                # mktemp destination
#   examples/create-test-project.sh /path/to/dir   # explicit destination

set -euo pipefail

DEST="${1:-}"
if [[ -z "$DEST" ]]; then
  DEST="$(mktemp -d -t orca-test-XXXXXX)"
else
  mkdir -p "$DEST"
fi

cd "$DEST"

mkdir -p src/main/java/com/example src/test/java/com/example

cat > src/main/java/com/example/Calculator.java <<'EOF'
package com.example;

public class Calculator {
  public int add(int a, int b) {
    return a + b;
  }

  public int subtract(int a, int b) {
    return a - b;
  }
}
EOF

cat > src/test/java/com/example/CalculatorTest.java <<'EOF'
package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CalculatorTest {
  @Test
  void addsTwoNumbers() {
    assertEquals(5, new Calculator().add(2, 3));
  }

  @Test
  void subtractsTwoNumbers() {
    assertEquals(1, new Calculator().subtract(3, 2));
  }
}
EOF

cat > pom.xml <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>calculator</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <properties>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.10.2</version>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>
    </plugins>
  </build>
</project>
EOF

cat > README.md <<'EOF'
# calculator

Tiny Java project used as a smoke-test target for Orca.

Run the tests:

    mvn test

Drive Orca at it (requires `com.virtuslab::orca:0.1.0-SNAPSHOT` published
locally via `sbt publishLocal` in the orca-sandbox checkout):

    scala-cli run ship.sc -- "Add a multiply method to Calculator"
EOF

cat > ship.sc <<'EOF'
//> using dep "com.virtuslab::orca:0.1.0-SNAPSHOT"
//> using repository ivy2Local
//> using jvm 21

import orca.{*, given}

/** A single task in the plan. `summary` is a short user-facing label
  * (drives the implement-stage name and the printed plan list); `prompt`
  * is the longer instruction sent verbatim to the LLM. Aim for `summary`
  * around 60 chars — anything longer truncates in the status bar.
  */
case class Task(
    branchName: String,
    summary: String,
    prompt: String
) derives JsonData

case class Plan(tasks: List[Task]) derives JsonData

// `args` is scala-cli's top-level script argv. `OrcaArgs.from` parses
// positional + flag arguments (`userPrompt`, `--verbose`, etc.).
flow(OrcaArgs.from(args.toSeq)):
  // 1. Break the user's prompt into concrete subtasks, interactively.
  val (sessionId, plan) = stage("Creating a development plan"):
    claude.resultAs[Plan].interactive(userPrompt)

  // The renderer suppresses the agent's raw JSON payload; print the
  // parsed plan in human-readable form here instead.
  println(s"● Planned branch: ${plan.tasks.headOption.map(_.branchName).getOrElse("(none)")}")
  println(s"● Defined ${plan.tasks.size} task(s):")
  plan.tasks.foreach(t => println(s"  - ${t.summary}"))

  // 2. Implement each task on its own branch and review locally.
  for task <- plan.tasks do
    stage(s"Implement task: ${task.summary}"):
      git.createBranch(task.branchName)
      claude.continueSession(sessionId, task.prompt)
      git.commit(s"Implement ${task.summary}")

      reviewAndFixLoop(
        coder = claude,
        sessionId = sessionId,
        reviewers = defaultReviewers(claude),
        task = task.summary,
        lintCommand = Some("mvn -q test")
      )
EOF

cat > .gitignore <<'EOF'
target/
*.class
.scala-build/
.bsp/
EOF

git init -q -b main
git -c user.name=orca-seed -c user.email=orca-seed@example.com \
    -c init.defaultBranch=main \
    add . > /dev/null
git -c user.name=orca-seed -c user.email=orca-seed@example.com \
    commit -q -m "Initial calculator project"

echo "Test project ready at: $DEST"
echo ""
echo "Next steps:"
echo "  cd $DEST"
echo "  scala-cli run ship.sc -- \"Add a multiply method to Calculator\""
