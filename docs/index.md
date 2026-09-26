# Orca: deterministic, AI-driven development flows

Orca allows you to programmatically define software development workflows where
AI agents perform the coding. If you want AI-generated code to always be
reviewed by another agent, don't try to coerce the agents; just express that
requirement in code. Don't waste tokens on formatting, committing, or creating
PRs - all of this can be handled by an ordinary script.

Orca comes with an `orca` cli, which can be used interactively by humans, or
headlessly by humans and agents alike. A number of built-in flows, implementing
e.g. a plan-implement-review loop, allow you to start using Orca right away.

Orca flow scripts are written in Scala, and can be run with a single command
through [scala-cli](https://scala-cli.virtuslab.org). Orca's development flows
are resumable, so that if work is interrupted mid-flow for any reason, it can
be continued from the last commit. You can use Orca to orchestrate development
in any language and ecosystem.

Orca is developed by [VirtusLab](https://virtuslab.com) and hosted on
[GitHub](https://github.com/VirtusLab/orca).

```{eval-rst}
.. toctree::
   :maxdepth: 2
   :caption: Getting started

   getting-started/quickstart
   getting-started/ways-to-use
   getting-started/how-it-works

.. toctree::
   :maxdepth: 2
   :caption: Using Orca

   using/shell
   using/agent-clis
   using/built-in-flows
   using/settings
   using/reviewers
   using/run-lifecycle
   using/output-and-files

.. toctree::
   :maxdepth: 2
   :caption: Authoring flows

   authoring/tutorial
   authoring/stages
   authoring/choosing-agents
   authoring/talking-to-agents
   authoring/planning
   authoring/review
   authoring/gates-and-checks
   authoring/pull-requests
   authoring/extending
   authoring/capabilities

.. toctree::
   :maxdepth: 2
   :caption: API reference

   api/backends
   api/tools
   api/data-structures

.. toctree::
   :maxdepth: 2
   :caption: Glossary

   glossary/users
   glossary/developers

.. toctree::
   :maxdepth: 2
   :caption: Development

   development
```
