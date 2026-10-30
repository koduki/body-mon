package com.master.healthcoach.domain

import com.master.healthcoach.BuildConfig
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.ChatMessageEntity
import com.master.healthcoach.data.db.ConversationMemoryEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.ExerciseSessionEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.db.NutritionMealEntity
import com.master.healthcoach.data.db.estimatedEnergyBalanceKcal
import com.master.healthcoach.data.llm.ChatHistoryPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val schemaVersion: Int = 1,
    val exportType: String = "weekly_ai_analysis",
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val exportedAtIso: String,
    val exportedAtEpochMillis: Long,
    val timeZone: String,
    val weekStart: String,
    val weekEnd: String,
    val daysIncluded: Int = HealthDataExporter.WEEKLY_EXPORT_DAYS.toInt(),
    val trendWindowDays: Int = HealthDataExporter.EXPORT_DAYS.toInt(),
    val analysisContext: WeeklyAiAnalysisContext = WeeklyAiAnalysisContext(),
    val goal: GoalEntity? = null,
    val weeklySummary: WeeklySnapshot,
    val sevenDayDetails: WeeklyMeasuredDetailsExport,
)

@Serializable
data class WeeklyAiAnalysisContext(
    val dietPolicy: String =
        "低脂質ダイエット方針。脂質エネルギー比15〜25%を運用目安とし、30%超は脂質の出どころを確認。" +
            "たんぱく質は減量中の筋力維持のため体重1kgあたり1.6g/日以上を目安とする。" +
            "食事記録がある日のみを観測値とし、欠測日を0kcal扱いしない。" +
            "食事回数はNutritionRecordのstart/endが近いレコードを1食にまとめて数える。",
    val bodyCompositionRules: String =
        "体重を速く落とすことではなく、筋力と回復を守りながら脂肪を減らすことを優先し、" +
            "28日ロバスト傾向（Theil-Sen推定）で体重の0.3〜0.7%/週減を運用目安とする。" +
            "BIA由来の体脂肪率・脂肪量・除脂肪量は水分等で日々変動するため単日値で断定せず、" +
            "7日中央値や28日傾向で判断する。除脂肪量は体重−脂肪量の計算値であり、骨格筋量そのものではない。",
    val exerciseAndRoutineRules: String =
        "Health Connectの「その他のワークアウト（category=morning_routine）」は本人の朝の5分ルーティン" +
            "（軽い筋トレ＋有酸素）として扱い、その実施日数を週間習慣KPIとする。" +
            "筋力維持そのものは朝トレ日数では証明せず、月1回のパーソナルでの重量・回数で確認する。",
    val energyAndExpenditureRules: String =
        EnergyModel.INTERPRETATION + " modelVersion=${EnergyModel.VERSION}",
    val sleepAndRecoveryRules: String =
        "睡眠6時間（360分）は本人の最低運用目標であり、回復十分の判定ではない（達成日はセッション枠で数える）。" +
            "sleepMinutesは睡眠ステージ合計（sleep_stages / sleep_stages_partial）、ステージ欠測時は" +
            "セッション枠で代用（session_fallback / legacy_session）して区別する。" +
            "sleepHeartRateAverageBpmは主睡眠区間の平均心拍で、直前21日平均との差（sleepHeartRateBaselineDeltaBpm）を回復シグナルとして参照する。",
)

@Serializable
data class WeeklyMeasuredDetailsExport(
    val dailyRecords: List<WeeklyDailyRecordExport>,
    val exerciseSessions: List<WeeklyExerciseSessionExport>,
    val nutritionMeals: List<WeeklyNutritionMealExport>,
)

