package orca.review

import java.util.Locale

/** A reviewer's identity: the name the picker is shown and asked to echo, and
  * what a discovered `.md` file's stem replaces a shipped reviewer by. Trimmed
  * and lower-cased at construction, so two spellings that differ only in case
  * or surrounding whitespace are the same reviewer.
  */
opaque type ReviewerSlug = String

object ReviewerSlug:
  def apply(raw: String): ReviewerSlug = raw.trim.toLowerCase(Locale.ROOT)

  extension (slug: ReviewerSlug) def value: String = slug
