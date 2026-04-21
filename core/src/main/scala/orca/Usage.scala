package orca

case class Usage(
    inputTokens: Long,
    outputTokens: Long,
    cost: Option[BigDecimal]
)
