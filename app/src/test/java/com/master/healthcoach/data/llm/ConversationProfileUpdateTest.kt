package com.master.healthcoach.data.llm

import com.master.healthcoach.data.db.GoalEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ConversationProfileUpdateTest {
    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun `latest self report updates settings while preserving other fields`() {
        val current = GoalEntity(age = 41, heightCm = 172.0, sex = "男性", monthlyWeightLossKg = 1.0)
        val updated = ConversationProfileUpdate.apply(current,
            args("""{"age":42,"heightCm":178,"evidence":"42歳、178cmです"}"""), "私は42歳、178cmです")
        assertEquals(42, updated.age)
        assertEquals(178.0, updated.heightCm!!, 0.0)
        assertEquals("男性", updated.sex)
        assertEquals(1.0, updated.monthlyWeightLossKg!!, 0.0)
        val corrected = ConversationProfileUpdate.apply(updated,
            args("""{"heightCm":177,"evidence":"身長は177cmに訂正"}"""), "身長は177cmに訂正")
        assertEquals(177.0, corrected.heightCm!!, 0.0)
        assertEquals(42, corrected.age)
    }

    @Test fun `missing current evidence and invalid ranges do not produce an update`() {
        for (input in listOf(
            """{"age":42,"evidence":"昔42歳と言った"}""",
            """{"age":999,"evidence":"42歳です"}""",
            """{"heightCm":0,"evidence":"42歳です"}""",
            """{"evidence":"42歳です"}"""
        )) {
            assertTrue(runCatching { ConversationProfileUpdate.apply(null, args(input), "42歳です") }.isFailure)
        }
    }
}
