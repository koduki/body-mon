package com.master.healthcoach.data.llm
import com.master.healthcoach.data.db.GoalEntity
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
class ConversationContextPolicyTest {
    @Test
    fun `pasted body profile cannot override settings and does not erase the monthly goal`() {
        val memory = "目標は月1kg減（42歳、178cm）\n通勤は自転車"
        val goal = GoalEntity(age = 41, heightCm = 172.0)
        val cleaned = ConversationContextPolicy.memoryForModel(memory, goal)
        assertEquals(2, ConversationContextPolicy.profileConflicts(memory, goal).size)
        assertFalse(cleaned.contains("178cm"))
        assertTrue(cleaned.contains("月1kg減"))
        assertTrue(cleaned.contains("通勤は自転車"))
        assertTrue(cleaned.contains("172.0cm"))
    }
    @Test
    fun `historical expert review is removed from model context only`() {
        val original = "過去の回答\n専門家ビュー（端末内KPI判定）\n7時間を確保"
        assertEquals("過去の回答", ConversationContextPolicy.historicalAssistantContext(original))
        assertTrue(original.contains("7時間"))
    }
    @Test
    fun `personal calorie evaluation fetches completed week separately from current day`() {
        val today = LocalDate.of(2026, 8, 20)
        val calls = ChatMetricPrefetch.calls("消費カロリーどう思う？", today)
        assertEquals(2, calls.size)
        assertTrue(calls[0].arguments.toString().contains("2026-08-19"))
        assertTrue(calls[1].arguments.toString().contains("2026-08-20"))
        assertTrue(ChatMetricPrefetch.calls("HIITの仕組みを説明して", today).isEmpty())
    }
    @Test
    fun `regression review identifies unsupported confident advice rather than accepting it`() {
        val warnings = ResponseEvidenceReview.warnings("400〜600 kcalは安全で現実的です。お休みは大正解です", false)
        assertTrue(warnings.contains("unconditional_certainty_requires_review"))
        assertTrue(warnings.contains("numeric_claim_without_metric_trace"))
        assertTrue(ResponseEvidenceReview.warnings("記録では300 kcalです。推定であり真値とは断定しません", true).isEmpty())
    }
}
