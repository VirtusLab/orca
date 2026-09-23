package orca.testkit

import orca.tools.PrHandle

/** Test shorthand for a PR URL the test knows is valid. */
def prHandle(url: String): PrHandle =
  PrHandle
    .fromUrl(url)
    .getOrElse(throw new IllegalArgumentException(url))
