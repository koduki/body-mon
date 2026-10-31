package com.master.healthcoach.domain

import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.ChatMessageEntity
import com.master.healthcoach.data.db.ConversationMemoryEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.db.HealthSourceStatusEntity
import com.master.healthcoach.data.db.NutritionMealEntity
import com.master.healthcoach.data.db.estimatedEnergyBalanceKcal
import com.master.healthcoach.data.llm.ChatHistoryPolicy
import com.master.healthcoach.data.llm.ConversationContextPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class HealthDataExportPayload(
    val schemaVersion: Int = 2,
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val exportedAtIso: String,
    val exportedAtEpochMillis: Long,
    val timeZone: String,
    val periodStart: String,
    val periodEnd: String,
    val daysIncluded: Int = HealthDataExporter.EXPORT_DAYS.toInt(),
    val goal: GoalEntity? = null,
    val weeklySnapshot: WeeklySnapshot? = null,
    val measuredData: MeasuredDataExport,
    val conversationHistory: ConversationHistoryExport,
)

@Serializable
data class MeasuredDataExport(
    val dailySummaries: List<DailyHealthSummaryEntity>,
    val bodyCompositions: List<BodyCompositionEntity>,
    val exerciseSessions: List<ExerciseSessionEntity>,
    val nutritionMeals: List<NutritionMealEntity>,
)

@Serializable
data class ConversationHistoryExport(
    val messageCount: Int,
    val messages: List<ExportedChatMessage>,
    val conversationMemory: ExportedConversationMemory? = null,
)

@Serializable
data class ExportedChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val attachmentNames: List<String> = emptyList(),
    val createdAtEpochMillis: Long,
    val createdAtIso: String,
    val modelId: String? = null,
    val appVersionName: String? = null,
    val policyVersion: String? = null,
    val toolTraceJson: String? = null,
)

@Serializable
data class ExportedConversationMemory(
    val summary: String,
    val summarizedThroughMessageId: Long,
    val updatedAtEpochMillis: Long,
    val updatedAtIso: String,
)

@Serializable
data class WeeklyAiExportPayload(
    val schemaVersion: Int = 2,
    val exportType: String = "weekly_ai_analysis",
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val commitSha: String = BuildConfig.COMMIT_SHA,
    val policyVersion: String = ConversationContextPolicy.VERSION,
    val dataRevision: String? = null,
    val lastSyncSuccessLocal: String? = null,
    val lastSyncOutcome: String? = null,
    val exportedAtIso: String,
    val exportedAtEpochMillis: Long,
    val timeZone: String,
    val todayDate: String,
    val weekStart: String,
    val weekEnd: String,
    val periodType: String = "completed_7_days_excluding_today",
    val daysIncluded: Int = HealthDataExporter.WEEKLY_EXPORT_DAYS.toInt(),
    val trendWindowDays: Int = HealthDataExporter.EXPORT_DAYS.toInt(),
    val analysisContext: WeeklyAiAnalysisContext = WeeklyAiAnalysisContext(),
    val goal: GoalEntity? = null,
    val goalStatus: WeeklyGoalStatusExport? = null,
    val dataQuality: WeeklyDataQualityExport,
    val weeklySummary: WeeklySnapshot,
    val sevenDayDetails: WeeklyMeasuredDetailsExport,
    val todayPartial: WeeklyTodayPartialExport? = null,
)

@Serializable
data class WeeklyAiAnalysisContext(
    val dataQualityRules: String =
        "分析前に必ずdataQualityセクションを確認し、各分野のusableフラグとinterpretationに従うこと。" +
            "心拍が運動中のみ（allDaysWorkoutOnly=true / interpretation=workout_only_not_resting）の場合、" +
            "週間平均心拍や日別平均心拍を安静時心拍・日常心拍・回復指標として解釈してはならない。" +
            "当日の途中データはtodayPartialに分離されており、weeklySummaryとsevenDayDetailsは完了済み7日間（weekStart〜weekEnd）で統一されている。",
    val dietPolicy: String =
        "低脂質ダイエット方針。脂質エネルギー比15〜25%を運用目安とし、30%超は脂質の出どころを確認。" +
            "たんぱく質は減量中の筋力維持のため体重1kgあたり1.6g/日以上を目安とする。" +
            "食事記録がある日のみを観測値とし、欠測日を0kcal扱いしない。" +
            NutritionDetailPolicy.LIMITATION,
    val bodyCompositionRules: String =
        "体重を速く落とすことではなく、筋力と回復を守りながら脂肪を減らすことを優先し、" +
            "28日ロバスト傾向（Theil-Sen推定）で体重の0.3〜0.7%/週減を運用目安とする。" +
            "BIA由来の体脂肪率・脂肪量・除脂肪量は水分等で日々変動するため単日値で断定せず、" +
            "7日中央値や28日傾向で判断する。除脂肪量は体重−脂肪量の計算値であり、骨格筋量そのものではない。",
    val exerciseAndRoutineRules: String =
        "Health Connectの「その他のワークアウト（Other Workout）」のうち、朝4〜10時開始・1〜15分の記録のみを" +
            "朝トレ候補（category=morning_routine、軽い筋トレ＋有酸素の習慣KPI）として扱う。" +
            "それ以外の時間帯・長時間のOther Workoutは「一般運動（内容未確認）」（category=other, otherExerciseMinutes）として区別する。" +
            "strengthMinutes=0でもotherExerciseMinutes>0の場合は筋トレ未実施の確定ではなく「未確認運動あり（unverified_other_workouts_present）」として扱う。" +
            "心拍ゾーンや運動強度の実測値がない場合はトレーニング負荷を断定しない。" +
            "筋力維持そのものは朝トレ日数では証明せず、月1回のパーソナルでの重量・回数で確認する。",
    val energyAndExpenditureRules: String =
        EnergyModel.INTERPRETATION + " modelVersion=${EnergyModel.VERSION}",
    val sleepAndRecoveryRules: String =
        "睡眠6時間（360分）は本人の最低運用目標であり、回復十分の判定ではない（達成日はセッション枠で数える）。" +
            "sleepMinutesは睡眠ステージ合計（sleep_stages / sleep_stages_partial）、ステージ欠測時は" +
            "セッション枠で代用（session_fallback / legacy_session）して区別する。" +
            "主睡眠区間の心拍サンプルがない日（sleepHeartRateStatus=no_samples_in_main_sleep）やベースライン日数不足時は、" +
            "睡眠時間のみで身体回復を断定せず、dataQuality.sleepAndRecoveryを参照する。",
)

