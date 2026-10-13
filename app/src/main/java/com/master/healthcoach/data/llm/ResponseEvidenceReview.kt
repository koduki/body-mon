package com.master.healthcoach.data.llm

/** Local review hints, not a medical classifier or a guarantee of answer correctness. */
internal object ResponseEvidenceReview {
    fun warnings(answer: String, hasMetricEvidence: Boolean): List<String> = buildList {
        if (answer.contains("専門家ビュー（端末内KPI判定）")) add("legacy_review_repeated")
        if (Regex("大正解|完全にオーバーワーク|全く問題ありません|絶対に.*毎日|安全で現実的").containsMatchIn(answer)) {
            add("unconditional_certainty_requires_review")
        }
        if (!hasMetricEvidence && Regex("\\d+(?:\\.\\d+)?\\s*(?:kcal|kg|歩|bpm)", RegexOption.IGNORE_CASE).containsMatchIn(answer)) {
            add("numeric_claim_without_metric_trace")
        }
    }
}
