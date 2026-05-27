package orca.review

import orca.llm.JsonData

case class ReviewContext(summary: String, filesChanged: List[String])
    derives JsonData
