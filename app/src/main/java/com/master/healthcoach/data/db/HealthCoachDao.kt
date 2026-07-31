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

    @Query("SELECT * FROM exercise_sessions WHERE startEpochMillis BETWEEN :from AND :to ORDER BY startEpochMillis")
    suspend fun getExerciseSessions(from: Long, to: Long): List<ExerciseSessionEntity>

    @Query("SELECT * FROM goals WHERE id = 1")
    fun observeGoal(): Flow<GoalEntity?>

    @Query("SELECT * FROM goals WHERE id = 1")
    suspend fun getGoal(): GoalEntity?

    @Query("SELECT * FROM weekly_reports ORDER BY weekStart DESC LIMIT 1")
    fun observeLatestWeekly(): Flow<WeeklyReportEntity?>

    @Query("SELECT * FROM weekly_reports ORDER BY weekStart DESC LIMIT 1")
    suspend fun getLatestWeekly(): WeeklyReportEntity?

    @Query("SELECT * FROM daily_health_summary WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun getDaily(from: String, to: String): List<DailyHealthSummaryEntity>

    @Query("SELECT * FROM body_composition_daily WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun getBody(from: String, to: String): List<BodyCompositionEntity>

    @Query("SELECT MIN(date) FROM daily_health_summary")
    suspend fun earliestDailyDate(): String?

    @Query(
        "DELETE FROM exercise_sessions " +
            "WHERE startEpochMillis >= :from AND startEpochMillis < :toExclusive",
    )
    suspend fun deleteExerciseSessionsInRange(from: Long, toExclusive: Long)

    @Query("SELECT * FROM chat_messages ORDER BY id")
    fun observeMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE id > :afterId ORDER BY id")
    suspend fun getMessagesAfter(afterId: Long): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun messageCount(): Int

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

    @Query("DELETE FROM goals")
    suspend fun clearGoals()

    @Query("DELETE FROM weekly_reports")
    suspend fun clearWeekly()

    @Query("DELETE FROM chat_messages")
    suspend fun clearMessages()

    @Query("DELETE FROM conversation_memory")
    suspend fun clearMemory()
}
