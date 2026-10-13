package com.master.healthcoach.domain

/** Record timestamps describe storage intervals; they do not prove eating times. */
object NutritionDetailPolicy {
    fun hasMealType(isDailyRecord: Boolean, mealType: Int): Boolean =
        !isDailyRecord && mealType in 1..4

    fun recordedMealCount(details: List<Pair<Boolean, Int>>): Int? =
        details.takeIf { it.isNotEmpty() && it.all { (daily, type) -> hasMealType(daily, type) } }?.size

    fun timeLabel(isDailyRecord: Boolean, mealType: Int, recordTime: String): String =
        if (hasMealType(isDailyRecord, mealType)) "記録区間 $recordTime（食事時刻は未確認）"
        else "食事時刻・区分は未取得"

    const val LIMITATION = "あすけんの現在の連携は日次栄養記録です。食事時刻・朝昼夕間食の区分・食事回数は未取得。記録の00:00やstart/endを食べた時刻と解釈せず、時刻から区分を推測しません。"
}
