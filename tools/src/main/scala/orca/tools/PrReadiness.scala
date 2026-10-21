package orca.tools

import orca.json.JsonData

/** Whether [[GitHubTool.createPr]] opens a PR ready for review or as a draft.
  */
enum PrReadiness derives JsonData:
  case Ready
  case Draft
