package orca.json

case class Leaf(name: String, size: Option[Int]) derives JsonData
case class Box[A](value: A, all: List[A])
object Box:
  given JsonData[Box[Leaf]] = JsonData.derived

sealed trait Node derives JsonData
case object Empty extends Node
case class Branch(left: Leaf, pair: (Leaf, Int)) extends Node

sealed trait Shape derives JsonData
sealed trait Round extends Shape
case class Circle(radius: Int) extends Round
case object Square extends Shape

enum Mixed derives JsonData:
  case Zero
  case One(n: Int)
  case Another

// Shapes the codebase's own `JsonData` types don't all exercise.
class SchemaDerivationTest extends SchemaParitySuite:
  parity[Leaf]
  parity[Box[Leaf]]
  parity[Node]
  parity[Shape]
  parity[Mixed]
  parity[(Leaf, Int)]
  parity[(Leaf, Int, Boolean)]