@Serializable
data class WeeklyGoalStatusExport(
    val deadline: String? = null,
    val deadlineExpired: Boolean = false,
    val fatMassToGoalKg: Double? = null,
    val requiredFatLossKgPerWeek: Double? = null,
    val monthlyWeightLossTargetKg: Double? = null,
    val targetLossRatePercentPerWeek: Double? = null,
    val evaluationRule: String,
)

@Serializable
data class WeeklyDataQualityExport(
    val bodyComposition: WeeklyBodyQualityExport,
    val activityAndEnergy: WeeklyActivityQualityExport,
    val exercise: WeeklyExerciseQualityExport,
    val heartRate: WeeklyHeartRateQualityExport,
    val sleepAndRecovery: WeeklySleepQualityExport,
    val nutrition: WeeklyNutritionQualityExport,
    val sources: List<WeeklySourceStatusExport> = emptyList(),
)

@Serializable
data class WeeklyBodyQualityExport(
    val weeklyMeasurementDays: Int,
    val trend28dMeasurementDays: Int,
    val usableForWeeklyMedian: Boolean,
    val usableFor28dRobustTrend: Boolean,
    val measurementTimeConsistencyPercent: Int? = null,
    val statusNote: String,
)

@Serializable
data class WeeklyActivityQualityExport(
    val stepsMeasurementDays: Int,
    val activeCaloriesMeasurementDays: Int,
    val basalCaloriesMeasurementDays: Int,
    val activityIntensityMeasurementDays: Int,
    val largeActiveDiscrepancyDays: Int,
    val usableForStepTrend: Boolean,
    val usableForActivityIntensity: Boolean,
    val wearableSourcePolicy: String = "xiaomi_only_v1",
    val selectedWearableOrigin: String = "com.xiaomi.wearable",
)

@Serializable
data class WeeklyExerciseQualityExport(
    val totalSessions: Int,
    val totalExerciseMinutes: Long,
    val morningRoutineDays: Int,
    val morningRoutineMinutes: Long,
    val confirmedStrengthMinutes: Long,
    val cardioMinutes: Long,
    val cyclingMinutes: Long,
    val hikingMinutes: Long,
    val hiitMinutes: Long,
    val unverifiedOtherSessions: Int,
    val unverifiedOtherMinutes: Long,
    val strengthAssessmentStatus: String,
    val heartRateIntensityAvailable: Boolean = false,
    val statusNote: String,
)

@Serializable
data class WeeklyHeartRateQualityExport(
    val measurementDays: Int,
    val workoutOnlyDays: Int,
    val outsideExerciseObservedDays: Int,
    val totalOutsideExerciseSamples: Int,
    val averageCoverageMinutes: Int? = null,
    val allDaysWorkoutOnly: Boolean,
    val usableForDailyOrRestingTrend: Boolean,
    val interpretation: String,
    val statusNote: String,
)

@Serializable
data class WeeklySleepQualityExport(
    val sleepMeasurementDays: Int,
    val stageMeasuredDays: Int,
    val sessionFallbackDays: Int,
    val mainSleepHeartRateDays: Int,
    val baseline21dSleepHeartRateDays: Int,
    val usableForSleepDuration: Boolean,
    val usableForRecoveryHeartRateDelta: Boolean,
    val recoveryAssessmentStatus: String,
    val statusNote: String,
)

@Serializable
data class WeeklyNutritionQualityExport(
    val recordedDays: Int,
    val confirmedCompleteDays: Int,
    val emptyPlaceholderDays: Int,
    val dailySummaryFormatDays: Int,
    val typedMealRecordDays: Int,
    val mealTimingAndCountAvailable: Boolean,
    val usableForDietaryAndBalanceAnalysis: Boolean,
    val statusNote: String,
)

@Serializable
data class WeeklySourceStatusExport(
    val recordType: String,
    val status: String,
    val recordCount: Int,
    val origins: String = "",
    val latestRecordLocal: String? = null,
)

@Serializable
data class WeeklyMeasuredDetailsExport(
    val dailyRecords: List<WeeklyDailyRecordExport>,
    val exerciseSessions: List<WeeklyExerciseSessionExport>,
    val nutritionMeals: List<WeeklyNutritionMealExport>,
)

@Serializable
data class WeeklyTodayPartialExport(
    val date: String,
    val dayOfWeek: String,
    val asOfLocal: String,
    val isPartialDay: Boolean = true,
    val note: String =
        "当日の途中データ（速報値）です。完了済み7日間（weeklySummary / sevenDayDetails）の集計には含めていません。",
    val dailyRecord: WeeklyDailyRecordExport? = null,
    val exerciseSessions: List<WeeklyExerciseSessionExport> = emptyList(),
    val nutritionMeals: List<WeeklyNutritionMealExport> = emptyList(),
)

@Serializable
data class WeeklyDailyRecordExport(
    val date: String,
    val dayOfWeek: String,
    val relativeDay: String,
    val isPartialDay: Boolean = false,
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
    val fatMassKg: Double? = null,
    val leanBodyMassKg: Double? = null,
    val leanMassSource: String? = null,
    val bodyMeasuredAtLocal: String? = null,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val cyclingMinutes: Long = 0,
    val hikingMinutes: Long = 0,
    val hiitMinutes: Long = 0,
    val moderateIntensityMinutes: Long? = null,
    val vigorousIntensityMinutes: Long? = null,
    val moderateEquivalentMinutes: Long? = null,
    val exerciseSessionCount: Int = 0,
    val exerciseMinutes: Long = 0,
    val morningRoutineMinutes: Long = 0,
    val strengthMinutes: Long = 0,
    val cardioMinutes: Long = 0,
    val otherExerciseMinutes: Long = 0,
    val sleepMinutes: Long? = null,
    val sleepSessionMinutes: Long? = null,
    val sleepDurationSource: String = "no_record",
    val meetsSixHourOpportunity: Boolean? = null,
    val sleepStartLocal: String? = null,
    val sleepEndLocal: String? = null,
    val sleepHeartRateAverageBpm: Long? = null,
    val sleepHeartRateStatus: String = "no_sleep_record",
    val heartRateAverageBpm: Long? = null,
    val heartRateMinimumBpm: Long? = null,
    val heartRateMaximumBpm: Long? = null,
    val heartRateMeasurementCount: Long? = null,
    val heartRateCoverageMinutes: Int? = null,
    val heartRateOutsideExerciseSamples: Int? = null,
    val heartRateInterpretation: String? = null,
    val basalCaloriesKcal: Double? = null,
    val activeCaloriesDeviceKcal: Double? = null,
    val referenceStepModelActiveKcal: Double? = null,
    val referenceEvaluatedActiveKcal: Double? = null,
    val referenceActiveRangeLowKcal: Double? = null,
    val referenceActiveRangeHighKcal: Double? = null,
    val isLargeActiveDiscrepancy: Boolean? = null,
    val intakeCaloriesKcal: Double? = null,
    val nutritionComplete: Boolean = false,
    val nutritionRecordStatus: String = "no_records",
    val mealCount: Int? = null,
    val proteinGrams: Double? = null,
    val totalFatGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    val proteinEnergyPercent: Double? = null,
    val fatEnergyPercent: Double? = null,
    val carbohydrateEnergyPercent: Double? = null,
    val estimatedEnergyBalanceDeviceKcal: Double? = null,
    val referenceEnergyBalanceLowKcal: Double? = null,
    val referenceEnergyBalanceHighKcal: Double? = null,
    val origins: List<String> = emptyList(),
)