@Serializable
data class WeeklyDailyRecordExport(
    val date: String,
    val dayOfWeek: String,
    val relativeDay: String,
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
    val fatMassKg: Double? = null,
    val leanBodyMassKg: Double? = null,
    val leanMassSource: String? = null,
    val bodyMeasuredAtLocal: String? = null,
    val bodyMeasurementEpochMillis: Long? = null,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val cyclingMinutes: Long = 0,
    val moderateIntensityMinutes: Long? = null,
    val vigorousIntensityMinutes: Long? = null,
    val moderateEquivalentMinutes: Long? = null,
    val exerciseSessionCount: Int = 0,
    val exerciseMinutes: Long = 0,
    val morningRoutineMinutes: Long = 0,
    val strengthMinutes: Long = 0,
    val cardioMinutes: Long = 0,
    val sleepMinutes: Long? = null,
    val sleepSessionMinutes: Long? = null,
    val sleepDurationSource: String = "no_record",
    val meetsSixHourOpportunity: Boolean? = null,
    val sleepStartLocal: String? = null,
    val sleepEndLocal: String? = null,
    val sleepStartEpochMillis: Long? = null,
    val sleepEndEpochMillis: Long? = null,
    val sleepHeartRateAverageBpm: Long? = null,
    val heartRateAverageBpm: Long? = null,
    val heartRateMinimumBpm: Long? = null,
    val heartRateMaximumBpm: Long? = null,
    val heartRateMeasurementCount: Long? = null,
    val basalCaloriesKcal: Double? = null,
    val activeCaloriesDeviceKcal: Double? = null,
    val referenceStepModelActiveKcal: Double? = null,
    val referenceEvaluatedActiveKcal: Double? = null,
    val referenceActiveRangeLowKcal: Double? = null,
    val referenceActiveRangeHighKcal: Double? = null,
    val isLargeActiveDiscrepancy: Boolean? = null,
    val intakeCaloriesKcal: Double? = null,
    val mealCount: Int = 0,
    val proteinGrams: Double? = null,
    val totalFatGrams: Double? = null,
    val carbohydrateGrams: Double? = null,
    val proteinEnergyPercent: Double? = null,
    val fatEnergyPercent: Double? = null,
    val carbohydrateEnergyPercent: Double? = null,
    val estimatedEnergyBalanceDeviceKcal: Double? = null,
    val referenceEnergyBalanceLowKcal: Double? = null,
    val referenceEnergyBalanceHighKcal: Double? = null,
    val dataOrigins: String = "",
)

@Serializable
data class WeeklyExerciseSessionExport(
    val recordId: String,
    val date: String,
    val dayOfWeek: String,
    val relativeDay: String,
    val startTimeLocal: String,
    val endTimeLocal: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val exerciseType: Int,
    val exerciseLabel: String,
    val category: String,
    val durationMinutes: Long,
    val dataOrigin: String,
)

