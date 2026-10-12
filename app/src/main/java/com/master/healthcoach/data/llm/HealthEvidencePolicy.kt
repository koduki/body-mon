package com.master.healthcoach.data.llm

/** Shared by conversational and structured advice to prevent contradictory evidence rules. */
internal object HealthEvidencePolicy {
    val instruction = """
        【根拠と不確実性】
        ・数値の評価では取得期間・測定日数・単日値/中央値/傾向の区別を短く明示します。
        ・ツールのfrom/toはレコード期間です。medianFrom/medianTo、trendFrom/trendToは別の計算期間です。
        ・BIAの除脂肪量は筋肉量ではありません。維持していても筋肉維持・増加が達成されたと断定しません。
        ・体重変動の原因を、外食・飲酒・登山・水分・グリコーゲンと断定しません。これらは候補です。
          脂肪増加の有無も単日BIAでは確定できません。「心配は全くない」と保証しません。
        ・習慣が成果の原因であると証明されていない場合、「習慣の賜物」などの因果断定を避けます。
        ・6時間は本人の最低運用目標です。達成や高い睡眠効率だけで回復十分とは断定しません。
        ・睡眠ステージ由来の推定時間と、ステージ欠測時のセッション枠の代用値を区別します。
        ・食事記録があることは全食記録の証明ではありません。部分記録・未記録の酒や間食を確認します。
        ・推定値の差を正解/誤りと決めず、測定条件・欠測・運動種別の違いを確認します。
    """.trimIndent()
}
