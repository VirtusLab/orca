package example

class CalculatorTest extends munit.FunSuite:
  test("addsTwoPositiveNumbers"):
    assertEquals(new Calculator().add(2, 3), 5)

  test("subtractsTwoNumbers"):
    assertEquals(new Calculator().subtract(3, 2), 1)
