package com.master.healthcoach.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthCoachDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(items: List<DailyHealthSummaryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBody(items: List<BodyCompositionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(items: List<HealthSourceStatusEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExerciseSessions(items: List<ExerciseSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNutritionMeals(items: List<NutritionMealEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(goal: GoalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeekly(report: WeeklyReportEntity)

    @Insert
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: ConversationMemoryEntity)

    @Query("SELECT * FROM daily_health_summary ORDER BY date DESC LIMIT :limit")
    fun observeDaily(limit: Int = 365): Flow<List<DailyHealthSummaryEntity>>

    @Query("SELECT * FROM body_composition_daily ORDER BY date DESC LIMIT :limit")
    fun observeBody(limit: Int = 365): Flow<List<BodyCompositionEntity>>

    @Query("SELECT * FROM health_source_status ORDER BY recordType")
    fun observeSources(): Flow<List<HealthSourceStatusEntity>>

    @Query("SELECT * FROM exercise_sessions ORDER BY startEpochMillis DESC LIMIT :limit")
    fun observeExerciseSessions(limit: Int = 365): Flow<List<ExerciseSessionEntity>>

    @Query("SELECT * FROM nutrition_meals ORDER BY startEpochMillis DESC LIMIT :limit")
    fun observeNutritionMeals(limit: Int = 2000): Flow<List<NutritionMealEntity>>

    @Query("SELECT * FROM exercise_sessions WHERE startEpochMillis BETWEEN :from AND :to ORDER BY startEpochMillis")
    suspend fun getExerciseSessions(from: Long, to: Long): List<ExerciseSessionEntity>

    @Query("SELECT * FROM nutrition_meals WHERE startEpochMillis BETWEEN :from AND :to ORDER BY startEpochMillis")
    suspend fun getNutritionMeals(from: Long, to: Long): List<NutritionMealEntity>

    @Query("SELECT * FROM goals WHERE id = 1")
    fun observeGoal(): Flow<GoalEntity?>

    @Query("SELECT * FROM goals WHERE id = 1")
    suspend fun getGoal(): GoalEntity?

    @Query("SELECT * FROM weekly_reports ORDER BY generatedAtEpochMillis DESC, weekStart DESC LIMIT 1")
    fun observeLatestWeekly(): Flow<WeeklyReportEntity?>

    @Query("SELECT * FROM weekly_reports ORDER BY generatedAtEpochMillis DESC, weekStart DESC LIMIT 1")
    suspend fun getLatestWeekly(): WeeklyReportEntity?

    @Query("SELECT * FROM daily_health_summary WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun getDaily(from: String, to: String): List<DailyHealthSummaryEntity>

    @Query("SELECT * FROM body_composition_daily WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun getBody(from: String, to: String): List<BodyCompositionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM daily_health_summary WHERE date >= :from AND (sleepDurationSource = 'legacy_session' OR nutritionRecordStatus = 'legacy'))")
    suspend fun needsMetricBackfill(from: String): Boolean

    @Query("UPDATE daily_health_summary SET nutritionComplete = :complete WHERE date = :date AND intakeCaloriesKcal IS NOT NULL")
    suspend fun setNutritionComplete(date: String, complete: Boolean)

    @Query("SELECT MIN(date) FROM daily_health_summary")
    suspend fun earliestDailyDate(): String?

    @Query(
        "DELETE FROM exercise_sessions " +
                "WHERE startEpochMillis >= :from AND startEpochMillis < :toExclusive",
    )
    suspend fun deleteExerciseSessionsInRange(from: Long, toExclusive: Long)

    @Query(
        "DELETE FROM nutrition_meals " +
                "WHERE startEpochMillis >= :from AND startEpochMillis < :toExclusive",
    )
    suspend fun deleteNutritionMealsInRange(from: Long, toExclusive: Long)

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    fun observeRecentMessages(limit: Int): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages ORDER BY id")
    suspend fun getAllMessages(): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE createdAtEpochMillis BETWEEN :from AND :to ORDER BY id")
    suspend fun getMessagesInRange(from: Long, to: Long): List<ChatMessageEntity>

    @Query("SELECT id FROM chat_messages ORDER BY id")
    suspend fun getMessageIds(): List<Long>

    @Query("SELECT * FROM chat_messages WHERE id > :afterId ORDER BY id")
    suspend fun getMessagesAfter(afterId: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE id <= :maxId")
    suspend fun deleteMessagesUpTo(maxId: Long)

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun messageCount(): Int

    @Query("SELECT * FROM conversation_memory WHERE id = 1")
    fun observeMemory(): Flow<ConversationMemoryEntity?>

    @Query("SELECT * FROM conversation_memory WHERE id = 1")
    suspend fun getMemory(): ConversationMemoryEntity?

    @Query("DELETE FROM daily_health_summary")
    suspend fun clearDaily()

    @Query("DELETE FROM body_composition_daily")
    suspend fun clearBody()

    @Query("DELETE FROM health_source_status")
    suspend fun clearSources()

    @Query("DELETE FROM exercise_sessions")
    suspend fun clearExerciseSessions()

    @Query("DELETE FROM nutrition_meals")
    suspend fun clearNutritionMeals()

    @Query("DELETE FROM goals")
    suspend fun clearGoals()

    @Query("DELETE FROM weekly_reports")
    suspend fun clearWeekly()

    @Query("DELETE FROM chat_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM conversation_memory")
    suspend fun clearMemory()

    @Insert suspend fun insertEvent(event: AppEventEntity)
    @Query("SELECT * FROM app_events WHERE timestamp BETWEEN :from AND :to AND (:category = '' OR category = :category) AND id > :after ORDER BY id LIMIT :limit")
    suspend fun events(from: Long, to: Long, category: String, after: Long, limit: Int): List<AppEventEntity>
    @Query("DELETE FROM app_events WHERE timestamp < :cutoff OR id NOT IN (SELECT id FROM app_events ORDER BY id DESC LIMIT 10000)")
    suspend fun pruneEvents(cutoff: Long)
    @Query("DELETE FROM app_events") suspend fun clearEvents()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveEvidence(item: ResponseEvidenceEntity)
    @Query("SELECT * FROM response_evidence WHERE messageId = :id") suspend fun evidence(id: Long): ResponseEvidenceEntity?
    @Query("DELETE FROM response_evidence WHERE timestamp < :cutoff OR messageId NOT IN (SELECT id FROM chat_messages) OR messageId NOT IN (SELECT messageId FROM response_evidence ORDER BY timestamp DESC LIMIT 40)")
    suspend fun pruneEvidence(cutoff: Long)
    @Query("DELETE FROM response_evidence") suspend fun clearEvidence()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun archiveReport(item: ReportArchiveEntity)
    @Query("SELECT * FROM report_archive WHERE periodEnd BETWEEN :from AND :to AND id > :after ORDER BY id LIMIT :limit")
    suspend fun reports(from: String, to: String, after: String, limit: Int): List<ReportArchiveEntity>
    @Query("SELECT * FROM report_archive WHERE id = :id") suspend fun report(id: String): ReportArchiveEntity?
    @Query("DELETE FROM report_archive WHERE generatedAt < :cutoff OR id NOT IN (SELECT id FROM report_archive ORDER BY generatedAt DESC LIMIT 50)")
    suspend fun pruneReports(cutoff: Long)
    @Query("DELETE FROM report_archive") suspend fun clearReports()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setMetadata(item: AppMetadataEntity)
    @Query("SELECT value FROM app_metadata WHERE `key` = :key") suspend fun metadata(key: String): String?
    @Query("DELETE FROM app_metadata WHERE `key` != 'revision'") suspend fun clearMetadata()
    @Query("SELECT * FROM chat_messages WHERE id = :id") suspend fun message(id: Long): ChatMessageEntity?
    @Query("SELECT * FROM chat_messages WHERE id > :after AND createdAtEpochMillis BETWEEN :from AND :to AND (:role = '' OR role = :role) AND instr(content, :query) > 0 ORDER BY id LIMIT :limit")
    suspend fun searchMessages(from: Long, to: Long, query: String, role: String, after: Long, limit: Int): List<ChatMessageEntity>
    @Query("SELECT * FROM chat_messages WHERE id < :id ORDER BY id DESC LIMIT :count")
    suspend fun messagesBefore(id: Long, count: Int): List<ChatMessageEntity>
    @Query("SELECT * FROM chat_messages WHERE id > :id ORDER BY id LIMIT :count")
    suspend fun messagesAfter(id: Long, count: Int): List<ChatMessageEntity>
    @Query("SELECT MIN(createdAtEpochMillis) FROM chat_messages") suspend fun earliestMessage(): Long?
    @Query("SELECT * FROM health_source_status ORDER BY recordType") suspend fun getSources(): List<HealthSourceStatusEntity>
}
