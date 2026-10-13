package com.master.healthcoach.data.llm

import com.master.healthcoach.data.HealthRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*

class HealthToolExecutor(
    private val repository: HealthRepository,
    todayProvider: () -> LocalDate = { LocalDate.now() },
    zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val queries = HealthQueryService(repository, todayProvider, zoneIdProvider)
    suspend fun execute(call: GeminiFunctionCall, userMessage: String? = null): JsonElement =
        if (call.name == "update_profile") updateProfile(call, userMessage) else queries.execute(call)

    private suspend fun updateProfile(call: GeminiFunctionCall, userMessage: String?): JsonElement {
        return try {
            val updated = ConversationProfileUpdate.apply(repository.getGoal(), call.arguments, userMessage)
            repository.saveGoal(updated)
            buildJsonObject {
                put("status", "saved")
                put("profile", updated.existingAiProfile())
                put("message", "会話のプロフィールを設定へ反映しました。設定画面で修正できます")
            }
        } catch (error: IllegalArgumentException) {
            buildJsonObject { put("error", error.message ?: "プロフィールを更新できません") }
        }
    }

}
