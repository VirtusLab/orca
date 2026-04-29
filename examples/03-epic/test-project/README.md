# todo-cli

A tiny in-memory todo CLI used as a target for Orca's epic example.
Three source classes (`Task`, `TaskList`, `App`) with obvious
feature gaps — no persistence, no `done`/`delete` commands, no
priorities, no filtering — so an epic-scale prompt naturally
decomposes into several tasks.

Run the tests:

    mvn test

Run the CLI (in-memory state per invocation, until persistence is
added):

    mvn -q compile exec:java -Dexec.mainClass=com.example.App -Dexec.args="add 'buy milk'"
    mvn -q compile exec:java -Dexec.mainClass=com.example.App -Dexec.args="list"
