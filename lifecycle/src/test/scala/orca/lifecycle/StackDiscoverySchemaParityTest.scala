package orca.lifecycle

import orca.json.given

@scala.annotation.experimental
class StackDiscoverySchemaParityTest extends orca.json.SchemaParitySuite:
  parity[DiscoveredCommand]
  parity[DiscoveredGate]
  parity[StackDiscoveryResult]
  parity[StackDiscoveryReply]