@Serializable
data class WeeklyExerciseSessionExport(
    val date: String,
    val dayOfWeek: String,
    val relativeDay: String,
    val startTimeLocal: String,
    val endTimeLocal: String,
    val exerciseType: Int,
    val exerciseLabel: String,
    val category: String,
    val durationMinutes: Long,
    val minutesInRequestedPeriod: Long,
    val dataOrigin: String,
)

@Serializable
data class WeeklyNutritionMealExport(
    val date: String,
    val dayOfWeek: String,
    val relativeDay: String,
    val startTimeLocal: String? = null,
    val endTimeLocal: String? = null,
    val mealLabel: String,
    val mealType: Int? = null,
    val isDailyRecord: Boolean = false,
    val detailStatus: String = "typed_meal_record",
    val intakeCaloriesKcal: Double? = null,
    val proteinGrams: Double? = null,
    val totalFatGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    val proteinEnergyPercent: Double? = null,
    val fatEnergyPercent: Double? = null,
    val carbohydrateEnergyPercent: Double? = null,
    val recordCount: Int,
    val dataOrigin: String,
)

object HealthDataExporter {
    const val EXPORT_DAYS = 28L
    const val WEEKLY_EXPORT_DAYS = 7L
    private const val SLEEP_BASELINE_MINUTES = 6 * 60L

