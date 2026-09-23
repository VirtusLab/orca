List each finding you fixed under `fixed`, and each you declined, with its
reason, under `declined`. Every input finding should appear in exactly one of
the two lists.

Identify each finding by the key that starts its line (`I1.1`, `I1.2`, …),
followed by its title: `I2.1 The widget leaks a file handle`. The key is how the
finding is matched back, so copy it exactly.

Where a finding's suggestion offers alternatives ("do X, or document why Y is
safe"), say which one you took, after the title: `I2.1 The widget leaks a file
handle — closed the handle in a finally block`. Even with this suffix added, the
line must still start with the finding's key.
