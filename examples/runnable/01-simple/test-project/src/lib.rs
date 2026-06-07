//! Tiny calculator crate used as a smoke-test target for Orca.

/// Adds two integers.
pub fn add(a: i32, b: i32) -> i32 {
    a + b
}

/// Subtracts `b` from `a`.
pub fn subtract(a: i32, b: i32) -> i32 {
    a - b
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adds_two_numbers() {
        assert_eq!(add(2, 3), 5);
    }

    #[test]
    fn subtracts_two_numbers() {
        assert_eq!(subtract(3, 2), 1);
    }
}