    private val LOCAL_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.JAPANESE)
    private val LOCAL_DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.JAPANESE)
    private val DAY_OF_WEEK_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("E", Locale.JAPANESE)

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun formatAppVersionLabel(
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
    ): String = "v$versionName ($versionCode)"

    fun defaultFileName(today: LocalDate = LocalDate.now()): String =
        "health-coach-export-$today.json"

    fun defaultWeeklyFileName(today: LocalDate = LocalDate.now()): String =
        "health-coach-weekly-$today.json"

    fun dateRange(today: LocalDate): Pair<LocalDate, LocalDate> =
        today.minusDays(EXPORT_DAYS - 1) to today

    fun weeklyDateRange(
        today: LocalDate,
        includeToday: Boolean = false,
    ): Pair<LocalDate, LocalDate> {
        val periodEnd = if (includeToday) today else today.minusDays(1)
        return periodEnd.minusDays(WEEKLY_EXPORT_DAYS - 1) to periodEnd
    }

    fun epochRange(from: LocalDate, to: LocalDate, zoneId: ZoneId): Pair<Long, Long> {
        val startMillis = from.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillisInclusive = to.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli() - 1
        return startMillis to endMillisInclusive
    }

    fun buildPayload(
        exportedAt: ZonedDateTime = ZonedDateTime.now(),
        daily: List<DailyHealthSummaryEntity>,
        body: List<BodyCompositionEntity>,
        exerciseSessions: List<ExerciseSessionEntity>,
        nutritionMeals: List<NutritionMealEntity>,
        goal: GoalEntity?,
        weeklySnapshot: WeeklySnapshot?,
        chatMessages: List<ChatMessageEntity>,
        conversationMemory: ConversationMemoryEntity?,
    ): HealthDataExportPayload {
        val zoneId = exportedAt.zone
        val today = exportedAt.toLocalDate()
        val (from, to) = dateRange(today)
        val (startMillis, endMillis) = epochRange(from, to, zoneId)
        val fromStr = from.toString()
        val toStr = to.toString()

        val filteredDaily = daily
            .filter { it.date in fromStr..toStr }
            .sortedBy { it.date }
        val filteredBody = body
            .filter { it.date in fromStr..toStr }
            .sortedBy { it.date }
        val filteredSessions = exerciseSessions
            .filter { it.startEpochMillis in startMillis..endMillis }
            .sortedBy { it.startEpochMillis }
        val filteredMeals = nutritionMeals
            .filter { it.date in fromStr..toStr || it.startEpochMillis in startMillis..endMillis }
            .sortedBy { it.startEpochMillis }
        val filteredMessages = chatMessages
            .filter { it.createdAtEpochMillis in startMillis..endMillis }
            .sortedBy { it.id }
            .map { msg ->
                ExportedChatMessage(
                    id = msg.id,
                    role = msg.role,
                    content = msg.content,
                    attachmentNames = msg.attachmentNames
                        ?.lines()
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
                    createdAtEpochMillis = msg.createdAtEpochMillis,
                    createdAtIso = formatIso(msg.createdAtEpochMillis, zoneId),
                    modelId = msg.modelId,
                    appVersionName = msg.appVersionName,
                    policyVersion = msg.policyVersion,
                    toolTraceJson = msg.toolTraceJson,
                )
            }

        val exportedMemory = conversationMemory
            ?.takeIf { it.summary.isNotBlank() }
            ?.let { mem ->
                ExportedConversationMemory(
                    summary = mem.summary,
                    summarizedThroughMessageId = mem.summarizedThroughMessageId,
                    updatedAtEpochMillis = mem.updatedAtEpochMillis,
                    updatedAtIso = formatIso(mem.updatedAtEpochMillis, zoneId),
                )
            }

        return HealthDataExportPayload(
            exportedAtIso = exportedAt.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            exportedAtEpochMillis = exportedAt.toInstant().toEpochMilli(),
            timeZone = zoneId.id,
            periodStart = fromStr,
            periodEnd = toStr,
            daysIncluded = EXPORT_DAYS.toInt(),
            goal = goal,
            weeklySnapshot = weeklySnapshot,
            measuredData = MeasuredDataExport(
                dailySummaries = filteredDaily,
                bodyCompositions = filteredBody,
                exerciseSessions = filteredSessions,
                nutritionMeals = filteredMeals,
            ),
            conversationHistory = ConversationHistoryExport(
                messageCount = filteredMessages.size,
                messages = filteredMessages,
                conversationMemory = exportedMemory,
            ),
        )
    }

    fun buildWeeklyAiPayload(
        exportedAt: ZonedDateTime = ZonedDateTime.now(),
        daily: List<DailyHealthSummaryEntity>,
        body: List<BodyCompositionEntity>,
        exerciseSessions: List<ExerciseSessionEntity>,
        nutritionMeals: List<NutritionMealEntity>,
        goal: GoalEntity?,
        weeklySnapshot: WeeklySnapshot,
        sources: List<HealthSourceStatusEntity> = emptyList(),
        dataRevision: String? = null,
        lastSyncSuccessEpochMillis: Long? = null,
        lastSyncOutcome: String? = null,
    ): WeeklyAiExportPayload {
        val zoneId = exportedAt.zone
        val today = exportedAt.toLocalDate()
        val defaultRange = weeklyDateRange(today, includeToday = false)
        val from = runCatching { LocalDate.parse(weeklySnapshot.weekStart) }.getOrNull() ?: defaultRange.first
        val to = runCatching { LocalDate.parse(weeklySnapshot.weekEnd) }.getOrNull() ?: defaultRange.second
        val (startMillis, endMillis) = epochRange(from, to, zoneId)
        val fromStr = from.toString()
        val toStr = to.toString()

        val dailyByDate = daily
            .filter { it.date in fromStr..toStr }
            .associateBy { it.date }
        val bodyByDate = body
            .filter { it.date in fromStr..toStr }
            .associateBy { it.date }
        val allDates = (dailyByDate.keys + bodyByDate.keys).distinct().sorted()

        val dailyRecords = allDates.map { dateStr ->
            buildDailyRecordExport(
                dateStr = dateStr,
                today = today,
                zoneId = zoneId,
                dayItem = dailyByDate[dateStr],
                bodyItem = bodyByDate[dateStr],
                allBody = body,
                goal = goal,
            )
        }

        val exportedSessions = mapExerciseSessionsInRange(
            exerciseSessions = exerciseSessions,
            startMillis = startMillis,
            endMillis = endMillis,
            today = today,
            zoneId = zoneId,
        )

        val emptyPlaceholderDates = daily
            .filter { it.nutritionRecordStatus == "empty_placeholder" }
            .map { it.date }
            .toSet()

        val exportedMeals = mapNutritionMealsInRange(
            nutritionMeals = nutritionMeals,
            fromStr = fromStr,
            toStr = toStr,
            startMillis = startMillis,
            endMillis = endMillis,
            emptyPlaceholderDates = emptyPlaceholderDates,
            today = today,
            zoneId = zoneId,
        )

        val todayStr = today.toString()
        val todayPartial = if (to < today) {
            val todayDayItem = daily.find { it.date == todayStr }
            val todayBodyItem = body.find { it.date == todayStr }
            val (todayStartMillis, todayEndMillis) = epochRange(today, today, zoneId)
            val todaySessions = mapExerciseSessionsInRange(
                exerciseSessions = exerciseSessions,
                startMillis = todayStartMillis,
                endMillis = todayEndMillis,
                today = today,
                zoneId = zoneId,
            )
            val todayMeals = mapNutritionMealsInRange(
                nutritionMeals = nutritionMeals,
                fromStr = todayStr,
                toStr = todayStr,
                startMillis = todayStartMillis,
                endMillis = todayEndMillis,
                emptyPlaceholderDates = emptyPlaceholderDates,
                today = today,
                zoneId = zoneId,
            )
            if (todayDayItem != null || todayBodyItem != null || todaySessions.isNotEmpty() || todayMeals.isNotEmpty()) {
                WeeklyTodayPartialExport(
                    date = todayStr,
                    dayOfWeek = today.format(DAY_OF_WEEK_FORMATTER),
                    asOfLocal = exportedAt.format(LOCAL_TIME_FORMATTER),
                    isPartialDay = true,
                    dailyRecord = if (todayDayItem != null || todayBodyItem != null) {
                        buildDailyRecordExport(
                            dateStr = todayStr,
                            today = today,
                            zoneId = zoneId,
                            dayItem = todayDayItem,
                            bodyItem = todayBodyItem,
                            allBody = body,
                            goal = goal,
                        )
                    } else {
                        null
                    },
                    exerciseSessions = todaySessions,
                    nutritionMeals = todayMeals,
                )
            } else {
                null
            }
        } else {
            null
        }

        val dataQuality = buildDataQualityExport(
            from = from,
            to = to,
            weekDaily = dailyByDate.values.toList(),
            allDaily = daily,
            weekDailyRecords = dailyRecords,
            weekSessions = exportedSessions,
            weeklySnapshot = weeklySnapshot,
            sources = sources,
            zoneId = zoneId,
        )

        val goalStatus = goal?.let { g ->
            val expired = g.deadline?.let { d ->
                runCatching { LocalDate.parse(d).isBefore(today) }.getOrDefault(false)
            } ?: false
            WeeklyGoalStatusExport(
                deadline = g.deadline,
                deadlineExpired = expired,
                fatMassToGoalKg = weeklySnapshot.fatMassToGoalKg,
                requiredFatLossKgPerWeek = weeklySnapshot.requiredFatLossKgPerWeek,
                monthlyWeightLossTargetKg = g.monthlyWeightLossKg,
                targetLossRatePercentPerWeek = weeklySnapshot.targetLossRatePercentPerWeek,
                evaluationRule = if (expired) {
                    "目標期限（deadline）を過ぎているため、期限逆算のrequiredFatLossKgPerWeekは算出していません。" +
                        "monthlyWeightLossKg（targetLossRatePercentPerWeek）および28日ロバスト傾向（0.3〜0.7%/週）を基準に評価してください。"
                } else {
                    "目標脂肪量までの差分（fatMassToGoalKg）と月間減量目標（monthlyWeightLossKg / targetLossRatePercentPerWeek）を参照してください。"
                },
            )
        }

        return WeeklyAiExportPayload(
            dataRevision = dataRevision?.takeIf { it.isNotBlank() },
            lastSyncSuccessLocal = lastSyncSuccessEpochMillis?.let { formatLocalDateTime(it, zoneId) },
            lastSyncOutcome = lastSyncOutcome?.takeIf { it.isNotBlank() },
            exportedAtIso = exportedAt.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            exportedAtEpochMillis = exportedAt.toInstant().toEpochMilli(),
            timeZone = zoneId.id,
            todayDate = todayStr,
            weekStart = fromStr,
            weekEnd = toStr,
            periodType = if (to < today) "completed_7_days_excluding_today" else "7_days_including_today",
            daysIncluded = WEEKLY_EXPORT_DAYS.toInt(),
            trendWindowDays = EXPORT_DAYS.toInt(),
            goal = goal,
            goalStatus = goalStatus,
            dataQuality = dataQuality,
            weeklySummary = weeklySnapshot,
            sevenDayDetails = WeeklyMeasuredDetailsExport(
                dailyRecords = dailyRecords,
                exerciseSessions = exportedSessions,
                nutritionMeals = exportedMeals,
            ),
            todayPartial = todayPartial,
        )
    }

    private fun buildDailyRecordExport(
        dateStr: String,
        today: LocalDate,
        zoneId: ZoneId,
        dayItem: DailyHealthSummaryEntity?,
        bodyItem: BodyCompositionEntity?,
        allBody: List<BodyCompositionEntity>,
        goal: GoalEntity?,
    ): WeeklyDailyRecordExport {
        val parsedDate = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: today
        val activeEstimate = dayItem?.let {
            EnergyModel.dailyActive(it, allBody, goal?.heightCm, goal?.sex)
        }
        val balanceRange = dayItem?.let {
            EnergyModel.dailyBalance(it, activeEstimate)
        }
        val mvpaEquivalent = dayItem?.let {
            if (it.moderateIntensityMinutes != null || it.vigorousIntensityMinutes != null) {
                (it.moderateIntensityMinutes ?: 0L) + 2L * (it.vigorousIntensityMinutes ?: 0L)
            } else {
                null
            }
        }
        val sleepOpportunityMinutes = dayItem?.sleepSessionMinutes ?: dayItem?.sleepMinutes
        val exerciseMinutes = dayItem?.exerciseMinutes ?: 0L
        val strengthMinutes = dayItem?.strengthMinutes ?: 0L
        val cardioMinutes = dayItem?.cardioMinutes ?: 0L
        val otherExerciseMinutes = (exerciseMinutes - strengthMinutes - cardioMinutes).coerceAtLeast(0L)
        val sleepHeartRateStatus = when {
            dayItem?.sleepMinutes == null -> "no_sleep_record"
            dayItem.sleepHeartRateAverageBpm == null -> "no_samples_in_main_sleep"
            else -> "observed"
        }
        val heartRateInterpretation = dayItem?.heartRateAverageBpm?.let {
            if (dayItem.heartRateOutsideExerciseSamples == 0) {
                "workout_only_not_resting"
            } else {
                "observed_samples_not_proven_resting"
            }
        }
        val nutritionStatus = dayItem?.nutritionRecordStatus ?: "no_records"
        val verifiedMealCount = dayItem?.mealCount?.takeIf {
            it > 0 && nutritionStatus == "meal_records_unverified"
        }
        val originsList = buildList {
            dayItem?.dataOrigins
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.let { addAll(it) }
            bodyItem?.dataOrigin?.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
        }.distinct().sorted()

        return WeeklyDailyRecordExport(
            date = dateStr,
            dayOfWeek = parsedDate.format(DAY_OF_WEEK_FORMATTER),
            relativeDay = ChatHistoryPolicy.relativeDayLabel(parsedDate, today),
            isPartialDay = parsedDate == today,
            weightKg = bodyItem?.weightKg,
            bodyFatPercent = bodyItem?.bodyFatPercent,
            fatMassKg = bodyItem?.fatMassKg,
            leanBodyMassKg = bodyItem?.leanBodyMassKg,
            leanMassSource = bodyItem?.leanMassSource,
            bodyMeasuredAtLocal = bodyItem?.measurementEpochMillis?.let {
                formatLocalTime(it, zoneId)
            },
            steps = dayItem?.steps,
            distanceMeters = dayItem?.distanceMeters,
            cyclingMinutes = dayItem?.cyclingMinutes ?: 0L,
            hikingMinutes = dayItem?.hikingMinutes ?: 0L,
            hiitMinutes = dayItem?.hiitMinutes ?: 0L,
            moderateIntensityMinutes = dayItem?.moderateIntensityMinutes,
            vigorousIntensityMinutes = dayItem?.vigorousIntensityMinutes,
            moderateEquivalentMinutes = mvpaEquivalent,
            exerciseSessionCount = dayItem?.exerciseSessionCount ?: 0,
            exerciseMinutes = exerciseMinutes,
            morningRoutineMinutes = dayItem?.morningRoutineMinutes ?: 0L,
            strengthMinutes = strengthMinutes,
            cardioMinutes = cardioMinutes,
            otherExerciseMinutes = otherExerciseMinutes,
            sleepMinutes = dayItem?.sleepMinutes,
            sleepSessionMinutes = dayItem?.sleepSessionMinutes,
            sleepDurationSource = dayItem?.sleepDurationSource ?: "no_record",
            meetsSixHourOpportunity = sleepOpportunityMinutes?.let {
                it >= SLEEP_BASELINE_MINUTES
            },
            sleepStartLocal = dayItem?.sleepStartEpochMillis?.let {
                formatLocalDateTime(it, zoneId)
            },
            sleepEndLocal = dayItem?.sleepEndEpochMillis?.let {
                formatLocalDateTime(it, zoneId)
            },
            sleepHeartRateAverageBpm = dayItem?.sleepHeartRateAverageBpm,
            sleepHeartRateStatus = sleepHeartRateStatus,
            heartRateAverageBpm = dayItem?.heartRateAverageBpm,
            heartRateMinimumBpm = dayItem?.heartRateMinimumBpm,
            heartRateMaximumBpm = dayItem?.heartRateMaximumBpm,
            heartRateMeasurementCount = dayItem?.heartRateMeasurementCount,
            heartRateCoverageMinutes = dayItem?.heartRateCoverageMinutes,
            heartRateOutsideExerciseSamples = dayItem?.heartRateOutsideExerciseSamples,
            heartRateInterpretation = heartRateInterpretation,
            basalCaloriesKcal = dayItem?.basalCaloriesKcal,
            activeCaloriesDeviceKcal = dayItem?.activeCaloriesKcal,
            referenceStepModelActiveKcal = activeEstimate?.let {
                EnergyModel.roundForDisplay(it.stepModelKcal)
            },
            referenceEvaluatedActiveKcal = activeEstimate?.let {
                EnergyModel.roundForDisplay(it.evaluatedKcal)
            },
            referenceActiveRangeLowKcal = activeEstimate?.let {
                EnergyModel.roundForDisplay(it.range.lowKcal)
            },
            referenceActiveRangeHighKcal = activeEstimate?.let {
                EnergyModel.roundForDisplay(it.range.highKcal)
            },
            isLargeActiveDiscrepancy = activeEstimate?.isLargeDiscrepancy,
            intakeCaloriesKcal = dayItem?.intakeCaloriesKcal,
            nutritionComplete = dayItem?.nutritionComplete ?: false,
            nutritionRecordStatus = nutritionStatus,
            mealCount = verifiedMealCount,
            proteinGrams = dayItem?.proteinGrams,
            totalFatGrams = dayItem?.totalFatGrams,
            carbohydrateGrams = dayItem?.carbohydrateGrams,
            proteinEnergyPercent = NutritionMacros.proteinEnergyPercent(
                dayItem?.proteinGrams,
                dayItem?.intakeCaloriesKcal,
            ),
            fatEnergyPercent = NutritionMacros.fatEnergyPercent(
                dayItem?.totalFatGrams,
                dayItem?.intakeCaloriesKcal,
            ),
            carbohydrateEnergyPercent = NutritionMacros.carbohydrateEnergyPercent(
                dayItem?.carbohydrateGrams,
                dayItem?.intakeCaloriesKcal,
            ),
            estimatedEnergyBalanceDeviceKcal = dayItem?.estimatedEnergyBalanceKcal,
            referenceEnergyBalanceLowKcal = balanceRange?.let {
                EnergyModel.roundForDisplay(it.lowKcal)
            },
            referenceEnergyBalanceHighKcal = balanceRange?.let {
                EnergyModel.roundForDisplay(it.highKcal)
            },
            origins = originsList,
        )
    }

    private fun mapExerciseSessionsInRange(
        exerciseSessions: List<ExerciseSessionEntity>,
        startMillis: Long,
        endMillis: Long,
        today: LocalDate,
        zoneId: ZoneId,
    ): List<WeeklyExerciseSessionExport> {
        val rangeEndExclusive = endMillis + 1L
        return exerciseSessions
            .filter { it.endEpochMillis > startMillis && it.startEpochMillis <= endMillis }
            .sortedBy { it.startEpochMillis }
            .map { session ->
                val sessionDate = Instant.ofEpochMilli(session.startEpochMillis)
                    .atZone(zoneId)
                    .toLocalDate()
                val clippedMinutes = (
                    (minOf(session.endEpochMillis, rangeEndExclusive) - maxOf(session.startEpochMillis, startMillis))
                        .coerceAtLeast(0L) / 60_000L
                    )
                WeeklyExerciseSessionExport(
                    date = sessionDate.toString(),
                    dayOfWeek = sessionDate.format(DAY_OF_WEEK_FORMATTER),
                    relativeDay = ChatHistoryPolicy.relativeDayLabel(sessionDate, today),
                    startTimeLocal = formatLocalTime(session.startEpochMillis, zoneId),
                    endTimeLocal = formatLocalTime(session.endEpochMillis, zoneId),
                    exerciseType = session.exerciseType,
                    exerciseLabel = session.exerciseLabel,
                    category = session.category,
                    durationMinutes = session.durationMinutes,
                    minutesInRequestedPeriod = clippedMinutes,
                    dataOrigin = session.dataOrigin,
                )
            }
    }

    private fun mapNutritionMealsInRange(
        nutritionMeals: List<NutritionMealEntity>,
        fromStr: String,
        toStr: String,
        startMillis: Long,
        endMillis: Long,
        emptyPlaceholderDates: Set<String>,
        today: LocalDate,
        zoneId: ZoneId,
    ): List<WeeklyNutritionMealExport> {
        return nutritionMeals
            .filter { meal ->
                val inPeriod = meal.date in fromStr..toStr ||
                    (meal.date.isBlank() && meal.startEpochMillis in startMillis..endMillis)
                val hasObservedValue = meal.intakeCaloriesKcal != null ||
                    meal.proteinGrams != null ||
                    meal.totalFatGrams != null ||
                    meal.carbohydrateGrams != null
                inPeriod && meal.date !in emptyPlaceholderDates && hasObservedValue
            }
            .sortedBy { it.startEpochMillis }
            .map { meal ->
                val mealDate = runCatching { LocalDate.parse(meal.date) }.getOrNull()
                    ?: Instant.ofEpochMilli(meal.startEpochMillis).atZone(zoneId).toLocalDate()
                val typed = NutritionDetailPolicy.hasMealType(meal.isDailyRecord, meal.mealType)
                val detailStatus = when {
                    meal.isDailyRecord -> "daily_summary"
                    typed -> "typed_meal_record"
                    else -> "unknown"
                }
                WeeklyNutritionMealExport(
                    date = meal.date,
                    dayOfWeek = mealDate.format(DAY_OF_WEEK_FORMATTER),
                    relativeDay = ChatHistoryPolicy.relativeDayLabel(mealDate, today),
                    startTimeLocal = if (typed) formatLocalTime(meal.startEpochMillis, zoneId) else null,
                    endTimeLocal = if (typed) formatLocalTime(meal.endEpochMillis, zoneId) else null,
                    mealLabel = meal.mealLabel,
                    mealType = if (typed) meal.mealType else null,
                    isDailyRecord = meal.isDailyRecord,
                    detailStatus = detailStatus,
                    intakeCaloriesKcal = meal.intakeCaloriesKcal,
                    proteinGrams = meal.proteinGrams,
                    totalFatGrams = meal.totalFatGrams,
                    carbohydrateGrams = meal.carbohydrateGrams,
                    proteinEnergyPercent = NutritionMacros.proteinEnergyPercent(
                        meal.proteinGrams,
                        meal.intakeCaloriesKcal,
                    ),
                    fatEnergyPercent = NutritionMacros.fatEnergyPercent(
                        meal.totalFatGrams,
                        meal.intakeCaloriesKcal,
                    ),
                    carbohydrateEnergyPercent = NutritionMacros.carbohydrateEnergyPercent(
                        meal.carbohydrateGrams,
                        meal.intakeCaloriesKcal,
                    ),
                    recordCount = meal.recordCount,
                    dataOrigin = meal.dataOrigin,
                )
            }
    }

    private fun buildDataQualityExport(
        from: LocalDate,
        to: LocalDate,
        weekDaily: List<DailyHealthSummaryEntity>,
        allDaily: List<DailyHealthSummaryEntity>,
        weekDailyRecords: List<WeeklyDailyRecordExport>,
        weekSessions: List<WeeklyExerciseSessionExport>,
        weeklySnapshot: WeeklySnapshot,
        sources: List<HealthSourceStatusEntity>,
        zoneId: ZoneId,
    ): WeeklyDataQualityExport {
        // 1. Body composition quality
        val bodyDays = weeklySnapshot.bodyMeasurementDays
        val trendDays = weeklySnapshot.trendMeasurementDays
        val usableWeeklyBody = bodyDays >= 3
        val usableTrendBody = weeklySnapshot.weightTrendKgPerWeek != null
        val bodyStatusNote = when {
            usableWeeklyBody && usableTrendBody ->
                "7日中央値および28日ロバスト傾向ともに十分な測定日数があります。"
            usableWeeklyBody ->
                "今週の7日中央値は有効ですが、28日ロバスト傾向には測定日数または期間幅（8回・14日以上）が不足しています。"
            else ->
                "今週の体組成測定が3日未満のため、週次変化の断定は控えてください。"
        }

        // 2. Activity & energy quality
        val stepsDays = weekDaily.count { it.steps != null }
        val activeCalDays = weekDaily.count { it.activeCaloriesKcal != null }
        val basalCalDays = weekDaily.count { it.basalCaloriesKcal != null }
        val intensityDays = weekDaily.count {
            it.moderateIntensityMinutes != null || it.vigorousIntensityMinutes != null
        }
        val largeDiscrepancyDays = weekDailyRecords.count { it.isLargeActiveDiscrepancy == true }
        val firstOriginsJson = weekDaily.firstNotNullOfOrNull { day ->
            day.metricOriginsJson.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            }
        }
        val wearablePolicy = firstOriginsJson?.get("wearableSourcePolicy")?.jsonPrimitive?.contentOrNull
            ?: "xiaomi_only_v1"
        val selectedWearable = firstOriginsJson?.get("selectedWearableOrigin")?.jsonPrimitive?.contentOrNull
            ?: "com.xiaomi.wearable"

        // 3. Exercise quality
        val totalExerciseMinutes = weekDaily.sumOf { it.exerciseMinutes }
        val confirmedStrengthMinutes = weekDaily.sumOf { it.strengthMinutes }
        val cardioMinutes = weekDaily.sumOf { it.cardioMinutes }
        val morningRoutineMinutes = weekDaily.sumOf { it.morningRoutineMinutes }
        val morningRoutineDays = weekDaily.count { it.morningRoutineMinutes > 0 }
        val cyclingMinutes = weekDaily.sumOf { it.cyclingMinutes }
        val hikingMinutes = weekDaily.sumOf { it.hikingMinutes }
        val hiitMinutes = weekDaily.sumOf { it.hiitMinutes }
        val unverifiedOtherMinutes = weekDaily.sumOf {
            (it.exerciseMinutes - it.strengthMinutes - it.cardioMinutes).coerceAtLeast(0L)
        }
        val unverifiedOtherSessions = weekSessions.count { it.category == "other" }
        val totalSessions = if (weekSessions.isNotEmpty()) {
            weekSessions.size
        } else {
            weeklySnapshot.exerciseSessions
        }
        val strengthStatus = when {
            confirmedStrengthMinutes > 0L -> "confirmed_present"
            unverifiedOtherMinutes > 0L || unverifiedOtherSessions > 0 -> "unverified_other_workouts_present"
            else -> "confirmed_zero"
        }
        val exerciseStatusNote = when (strengthStatus) {
            "confirmed_present" ->
                "筋力トレーニング記録が確認されています。心拍ゾーン等の実測強度データは未取得です。"
            "unverified_other_workouts_present" ->
                "strengthMinutesは0分ですが、内容未確認の一般運動（other）が${unverifiedOtherSessions}件・${unverifiedOtherMinutes}分あります。" +
                    "筋トレ未実施の証明ではないため、「筋トレ未実施」と断定しないでください。また実測運動強度は未取得です。"
            else ->
                "今週は筋力トレーニングおよび未確認の一般運動の記録はありません。"
        }

        // 4. Heart rate quality
        val hrMeasuredDays = weekDaily.filter { it.heartRateAverageBpm != null }
        val hrMeasurementDaysCount = hrMeasuredDays.size
        val workoutOnlyDays = hrMeasuredDays.count { it.heartRateOutsideExerciseSamples == 0 }
        val outsideExerciseDays = hrMeasuredDays.count { (it.heartRateOutsideExerciseSamples ?: 0) > 0 }
        val totalOutsideSamples = hrMeasuredDays.sumOf { it.heartRateOutsideExerciseSamples ?: 0 }
        val coverageValues = hrMeasuredDays.mapNotNull { it.heartRateCoverageMinutes }
        val avgCoverageMinutes = if (coverageValues.isNotEmpty()) {
            coverageValues.average().roundToInt()
        } else {
            null
        }
        val allDaysWorkoutOnly = hrMeasurementDaysCount > 0 && workoutOnlyDays == hrMeasurementDaysCount
        val usableForRestingOrDaily = outsideExerciseDays >= 3 && (avgCoverageMinutes ?: 0) >= 180
        val hrInterpretation = when {
            hrMeasurementDaysCount == 0 -> "no_heart_rate_records"
            allDaysWorkoutOnly -> "workout_only_do_not_use_as_resting_or_daily_hr"
            !usableForRestingOrDaily -> "partial_coverage_not_proven_resting"
            else -> "daily_samples_observed"
        }
        val hrStatusNote = when (hrInterpretation) {
            "workout_only_do_not_use_as_resting_or_daily_hr" ->
                "今週の心拍記録（${hrMeasurementDaysCount}日）はすべて運動外の心拍サンプルが0件（運動中のみ計測）です。" +
                    "週間平均心拍や日別平均心拍を日常心拍・安静時心拍・回復状態の評価に使用しないでください。"
            "partial_coverage_not_proven_resting" ->
                "運動外の心拍サンプルが一部ありますがカバレッジが限定的なため、安静時心拍や回復指標として断定しないでください。"
            "no_heart_rate_records" ->
                "心拍データがありません。"
            else ->
                "運動外の心拍サンプルが観測されていますが、厳密な安静時心拍ではなく日次観測平均として参照してください。"
        }

        // 5. Sleep & recovery quality
        val sleepMeasured = weekDaily.filter { it.sleepMinutes != null }
        val sleepDaysCount = sleepMeasured.size
        val stageMeasuredDays = sleepMeasured.count {
            it.sleepDurationSource in setOf("sleep_stages", "sleep_stages_partial")
        }
        val sessionFallbackDays = sleepMeasured.count {
            it.sleepDurationSource in setOf("session_fallback", "legacy_session")
        }
        val mainSleepHrDays = weekDaily.count { it.sleepHeartRateAverageBpm != null }
        val baselineStartStr = from.minusDays(21).toString()
        val baselineEndStr = from.minusDays(1).toString()
        val baseline21dSleepHrDays = allDaily.count {
            it.date in baselineStartStr..baselineEndStr && it.sleepHeartRateAverageBpm != null
        }
        val usableSleepDuration = sleepDaysCount >= 5
        val usableRecoveryDelta = weeklySnapshot.sleepHeartRateBaselineDeltaBpm != null
        val recoveryStatus = when {
            usableRecoveryDelta -> "duration_and_sleep_hr_available"
            sleepDaysCount == 0 -> "insufficient_sleep_records"
            mainSleepHrDays == 0 -> "duration_only_no_sleep_hr_samples"
            else -> "duration_only_insufficient_sleep_hr_baseline"
        }
        val sleepStatusNote = when (recoveryStatus) {
            "duration_and_sleep_hr_available" ->
                "睡眠時間および主睡眠中の心拍ベースライン差（sleepHeartRateBaselineDeltaBpm）の両方を参照できます。"
            "duration_only_no_sleep_hr_samples" ->
                "睡眠時間・就寝起床時刻の分析は可能ですが、主睡眠中の心拍サンプルが0日のため生理的な回復状態の断定はできません。"
            "duration_only_insufficient_sleep_hr_baseline" ->
                "睡眠時間の分析は可能ですが、睡眠中心拍の観測日数（今週${mainSleepHrDays}日/3日以上、直前21日${baseline21dSleepHrDays}日/7日以上が必要）が不足しているためベースライン比較はできません。"
            else ->
                "睡眠記録が不足しています。"
        }

        // 6. Nutrition quality
        val recordedNutritionDays = weekDaily.count { it.intakeCaloriesKcal != null }
        val confirmedCompleteDays = weekDaily.count { it.intakeCaloriesKcal != null && it.nutritionComplete }
        val emptyPlaceholderDays = weekDaily.count { it.nutritionRecordStatus == "empty_placeholder" }
        val dailySummaryDays = weekDaily.count { it.nutritionRecordStatus == "daily_record_unverified" }
        val typedMealDays = weekDaily.count { it.nutritionRecordStatus == "meal_records_unverified" }
        val usableNutrition = confirmedCompleteDays >= 3
        val nutritionStatusNote = when {
            usableNutrition ->
                "全食記録済み確認日（confirmedCompleteDays=${confirmedCompleteDays}日）に基づき、摂取カロリー・PFC・参考収支を評価できます。"
            recordedNutritionDays == 0 && emptyPlaceholderDays > 0 ->
                "栄養値が空のプレースホルダ記録（${emptyPlaceholderDays}日）のみで、有効な摂取カロリー・PFCデータはありません。"
            recordedNutritionDays == 0 ->
                "今週の栄養記録（摂取カロリー・PFC）は未取得です。"
            confirmedCompleteDays == 0 ->
                "栄養記録は${recordedNutritionDays}日ありますが全食記録済み（nutritionComplete）と確認されていないため、収支や摂取不足の断定は保留してください。"
            else ->
                "全食確認済みの栄養記録が${confirmedCompleteDays}日と少ないため、週次平均の解釈には注意してください。"
        }

        val exportedSources = sources.map { src ->
            WeeklySourceStatusExport(
                recordType = src.recordType,
                status = src.status,
                recordCount = src.recordCount,
                origins = src.origins,
                latestRecordLocal = src.latestRecordEpochMillis?.let { formatLocalDateTime(it, zoneId) },
            )
        }

        return WeeklyDataQualityExport(
            bodyComposition = WeeklyBodyQualityExport(
                weeklyMeasurementDays = bodyDays,
                trend28dMeasurementDays = trendDays,
                usableForWeeklyMedian = usableWeeklyBody,
                usableFor28dRobustTrend = usableTrendBody,
                measurementTimeConsistencyPercent = weeklySnapshot.measurementTimeConsistencyPercent,
                statusNote = bodyStatusNote,
            ),
            activityAndEnergy = WeeklyActivityQualityExport(
                stepsMeasurementDays = stepsDays,
                activeCaloriesMeasurementDays = activeCalDays,
                basalCaloriesMeasurementDays = basalCalDays,
                activityIntensityMeasurementDays = intensityDays,
                largeActiveDiscrepancyDays = largeDiscrepancyDays,
                usableForStepTrend = stepsDays >= 5,
                usableForActivityIntensity = intensityDays > 0,
                wearableSourcePolicy = wearablePolicy,
                selectedWearableOrigin = selectedWearable,
            ),
            exercise = WeeklyExerciseQualityExport(
                totalSessions = totalSessions,
                totalExerciseMinutes = totalExerciseMinutes,
                morningRoutineDays = morningRoutineDays,
                morningRoutineMinutes = morningRoutineMinutes,
                confirmedStrengthMinutes = confirmedStrengthMinutes,
                cardioMinutes = cardioMinutes,
                cyclingMinutes = cyclingMinutes,
                hikingMinutes = hikingMinutes,
                hiitMinutes = hiitMinutes,
                unverifiedOtherSessions = unverifiedOtherSessions,
                unverifiedOtherMinutes = unverifiedOtherMinutes,
                strengthAssessmentStatus = strengthStatus,
                heartRateIntensityAvailable = false,
                statusNote = exerciseStatusNote,
            ),
            heartRate = WeeklyHeartRateQualityExport(
                measurementDays = hrMeasurementDaysCount,
                workoutOnlyDays = workoutOnlyDays,
                outsideExerciseObservedDays = outsideExerciseDays,
                totalOutsideExerciseSamples = totalOutsideSamples,
                averageCoverageMinutes = avgCoverageMinutes,
                allDaysWorkoutOnly = allDaysWorkoutOnly,
                usableForDailyOrRestingTrend = usableForRestingOrDaily,
                interpretation = hrInterpretation,
                statusNote = hrStatusNote,
            ),
            sleepAndRecovery = WeeklySleepQualityExport(
                sleepMeasurementDays = sleepDaysCount,
                stageMeasuredDays = stageMeasuredDays,
                sessionFallbackDays = sessionFallbackDays,
                mainSleepHeartRateDays = mainSleepHrDays,
                baseline21dSleepHeartRateDays = baseline21dSleepHrDays,
                usableForSleepDuration = usableSleepDuration,
                usableForRecoveryHeartRateDelta = usableRecoveryDelta,
                recoveryAssessmentStatus = recoveryStatus,
                statusNote = sleepStatusNote,
            ),
            nutrition = WeeklyNutritionQualityExport(
                recordedDays = recordedNutritionDays,
                confirmedCompleteDays = confirmedCompleteDays,
                emptyPlaceholderDays = emptyPlaceholderDays,
                dailySummaryFormatDays = dailySummaryDays,
                typedMealRecordDays = typedMealDays,
                mealTimingAndCountAvailable = typedMealDays > 0,
                usableForDietaryAndBalanceAnalysis = usableNutrition,
                statusNote = nutritionStatusNote,
            ),
            sources = exportedSources,
        )
    }

    fun encodeToJson(payload: HealthDataExportPayload): String =
        prettyJson.encodeToString(payload)

    fun encodeWeeklyAiToJson(payload: WeeklyAiExportPayload): String =
        prettyJson.encodeToString(payload)

    private fun formatIso(epochMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun formatLocalTime(epochMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .format(LOCAL_TIME_FORMATTER)

    private fun formatLocalDateTime(epochMillis: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .format(LOCAL_DATE_TIME_FORMATTER)
}
