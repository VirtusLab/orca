package orca.review

import orca.llm.{JsonData, LlmTool}

case class SelectedReviewers(names: List[String]) derives JsonData:
  def pick(all: List[LlmTool[?]]): List[LlmTool[?]] =
    all.filter(r => names.contains(r.name))