@Serializable
data class WeeklyNutritionMealExport(
    val recordId: String,
    val date: String,
    val dayOfWeek: String,
    val relativeDay: String,
    val startTimeLocal: String,
    val endTimeLocal: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val mealLabel: String,
    val mealType: Int,
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

    fun weeklyDateRange(today: LocalDate): Pair<LocalDate, LocalDate> =
        today.minusDays(WEEKLY_EXPORT_DAYS - 1) to today

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
    ): WeeklyAiExportPayload {
        val zoneId = exportedAt.zone
        val today = exportedAt.toLocalDate()
        val (from, to) = weeklyDateRange(today)
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
            val parsedDate = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: today
            val dayItem = dailyByDate[dateStr]
            val bodyItem = bodyByDate[dateStr]
            val activeEstimate = dayItem?.let {
                EnergyModel.dailyActive(it, body, goal?.heightCm, goal?.sex)
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

            WeeklyDailyRecordExport(
                date = dateStr,
                dayOfWeek = parsedDate.format(DAY_OF_WEEK_FORMATTER),
                relativeDay = ChatHistoryPolicy.relativeDayLabel(parsedDate, today),
                weightKg = bodyItem?.weightKg,
                bodyFatPercent = bodyItem?.bodyFatPercent,
                fatMassKg = bodyItem?.fatMassKg,
                leanBodyMassKg = bodyItem?.leanBodyMassKg,
                leanMassSource = bodyItem?.leanMassSource,
                bodyMeasuredAtLocal = bodyItem?.measurementEpochMillis?.let {
                    formatLocalTime(it, zoneId)
                },
                bodyMeasurementEpochMillis = bodyItem?.measurementEpochMillis,
                steps = dayItem?.steps,
                distanceMeters = dayItem?.distanceMeters,
                cyclingMinutes = dayItem?.cyclingMinutes ?: 0L,
                moderateIntensityMinutes = dayItem?.moderateIntensityMinutes,
                vigorousIntensityMinutes = dayItem?.vigorousIntensityMinutes,
                moderateEquivalentMinutes = mvpaEquivalent,
                exerciseSessionCount = dayItem?.exerciseSessionCount ?: 0,
                exerciseMinutes = dayItem?.exerciseMinutes ?: 0L,
                morningRoutineMinutes = dayItem?.morningRoutineMinutes ?: 0L,
                strengthMinutes = dayItem?.strengthMinutes ?: 0L,
                cardioMinutes = dayItem?.cardioMinutes ?: 0L,
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
                sleepStartEpochMillis = dayItem?.sleepStartEpochMillis,
                sleepEndEpochMillis = dayItem?.sleepEndEpochMillis,
                sleepHeartRateAverageBpm = dayItem?.sleepHeartRateAverageBpm,
                heartRateAverageBpm = dayItem?.heartRateAverageBpm,
                heartRateMinimumBpm = dayItem?.heartRateMinimumBpm,
                heartRateMaximumBpm = dayItem?.heartRateMaximumBpm,
                heartRateMeasurementCount = dayItem?.heartRateMeasurementCount,
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
                mealCount = dayItem?.mealCount ?: 0,
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
                dataOrigins = dayItem?.dataOrigins ?: bodyItem?.dataOrigin.orEmpty(),
            )
        }

        val exportedSessions = exerciseSessions
            .filter { it.startEpochMillis in startMillis..endMillis }
            .sortedBy { it.startEpochMillis }
            .map { session ->
                val sessionDate = Instant.ofEpochMilli(session.startEpochMillis)
                    .atZone(zoneId)
                    .toLocalDate()
                WeeklyExerciseSessionExport(
                    recordId = session.recordId,
                    date = sessionDate.toString(),
                    dayOfWeek = sessionDate.format(DAY_OF_WEEK_FORMATTER),
                    relativeDay = ChatHistoryPolicy.relativeDayLabel(sessionDate, today),
                    startTimeLocal = formatLocalTime(session.startEpochMillis, zoneId),
                    endTimeLocal = formatLocalTime(session.endEpochMillis, zoneId),
                    startEpochMillis = session.startEpochMillis,
                    endEpochMillis = session.endEpochMillis,
                    exerciseType = session.exerciseType,
                    exerciseLabel = session.exerciseLabel,
                    category = session.category,
                    durationMinutes = session.durationMinutes,
                    dataOrigin = session.dataOrigin,
                )
            }

        val exportedMeals = nutritionMeals
            .filter { it.date in fromStr..toStr || it.startEpochMillis in startMillis..endMillis }
            .sortedBy { it.startEpochMillis }
            .map { meal ->
                val mealDate = runCatching { LocalDate.parse(meal.date) }.getOrNull()
                    ?: Instant.ofEpochMilli(meal.startEpochMillis).atZone(zoneId).toLocalDate()
                WeeklyNutritionMealExport(
                    recordId = meal.recordId,
                    date = meal.date,
                    dayOfWeek = mealDate.format(DAY_OF_WEEK_FORMATTER),
                    relativeDay = ChatHistoryPolicy.relativeDayLabel(mealDate, today),
                    startTimeLocal = formatLocalTime(meal.startEpochMillis, zoneId),
                    endTimeLocal = formatLocalTime(meal.endEpochMillis, zoneId),
                    startEpochMillis = meal.startEpochMillis,
                    endEpochMillis = meal.endEpochMillis,
                    mealLabel = meal.mealLabel,
                    mealType = meal.mealType,
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

        return WeeklyAiExportPayload(
            exportedAtIso = exportedAt.toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            exportedAtEpochMillis = exportedAt.toInstant().toEpochMilli(),
            timeZone = zoneId.id,
            weekStart = fromStr,
            weekEnd = toStr,
            daysIncluded = WEEKLY_EXPORT_DAYS.toInt(),
            trendWindowDays = EXPORT_DAYS.toInt(),
            goal = goal,
            weeklySummary = weeklySnapshot,
            sevenDayDetails = WeeklyMeasuredDetailsExport(
                dailyRecords = dailyRecords,
                exerciseSessions = exportedSessions,
                nutritionMeals = exportedMeals,
            ),
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
