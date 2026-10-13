package com.master.healthcoach.data.llm

import com.master.healthcoach.data.db.GoalEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Partial updates preserve unrelated goals and require evidence from the current user turn. */
internal object ConversationProfileUpdate {
    fun apply(current: GoalEntity?, arguments: JsonObject, userMessage: String?): GoalEntity {
        val evidence = arguments["evidence"]?.jsonPrimitive?.contentOrNull
        require(!evidence.isNullOrBlank() && userMessage?.contains(evidence) == true) {
            "現在の本人発言に含まれる根拠が必要です"
        }
        val age = arguments["age"]?.jsonPrimitive?.contentOrNull?.let {
            val value = it.toIntOrNull()
            require(value != null && value in 13..120) { "年齢は13〜120歳で指定してください" }
            value
        }
        val height = arguments["heightCm"]?.jsonPrimitive?.contentOrNull?.let {
            val value = it.toDoubleOrNull()
            require(value != null && value.isFinite() && value in 100.0..250.0) { "身長は100〜250cmで指定してください" }
            value
        }
        val sex = arguments["sex"]?.jsonPrimitive?.contentOrNull?.trim()?.also {
            require(it.isNotEmpty() && it.length <= 40) { "性別の入力を確認してください" }
        }
        require(age != null || height != null || sex != null) { "更新するプロフィール項目がありません" }
        return (current ?: GoalEntity()).let {
            it.copy(age = age ?: it.age, heightCm = height ?: it.heightCm, sex = sex ?: it.sex)
        }
    }
}
