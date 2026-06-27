package orca.review

import orca.agents.JsonData

case class ReviewContext(summary: String, filesChanged: List[String])
    derives JsonData
