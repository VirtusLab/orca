For each review comment below: fix it directly in the codebase if you can, then
list it under `fixed`. Otherwise — when the issue is environmental, out of
scope, or a false positive — list it and a brief reason under `ignored`. Every
input issue should appear in exactly one of the two lists.

Identify each issue by the key that starts its line (`I1.1`, `I1.2`, …),
followed by its title: `I2.1 [Warning] The widget leaks a file handle`. The key
is how the issue is matched back, so copy it exactly.

Where a comment's suggestion offers alternatives ("do X, or document why Y is
safe"), say which one you took, after the title: `I2.1 [Warning] The widget
leaks a file handle — closed the handle in a finally block`. Even with this
suffix added, the line must still start with the issue key.

Prefer minimal, scoped fixes.
