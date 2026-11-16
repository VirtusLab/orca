package orca.backend.mcp

import orca.json.given

import scala.annotation.experimental

// tapir's Magnolia derivation copies the annotations of these safe-mode
// classes, `@assumeSafe` among them, which only experimental code may use.
// Not `-experimental` on tools' tests: it marks every test definition
// experimental, which the backend modules' tests reuse.
@experimental
class McpInputSchemaParityTest extends orca.json.SchemaParitySuite:
  parity[AskUserInput]
  parity[GitHubIssueInput]
  parity[GitShowInput]
  parity[GitFileAtInput]
