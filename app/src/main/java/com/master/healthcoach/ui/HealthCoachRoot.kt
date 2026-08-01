package com.master.healthcoach.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.health.HealthConnectAvailability
import com.master.healthcoach.domain.TrendMath
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

private enum class AppTab(val label: String, val icon: ImageVector) {
    TODAY("今日", Icons.Default.Home),
    TRENDS("推移", Icons.Default.ShowChart),
    WEEKLY("週報", Icons.Default.AutoAwesome),
    CHAT("チャット", Icons.Default.Chat),
    SETTINGS("設定", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthCoachRoot(
    state: MainUiState,
    onRequestPermissions: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onSync: () -> Unit,
    onSaveGoal: (GoalEntity) -> Unit,
    onSaveApiKey: (String, String) -> Unit,
    onClearApiKey: () -> Unit,
    onSendChat: (String) -> Unit,
    onAddChatAttachments: (List<Uri>) -> Unit,
    onRemoveChatAttachment: (String) -> Unit,
    onAnalyzeWeek: () -> Unit,
    onClearLocalData: () -> Unit,
    onMessageShown: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.TODAY) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Health Coach", fontWeight = FontWeight.SemiBold)
                        Text(
                            selectedTab.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSync, enabled = !state.isSyncing) {
                        if (state.isSyncing) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "同期")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!state.hasCorePermissions) {
                PermissionScreen(state, onRequestPermissions, onOpenHealthConnect)
            } else {
                when (selectedTab) {
                    AppTab.TODAY -> DashboardScreen(state, onSync)
                    AppTab.TRENDS -> TrendsScreen(state)
                    AppTab.WEEKLY -> WeeklyScreen(state, onAnalyzeWeek)
                    AppTab.CHAT -> ChatScreen(
                        state = state,
                        onSendChat = onSendChat,
                        onAddAttachments = onAddChatAttachments,
                        onRemoveAttachment = onRemoveChatAttachment,
                    )
                    AppTab.SETTINGS -> SettingsScreen(
                        state = state,
                        onSaveGoal = onSaveGoal,
                        onSaveApiKey = onSaveApiKey,
                        onClearApiKey = onClearApiKey,
                        onRequestPermissions = onRequestPermissions,
                        onOpenHealthConnect = onOpenHealthConnect,
                        onClearLocalData = onClearLocalData,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionScreen(
    state: MainUiState,
    onRequestPermissions: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (state.availability == HealthConnectAvailability.AVAILABLE) {
                Icons.Default.FitnessCenter
            } else Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(54.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(18.dp))
        Text("端末内の健康データを接続", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "体組成・歩数・運動・活動消費・睡眠・心拍・活動強度を" +
                "Health Connectから読み取ります。" +
                "生データはこの端末に留まり、Geminiには明示操作時に加工済みデータだけを送ります。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        when (state.availability) {
            HealthConnectAvailability.AVAILABLE -> Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Health Connectへのアクセスを許可") }

            HealthConnectAvailability.UPDATE_REQUIRED -> Button(
                onClick = onOpenHealthConnect,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Health Connectを更新") }

            HealthConnectAvailability.UNAVAILABLE -> Text(
                "この端末ではHealth Connectを利用できません。",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenHealthConnect, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Health Connectの設定を開く")
        }
    }
}

@Composable
private fun DashboardScreen(state: MainUiState, onSync: () -> Unit) {
    val zoneId = ZoneId.systemDefault()
    val today = LocalDate.now(zoneId)
    val todayStr = today.toString()
    val todayDaily = state.daily.firstOrNull { it.date == todayStr }
    val todayBody = state.body.firstOrNull { it.date == todayStr }
    val displayedBody = todayBody ?: state.body.firstOrNull()
    val todayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val tomorrowStart = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val todaySessions = state.exerciseSessions.filter {
        it.startEpochMillis in todayStart until tomorrowStart
    }
    val morningRoutineMinutes = maxOf(
        todayDaily?.morningRoutineMinutes ?: 0,
        todaySessions.filter { it.category == "morning_routine" }
            .sumOf { it.durationMinutes },
    )
    val hasTodayData = todayBody != null || todaySessions.isNotEmpty() || todayDaily?.let {
        it.steps != null ||
            it.distanceMeters != null ||
            it.activeCaloriesKcal != null ||
            it.sleepMinutes != null ||
            it.heartRateMeasurementCount != null ||
            it.moderateIntensityMinutes != null ||
            it.vigorousIntensityMinutes != null
    } == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                "今日の記録",
                "Health Connectの当日値を、平均や前週比へまとめず表示",
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("朝の5分ルーティン", style = MaterialTheme.typography.labelLarge)
                            Text(
                                if (morningRoutineMinutes > 0) "実施" else "記録なし",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Icon(
                            if (morningRoutineMinutes > 0) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.FitnessCenter
                            },
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Text(
                        if (morningRoutineMinutes > 0) {
                            "記録値 $morningRoutineMinutes 分。軽い筋トレ＋有酸素として評価します。"
                        } else {
                            "Health Connectの「その他のワークアウト」が記録されると実施扱いになります。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "週次では一般的な筋トレ日数ではなく、この朝トレの実施日数を習慣KPIにします。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                }
            }
        }
        item {
            SectionHeader(
                "体組成",
                if (todayBody != null) {
                    todayBody.measurementEpochMillis?.let { "本日 ${formatInstant(it)} の測定" }
                        ?: "本日の測定値"
                } else {
                    displayedBody?.measurementEpochMillis?.let {
                        "本日未測定・最新は ${formatInstant(it)}"
                    } ?: "本日の測定はありません"
                },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "体重",
                    displayedBody?.weightKg.kg(),
                    if (todayBody != null) "実測値" else "最新値",
                    Modifier.weight(1f),
                )
                MetricCard(
                    "体脂肪率",
                    displayedBody?.bodyFatPercent?.let {
                        "${DecimalFormat("0.0").format(it)} %"
                    } ?: "未取得",
                    "BIA測定値",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "脂肪量",
                    displayedBody?.fatMassKg.kg(),
                    "体重×体脂肪率",
                    Modifier.weight(1f),
                )
                MetricCard(
                    "除脂肪量",
                    displayedBody?.leanBodyMassKg.kg(),
                    "体重−脂肪量",
                    Modifier.weight(1f),
                )
            }
        }
        item { SectionHeader("活動", "今日0時から現在までの記録値") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "歩数",
                    todayDaily?.steps?.let { "%,d歩".format(Locale.JAPAN, it) }
                        ?: "未取得",
                    "本日累計",
                    Modifier.weight(1f),
                    Icons.Default.DirectionsWalk,
                )
                MetricCard(
                    "距離",
                    todayDaily?.distanceMeters.asDistance(),
                    "本日累計",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "中強度",
                    todayDaily?.moderateIntensityMinutes?.let { "$it 分" } ?: "未取得",
                    "Health Connect記録",
                    Modifier.weight(1f),
                )
                MetricCard(
                    "高強度",
                    todayDaily?.vigorousIntensityMinutes?.let { "$it 分" } ?: "未取得",
                    "Health Connect記録",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "活動消費",
                    todayDaily?.activeCaloriesKcal?.roundToLong()?.let { "$it kcal" }
                        ?: "未取得",
                    "デバイス推定・参考",
                    Modifier.weight(1f),
                )
                MetricCard(
                    "基礎代謝",
                    todayDaily?.basalCaloriesKcal?.roundToLong()?.let { "$it kcal" }
                        ?: "未取得",
                    "Health Connect集計・参考",
                    Modifier.weight(1f),
                )
            }
        }
        item { SectionHeader("睡眠と心拍", "本日に終了した主睡眠と本日の心拍記録") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "主睡眠",
                    todayDaily?.sleepMinutes?.asHoursMinutes() ?: "未取得",
                    listOfNotNull(
                        todayDaily?.sleepStartEpochMillis?.let { formatClock(it) },
                        todayDaily?.sleepEndEpochMillis?.let { formatClock(it) },
                    ).joinToString("〜").ifBlank { "起床日に記録" },
                    Modifier.weight(1f),
                )
                MetricCard(
                    "睡眠中心拍",
                    todayDaily?.sleepHeartRateAverageBpm?.let { "$it bpm" } ?: "未取得",
                    "主睡眠区間の平均",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("本日の心拍", style = MaterialTheme.typography.labelLarge)
                    Text(
                        todayDaily?.heartRateAverageBpm?.let { "平均 $it bpm" } ?: "未取得",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        listOfNotNull(
                            todayDaily?.heartRateMinimumBpm?.let { "最小 $it" },
                            todayDaily?.heartRateMaximumBpm?.let { "最大 $it" },
                            todayDaily?.heartRateMeasurementCount?.let { "${it}件" },
                        ).joinToString("・").ifBlank { "測定なし" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionHeader("今日の運動", "セッションを記録時刻と実時間のまま表示") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "セッション",
                    todayDaily?.exerciseSessionCount?.let { "$it 件" }
                        ?: todaySessions.size.let { "$it 件" },
                    "Health Connect記録",
                    Modifier.weight(1f),
                )
                MetricCard(
                    "運動時間",
                    todayDaily?.exerciseMinutes?.let { "$it 分" }
                        ?: todaySessions.sumOf { it.durationMinutes }.let { "$it 分" },
                    "重複分類せず実時間",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "有酸素分類",
                    todayDaily?.cardioMinutes?.let { "$it 分" } ?: "未取得",
                    "朝ルーティンを含む",
                    Modifier.weight(1f),
                )
                MetricCard(
                    "通常の筋トレ",
                    todayDaily?.strengthMinutes?.let { "$it 分" } ?: "未取得",
                    "朝ルーティンは別枠",
                    Modifier.weight(1f),
                )
            }
        }
        if (todaySessions.isEmpty()) {
            item {
                EmptyCard(
                    "今日の運動セッションはありません",
                    "端末側で記録後、Health Connectを同期すると表示されます",
                )
            }
        } else {
            items(todaySessions, key = { it.recordId }) { session ->
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(session.exerciseLabel, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatClock(session.startEpochMillis)}〜" +
                                    formatClock(session.endEpochMillis),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${session.durationMinutes}分")
                    }
                }
            }
        }
        item {
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "歩数・距離・消費・心拍・活動強度は日内のHealth Connect集計値で、" +
                            "同期時刻や端末側の反映により今日中も更新されます。" +
                            "7日・28日の傾向は「推移」と「週報」で確認できます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (!hasTodayData) {
            item {
                EmptyCard(
                    "今日のデータはまだありません",
                    "同期するとHealth Connectに届いている当日値を読み取ります",
                    onSync,
                )
            }
        }
    }
}

@Composable
private fun TrendsScreen(state: MainUiState) {
    var rangeDays by rememberSaveable { mutableStateOf(28) }
    val today = LocalDate.now()
    val oldest = (state.daily.map { LocalDate.parse(it.date) } +
        state.body.map { LocalDate.parse(it.date) }).minOrNull()
    val availableDays = oldest?.let {
        ChronoUnit.DAYS.between(it, today).toInt() + 1
    } ?: 1
    val pageCount = ceil(availableDays.toDouble() / rangeDays).toInt().coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = 0) { pageCount }
    val pagerScope = rememberCoroutineScope()
    LaunchedEffect(rangeDays) {
        pagerState.scrollToPage(0)
    }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(
                "期間別トレンド",
                "点は実測、太線は7日中央値。左右スワイプ・タップで確認",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "7日", 28 to "28日", 90 to "90日").forEach { (days, label) ->
                    FilterChip(
                        selected = rangeDays == days,
                        onClick = { rangeDays = days },
                        label = { Text(label) },
                    )
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val end = today.minusDays((page * rangeDays).toLong())
            val start = end.minusDays((rangeDays - 1).toLong())
            val bodyByDate = state.body.associateBy { it.date }
            val dailyByDate = state.daily.associateBy { it.date }
            val dates = (0 until rangeDays).map { start.plusDays(it.toLong()) }
            val sessions = state.exerciseSessions.filter {
                val date = Instant.ofEpochMilli(it.startEpochMillis)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                date in start..end
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                if (page > 0) {
                                    pagerScope.launch {
                                        pagerState.animateScrollToPage(page - 1)
                                    }
                                }
                            },
                            enabled = page > 0,
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "新しい期間")
                        }
                        Text(
                            "${start.format(TREND_DATE_FORMAT)} 〜 " +
                                end.format(TREND_DATE_FORMAT),
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(
                            onClick = {
                                if (page < pageCount - 1) {
                                    pagerScope.launch {
                                        pagerState.animateScrollToPage(page + 1)
                                    }
                                }
                            },
                            enabled = page < pageCount - 1,
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "古い期間")
                        }
                    }
                }
                item {
                    if (page == 0 && state.weekly != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("現在の28日判定", fontWeight = FontWeight.SemiBold)
                                Text(
                                    state.weekly.weightLossRatePercentPerWeek?.let {
                                        "減量ペース ${DecimalFormat("0.00").format(it)}%/週・" +
                                            weightPaceGuidance(it)
                                    } ?: "体重データが8回・14日以上たまると判定します",
                                )
                            }
                        }
                    }
                }
                item {
                    val raw = dates.map { date ->
                        TrendPoint(date, bodyByDate[date.toString()]?.weightKg)
                    }
                    TrendCard(
                        title = "体重",
                        unit = "kg",
                        points = raw,
                        overlayPoints = rollingBodyMedian(
                            dates = dates,
                            allBody = state.body,
                            value = { it.weightKg },
                        ),
                        summary = if (page == 0) {
                            (state.weekly?.weightTrendKgPerWeek).signedOrMissing("kg/週") +
                                "（28日傾向）"
                        } else {
                            null
                        },
                    )
                }
                item {
                    val raw = dates.map { date ->
                        TrendPoint(date, bodyByDate[date.toString()]?.fatMassKg)
                    }
                    TrendCard(
                        title = "脂肪量",
                        unit = "kg",
                        points = raw,
                        overlayPoints = rollingBodyMedian(
                            dates = dates,
                            allBody = state.body,
                            value = { it.fatMassKg },
                        ),
                        summary = if (page == 0) {
                            (state.weekly?.fatMassTrendKgPerWeek).signedOrMissing("kg/週") +
                                "（BIA参考）"
                        } else {
                            null
                        },
                    )
                }
                item {
                    val raw = dates.map { date ->
                        TrendPoint(date, bodyByDate[date.toString()]?.leanBodyMassKg)
                    }
                    TrendCard(
                        title = "除脂肪量（計算値）",
                        unit = "kg",
                        points = raw,
                        overlayPoints = rollingBodyMedian(
                            dates = dates,
                            allBody = state.body,
                            value = { it.leanBodyMassKg },
                        ),
                        summary = if (page == 0) {
                            (state.weekly?.leanMassTrendKgPerWeek).signedOrMissing("kg/週") +
                                "（筋肉量そのものではありません）"
                        } else {
                            null
                        },
                    )
                }
                item { SectionHeader("行動KPI", "朝トレ習慣と、減量中も落としたくない活動量") }
                item {
                    TrendCard(
                        title = "朝の5分ルーティン",
                        unit = "分",
                        points = dates.map { date ->
                            TrendPoint(
                                date,
                                dailyByDate[date.toString()]?.morningRoutineMinutes?.toDouble(),
                            )
                        },
                        summary = if (page == 0) {
                            state.weekly?.let {
                                "${it.morningRoutineDays}/${it.morningRoutineTargetDays}日" +
                                    (
                                        it.morningRoutineAdherencePercent?.let { percent ->
                                            "・朝トレ継続率 $percent%"
                                        } ?: ""
                                        )
                            }
                        } else {
                            null
                        },
                    )
                }
                item {
                    TrendCard(
                        title = "歩数",
                        unit = "歩",
                        points = dates.map { date ->
                            TrendPoint(date, dailyByDate[date.toString()]?.steps?.toDouble())
                        },
                        summary = if (page == 0) {
                            listOfNotNull(
                                state.weekly?.stepsTargetHitDays?.let { "目標達成 $it/7日" },
                                state.weekly?.stepsBaselinePercent?.let { "開始前比 $it%" },
                            ).joinToString("・").ifBlank { null }
                        } else {
                            null
                        },
                    )
                }
                item {
                    TrendCard(
                        title = "中強度換算の活動",
                        unit = "分",
                        points = dates.map { date ->
                            val item = dailyByDate[date.toString()]
                            val value = if (
                                item?.moderateIntensityMinutes != null ||
                                item?.vigorousIntensityMinutes != null
                            ) {
                                (item.moderateIntensityMinutes ?: 0) +
                                    2 * (item.vigorousIntensityMinutes ?: 0)
                            } else {
                                null
                            }
                            TrendPoint(
                                date,
                                value?.toDouble(),
                                listOfNotNull(
                                    item?.moderateIntensityMinutes?.let { "中強度 ${it}分" },
                                    item?.vigorousIntensityMinutes?.let { "高強度 ${it}分" },
                                ),
                            )
                        },
                        summary = "中強度＋高強度×2",
                    )
                }
                item { SectionHeader("回復シグナル", "主睡眠と睡眠中心拍を別々に確認") }
                item {
                    TrendCard(
                        title = "主睡眠",
                        unit = "分",
                        points = dates.map { date ->
                            val item = dailyByDate[date.toString()]
                            TrendPoint(
                                date,
                                item?.sleepMinutes?.toDouble(),
                                listOfNotNull(
                                    item?.sleepStartEpochMillis?.let {
                                        "就寝 ${formatInstant(it)}"
                                    },
                                    item?.sleepEndEpochMillis?.let {
                                        "起床 ${formatInstant(it)}"
                                    },
                                ),
                            )
                        },
                        summary = if (page == 0) {
                            state.weekly?.sleepTargetHitDays?.let { "7時間以上 $it/7日" }
                        } else {
                            null
                        },
                    )
                }
                item {
                    TrendCard(
                        title = "睡眠中心拍",
                        unit = "bpm",
                        points = dates.map { date ->
                            val item = dailyByDate[date.toString()]
                            TrendPoint(
                                date = date,
                                value = item?.sleepHeartRateAverageBpm?.toDouble(),
                                details = listOfNotNull(
                                    item?.heartRateAverageBpm?.let {
                                        "終日平均 $it bpm（参考）"
                                    },
                                ),
                            )
                        },
                        summary = if (page == 0) {
                            state.weekly?.sleepHeartRateBaselineDeltaBpm?.let {
                                "直前21日比 ${signed(it.toDouble())} bpm"
                            }
                        } else {
                            null
                        },
                    )
                }
                item { SectionHeader("参考値", "消費カロリーは減量ペースの根拠にしません") }
                item {
                    TrendCard(
                        "活動消費",
                        "kcal",
                        dates.map { date ->
                            TrendPoint(date, dailyByDate[date.toString()]?.activeCaloriesKcal)
                        },
                    )
                }
                item {
                    TrendCard(
                        "基礎代謝",
                        "kcal/日",
                        dates.map { date ->
                            TrendPoint(date, dailyByDate[date.toString()]?.basalCaloriesKcal)
                        },
                    )
                }
                item { SectionHeader("期間内の運動", "Health Connectの運動セッション") }
                if (sessions.isEmpty()) {
                    item { EmptyCard("この期間の運動セッションはありません", "記録がある期間へスワイプしてください") }
                } else {
                    items(sessions, key = { it.recordId }) { session ->
                        Card {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(session.exerciseLabel, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        formatInstant(session.startEpochMillis),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("${session.durationMinutes}分")
                            }
                        }
                    }
                }
                item {
                    Text(
                        "除脂肪量は体重と体脂肪率から計算し、水分・骨なども含みます。" +
                            "体脂肪率も絶対値ではなく、同条件での長期傾向だけを参考にしてください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyScreen(state: MainUiState, onAnalyzeWeek: () -> Unit) {
    val report = state.weekly
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(
                "週次ボディメイクレビュー",
                "7日中央値と28日傾向で、脂肪減少と筋力維持の行動を確認",
            )
        }
        if (report == null) {
            item { EmptyCard("週報はまだありません", "Health Connectを同期すると自動作成されます") }
        } else {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "${report.weekStart} 〜 ${report.weekEnd}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text("28日ベースの減量ペース")
                        Text(
                            report.weightLossRatePercentPerWeek?.let {
                                "${DecimalFormat("0.00").format(it)} % / 週"
                            } ?: "判定保留",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(weightPaceGuidance(report.weightLossRatePercentPerWeek))
                        Text(
                            "28日計測 ${report.trendMeasurementDays}日・" +
                                "今週 ${report.bodyMeasurementDays}日",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "体組成の傾向",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "カードは前週中央値比",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricChangeChip(
                                label = "体重変化",
                                value = report.weightChangeKg,
                                unit = "kg",
                                lowerIsBetter = null,
                                modifier = Modifier.weight(1f),
                            )
                            MetricChangeChip(
                                label = "脂肪量変化",
                                value = report.fatMassChangeKg,
                                unit = "kg",
                                lowerIsBetter = null,
                                modifier = Modifier.weight(1f),
                            )
                            MetricChangeChip(
                                label = "除脂肪量変化",
                                value = report.leanMassChangeKg,
                                unit = "kg",
                                lowerIsBetter = null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "脂肪量 28日",
                                mainValue = report.fatMassTrendKgPerWeek
                                    .signedOrMissing("kg/週"),
                                subValue = "BIAの長期傾向",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "除脂肪量 28日",
                                mainValue = report.leanMassTrendKgPerWeek
                                    .signedOrMissing("kg/週"),
                                subValue = leanTrendGuidance(report.leanMassTrendKgPerWeek),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            val weekDaily = state.daily.take(7).reversed()
            if (weekDaily.isNotEmpty()) {
                item {
                    val stepPoints = weekDaily.map {
                        TrendPoint(LocalDate.parse(it.date), it.steps?.toDouble())
                    }
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "歩数の維持",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "平均 ${report.stepsDailyAverage?.let { "%,d歩/日".format(it) } ?: "未取得"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (stepPoints.mapNotNull { it.value }.size >= 2) {
                                InteractiveSparkline(
                                    points = stepPoints,
                                    selectedIndex = null,
                                    onSelect = {},
                                    modifier = Modifier.fillMaxWidth().height(80.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val stepsDiff = compareDiff(report.stepsDailyAverage, report.previousWeekStepsDailyAverage)
                    ReportTile(
                        title = "1日平均歩数",
                        mainValue = report.stepsDailyAverage?.let { "%,d歩".format(it) } ?: "未取得",
                        subValue = stepsDiff?.let { "前週比 ${signed(it.toDouble())}歩" } ?: "前週データなし",
                        icon = Icons.Default.DirectionsWalk,
                        modifier = Modifier.weight(1f),
                    )

                    ReportTile(
                        title = "中強度換算",
                        mainValue = report.moderateEquivalentMinutes?.let { "$it 分" }
                            ?: "未取得",
                        subValue = compareDiff(
                            report.moderateEquivalentMinutes,
                            report.previousWeekModerateEquivalentMinutes,
                        )?.let { "前週比 ${signed(it.toDouble())}分" }
                            ?: "中強度＋高強度×2",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "朝トレ継続",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${report.morningRoutineDays}/" +
                                    "${report.morningRoutineTargetDays}日",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        val routineFraction = if (report.morningRoutineTargetDays > 0) {
                            report.morningRoutineDays.toFloat() /
                                report.morningRoutineTargetDays
                        } else {
                            0f
                        }
                        LinearProgressIndicator(
                            progress = { routineFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "前週 ${report.previousWeekMorningRoutineDays}日",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "計 ${report.morningRoutineMinutes}分",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Health Connectの「その他」を朝の5分ルーティンとして数えます。" +
                                "軽い筋トレ＋有酸素ですが、実際の筋力は月1回の" +
                                "パーソナルで同じ種目の負荷・回数を確認してください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "リカバリーシグナル",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "平均睡眠",
                                mainValue = report.sleepDailyAverageMinutes?.asHoursMinutes() ?: "未取得",
                                subValue = compareDiff(report.sleepDailyAverageMinutes, report.previousWeekSleepDailyAverageMinutes)?.let {
                                    "前週比 ${signed(it.toDouble())}分"
                                } ?: "前週データなし",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "睡眠中心拍",
                                mainValue = report.sleepHeartRateAverageBpm?.let { "$it bpm" }
                                    ?: "未取得",
                                subValue = report.sleepHeartRateBaselineDeltaBpm?.let {
                                    "直前21日比 ${signed(it.toDouble())} bpm"
                                } ?: "比較データ不足",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "7時間以上",
                                mainValue = report.sleepTargetHitDays?.let { "$it / 7日" }
                                    ?: "未取得",
                                subValue = "主睡眠の達成日",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "就寝時刻のばらつき",
                                mainValue = report.sleepScheduleDeviationMinutes?.let { "$it 分" }
                                    ?: "判定保留",
                                subValue = "中央値からの代表偏差",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                Card {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "測定の品質",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReportTile(
                                title = "体組成の時刻統一",
                                mainValue = report.measurementTimeConsistencyPercent?.let {
                                    "$it%"
                                } ?: "判定保留",
                                subValue = "中央値±90分以内",
                                modifier = Modifier.weight(1f),
                            )
                            ReportTile(
                                title = "28日計測日",
                                mainValue = "${report.trendMeasurementDays}日",
                                subValue = "8回・14日以上で判定",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "寝起き・トイレ後・飲食前・同程度の服装で統一。" +
                                "BIAの脂肪量・除脂肪量は単日値で判断しません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("参考値", fontWeight = FontWeight.SemiBold)
                        Text(
                            "活動消費 ${
                                report.activeCaloriesDailyAverage?.roundToLong()?.let {
                                    "$it kcal/日"
                                } ?: "未取得"
                            }・基礎代謝 ${
                                report.basalCaloriesDailyAverage?.roundToLong()?.let {
                                    "$it kcal/日"
                                } ?: "未取得"
                            }",
                        )
                        Text(
                            "デバイスの推定消費量はエネルギー赤字の算定には使わず、" +
                                "体重ペースと行動KPIで調整します。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (report.dataLimitations.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("データ補足・注意点", fontWeight = FontWeight.SemiBold)
                            }
                            report.dataLimitations.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onAnalyzeWeek,
                    enabled = state.apiKeyConfigured && !state.isSending,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (state.isSending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.apiKeyConfigured) {
                            "専門コーチで今週を分析"
                        } else {
                            "設定でAPIキーを登録してください"
                        },
                    )
                }
            }
        }

        state.weeklyAdvice?.let { advice ->
            item {
                SectionHeader(
                    "専門コーチ分析",
                    "端末内KPI判定とGeminiによる健康・習慣アドバイス",
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(advice.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        HorizontalDivider()
                        advice.positiveChange?.let { AdviceBlock("よかった変化", it) }
                        advice.caution?.let { AdviceBlock("注意点", it) }
                        if (advice.habitInsights.isNotEmpty()) {
                            Text("習慣との関連", fontWeight = FontWeight.SemiBold)
                            advice.habitInsights.forEach { Text("・$it") }
                        }
                        if (advice.nextActions.isNotEmpty()) {
                            Text("次の一手", fontWeight = FontWeight.SemiBold)
                            advice.nextActions.forEachIndexed { index, action ->
                                Text("${index + 1}. $action")
                            }
                        }
                        if (advice.dataLimitations.isNotEmpty()) {
                            Text("判定の限界", fontWeight = FontWeight.SemiBold)
                            advice.dataLimitations.take(3).forEach { Text("・$it") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            SuggestionChip(onClick = {}, label = { Text("確からしさ: ${advice.confidence}") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(
    state: MainUiState,
    onSendChat: (String) -> Unit,
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = onAddAttachments,
    )
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        if (!state.apiKeyConfigured) {
            Text(
                "設定画面で自分のGemini APIキーを登録すると相談できます。",
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    EmptyCard(
                        "専門コーチへ相談できます",
                        "例：今の減量ペースで筋力を守れそう？\n" +
                            "Geminiの回答へ、端末内KPIによる専門家判定を合成します。",
                    )
                }
            }
            items(state.messages, key = { it.id }) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (message.role == "user") MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .padding(14.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message.content)
                            message.attachmentNames
                                ?.lineSequence()
                                ?.filter(String::isNotBlank)
                                ?.forEach { name ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.AttachFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(5.dp))
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    }
                                }
                        }
                    }
                }
            }
            if (state.isSending) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("必要なデータを集計しています…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.chatAttachments.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.chatAttachments.forEach { attachment ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(start = 10.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (attachment.mimeType.startsWith("image/")) {
                                Icons.Default.Image
                            } else {
                                Icons.Default.Description
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                attachment.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                attachment.sizeBytes.asFileSize(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onRemoveAttachment(attachment.id) },
                            enabled = !state.isSending,
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "添付を外す")
                        }
                    }
                }
                Text(
                    "最大4件・合計12MB。内容は今回の送信時だけGeminiへ渡し、端末には保存しません。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = {
                    attachmentPicker.launch(
                        arrayOf(
                            "image/*",
                            "application/pdf",
                            "text/*",
                            "application/json",
                        ),
                    )
                },
                enabled = state.apiKeyConfigured &&
                    !state.isSending &&
                    !state.isAddingAttachments,
            ) {
                if (state.isAddingAttachments) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AttachFile, contentDescription = "写真やファイルを添付")
                }
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("質問を入力（添付だけでも送信可）") },
                maxLines = 4,
            )
            Button(
                onClick = {
                    onSendChat(input)
                    input = ""
                },
                enabled = (input.isNotBlank() || state.chatAttachments.isNotEmpty()) &&
                    state.apiKeyConfigured &&
                    !state.isSending &&
                    !state.isAddingAttachments,
            ) { Text("送信") }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    onSaveGoal: (GoalEntity) -> Unit,
    onSaveApiKey: (String, String) -> Unit,
    onClearApiKey: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onClearLocalData: () -> Unit,
) {
    var age by rememberSaveable(state.goal) { mutableStateOf(state.goal?.age?.toString().orEmpty()) }
    var height by rememberSaveable(state.goal) { mutableStateOf(state.goal?.heightCm.clean().orEmpty()) }
    var sex by rememberSaveable(state.goal) { mutableStateOf(state.goal?.sex.orEmpty()) }
    var dietStartDate by rememberSaveable(state.goal) {
        mutableStateOf(state.goal?.dietStartDate.orEmpty())
    }
    var deadline by rememberSaveable(state.goal) { mutableStateOf(state.goal?.deadline.orEmpty()) }
    var fatTarget by rememberSaveable(state.goal) { mutableStateOf(state.goal?.targetFatMassKg.clean().orEmpty()) }
    var leanMinimum by rememberSaveable(state.goal) { mutableStateOf(state.goal?.minimumLeanMassKg.clean().orEmpty()) }
    var steps by rememberSaveable(state.goal) { mutableStateOf(state.goal?.dailySteps?.toString().orEmpty()) }
    var sessions by rememberSaveable(state.goal) { mutableStateOf(state.goal?.weeklyExerciseSessions?.toString().orEmpty()) }
    var calories by rememberSaveable(state.goal) { mutableStateOf(state.goal?.dailyActiveCaloriesKcal.clean().orEmpty()) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable(state.modelId) { mutableStateOf(state.modelId) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 10.dp, 16.dp, 34.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("プロフィールと目標", "AIはここに入力した情報だけを個人化に使います")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(age, { age = it }, "年齢", Modifier.weight(1f), KeyboardType.Number)
            SmallField(height, { height = it }, "身長 cm", Modifier.weight(1f), KeyboardType.Decimal)
            SmallField(sex, { sex = it }, "性別", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(
                dietStartDate,
                { dietStartDate = it },
                "減量開始日 YYYY-MM-DD",
                Modifier.weight(1f),
            )
            SmallField(
                deadline,
                { deadline = it },
                "目標期限 YYYY-MM-DD",
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(fatTarget, { fatTarget = it }, "目標脂肪量 kg", Modifier.weight(1f), KeyboardType.Decimal)
            SmallField(leanMinimum, { leanMinimum = it }, "維持する除脂肪量 kg", Modifier.weight(1f), KeyboardType.Decimal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(steps, { steps = it }, "1日歩数", Modifier.weight(1f), KeyboardType.Number)
            SmallField(
                sessions,
                { sessions = it },
                "週の朝トレ目標日数",
                Modifier.weight(1f),
                KeyboardType.Number,
            )
        }
        SmallField(
            calories,
            { calories = it },
            "参考：1日の活動消費目標 kcal",
            Modifier.fillMaxWidth(),
            KeyboardType.Decimal,
        )
        Text(
            "体組成は寝起き・トイレ後・飲食前・同程度の服装で測定すると、" +
                "28日トレンドのノイズを抑えられます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                onSaveGoal(
                    GoalEntity(
                        age = age.toIntOrNull(),
                        heightCm = height.toDoubleOrNull(),
                        sex = sex.ifBlank { null },
                        dietStartDate = dietStartDate.ifBlank { null },
                        deadline = deadline.ifBlank { null },
                        targetFatMassKg = fatTarget.toDoubleOrNull(),
                        minimumLeanMassKg = leanMinimum.toDoubleOrNull(),
                        dailySteps = steps.toLongOrNull(),
                        weeklyExerciseSessions = sessions.toIntOrNull(),
                        dailyActiveCaloriesKcal = calories.toDoubleOrNull(),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("目標を保存") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Gemini API", "キーはAndroid Keystoreで暗号化し、この端末だけに保存します")
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (state.apiKeyConfigured) "新しいAPIキーで置き換える" else "Gemini APIキー") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        SmallField(model, { model = it }, "モデルID", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSaveApiKey(apiKey, model); apiKey = "" },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("API設定を保存") }
            if (state.apiKeyConfigured) {
                OutlinedButton(onClick = onClearApiKey) { Text("キーを削除") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("実機データ診断", "Mi Fitnessとeufyが実際に書いた項目を確認します")
        state.sources.forEach { source ->
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.recordType, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${source.recordCount}件・${source.origins.ifBlank { "提供元なし" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        source.latestRecordEpochMillis?.let {
                            Text("最新 ${formatInstant(it)}", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(source.status, style = MaterialTheme.typography.labelSmall)
                    }
                    val healthy = source.status == "取得可能" || source.status == "計算可能"
                    Icon(
                        if (healthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                        null,
                        tint = if (healthy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
        if (state.sources.isEmpty()) Text("同期後に診断結果が表示されます。")
        if (!state.grantedPermissions.containsAll(state.requiredPermissions)) {
            Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                Text("睡眠・心拍・履歴などの権限を許可")
            }
        }
        OutlinedButton(onClick = onOpenHealthConnect, modifier = Modifier.fillMaxWidth()) {
            Text("Health Connectの権限を管理")
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("データ管理", "バックアップはなく、削除後は元に戻せません")
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text("アプリ内データとAPIキーを削除")
        }
        Text(
            "Health Connect内の原データは削除しません。医療診断を目的とするアプリではありません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("端末内データを削除しますか？") },
            text = { Text("目標、集計、週報、AI会話、APIキーが削除されます。Health Connectの原データは残ります。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onClearLocalData() }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)) }
                Text(title, style = MaterialTheme.typography.labelLarge)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class TrendPoint(
    val date: LocalDate,
    val value: Double?,
    val details: List<String> = emptyList(),
)

@Composable
private fun TrendCard(
    title: String,
    unit: String,
    points: List<TrendPoint>,
    overlayPoints: List<TrendPoint> = emptyList(),
    summary: String? = null,
) {
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val measured = points.mapNotNull { it.value }
    val selected = selectedIndex?.let { points.getOrNull(it) }
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    measured.lastOrNull()?.let {
                        "${DecimalFormat("0.#").format(it)} $unit"
                    } ?: "未取得",
                )
            }
            summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (measured.size < 2) {
                Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.Center) {
                    Text("表示に十分なデータがありません", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                InteractiveSparkline(
                    points = points,
                    overlayPoints = overlayPoints,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                    modifier = Modifier.fillMaxWidth().height(108.dp),
                )
                selected?.let { point ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp),
                            )
                            .padding(10.dp),
                    ) {
                        Text(
                            point.date.format(TREND_DETAIL_DATE_FORMAT),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            point.value?.let {
                                "${DecimalFormat("0.#").format(it)} $unit"
                            } ?: "未取得",
                            fontWeight = FontWeight.SemiBold,
                        )
                        point.details.forEach {
                            Text(it, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (overlayPoints.any { it.value != null }) {
                            "${measured.size}測定・太線は7日中央値"
                        } else {
                            "${measured.size}測定・タップで詳細"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "変化 ${signed(measured.last() - measured.first())} $unit",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveSparkline(
    points: List<TrendPoint>,
    overlayPoints: List<TrendPoint> = emptyList(),
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rawColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    val trendColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.tertiary
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    Canvas(
        modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(points, canvasSize) {
                detectTapGestures { offset ->
                    if (points.isNotEmpty() && canvasSize.width > 0) {
                        val fraction = (offset.x / canvasSize.width).coerceIn(0f, 1f)
                        onSelect((fraction * (points.size - 1)).roundToInt())
                    }
                }
            },
    ) {
        val values = (points + overlayPoints).mapNotNull { it.value }
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val span = (max - min).takeIf { it > 0.0001 } ?: 1.0
        val stepX = if (points.size > 1) size.width / (points.size - 1) else 0f
        fun drawSeries(
            series: List<TrendPoint>,
            color: androidx.compose.ui.graphics.Color,
            strokeWidth: Float,
            showDots: Boolean,
        ) {
            var path = Path()
            var pathPointCount = 0

            fun drawCurrentPath() {
                if (pathPointCount >= 2) {
                    drawPath(
                        path,
                        color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                        ),
                    )
                }
                path = Path()
                pathPointCount = 0
            }

            series.forEachIndexed { index, trendPoint ->
                val value = trendPoint.value
                if (value == null) {
                    drawCurrentPath()
                    return@forEachIndexed
                }
                val chartPoint = Offset(
                    x = stepX * index,
                    y = size.height -
                        (((value - min) / span).toFloat() * size.height * 0.82f) -
                        size.height * 0.09f,
                )
                if (pathPointCount == 0) {
                    path.moveTo(chartPoint.x, chartPoint.y)
                } else {
                    path.lineTo(chartPoint.x, chartPoint.y)
                }
                pathPointCount++
                if (showDots) drawCircle(color = color, radius = 3.5f, center = chartPoint)
            }
            drawCurrentPath()
        }

        drawSeries(points, rawColor, 2.5f, true)
        if (overlayPoints.isNotEmpty()) {
            drawSeries(overlayPoints, trendColor, 5f, false)
        } else {
            drawSeries(points, trendColor, 4f, false)
        }

        selectedIndex?.takeIf { it in points.indices }?.let { index ->
            val x = stepX * index
            drawLine(
                color = selectionColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
            points[index].value?.let { value ->
                val y = size.height -
                    (((value - min) / span).toFloat() * size.height * 0.82f) -
                    size.height * 0.09f
                drawCircle(color = selectionColor, radius = 8f, center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun EmptyCard(title: String, description: String, action: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            action?.let { FilledTonalButton(onClick = it) { Text("同期する") } }
        }
    }
}

@Composable
private fun MetricChangeChip(
    label: String,
    value: Double?,
    unit: String,
    lowerIsBetter: Boolean?,
    modifier: Modifier = Modifier,
) {
    val favorable = lowerIsBetter?.let { lower ->
        value?.let { if (lower) it <= 0 else it >= 0 }
    }
    val containerColor = when (favorable) {
        true -> MaterialTheme.colorScheme.primaryContainer
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (favorable) {
        true -> MaterialTheme.colorScheme.onPrimaryContainer
        false -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor)
            Text(
                value?.let { "${signed(it)} $unit" } ?: "データ無",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun ReportTile(
    title: String,
    mainValue: String,
    subValue: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                Text(title, style = MaterialTheme.typography.labelMedium)
            }
            Text(mainValue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subValue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun compareDiff(current: Long?, previous: Long?): Long? {
    if (current == null || previous == null) return null
    return current - previous
}

@Composable
private fun AdviceBlock(title: String, body: String) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body)
    }
}

@Composable
private fun SmallField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
    )
}

private fun rollingBodyMedian(
    dates: List<LocalDate>,
    allBody: List<BodyCompositionEntity>,
    value: (BodyCompositionEntity) -> Double?,
): List<TrendPoint> {
    val measurements = allBody.mapNotNull { item ->
        value(item)?.let { LocalDate.parse(item.date) to it }
    }
    return dates.map { date ->
        val from = date.minusDays(6)
        TrendPoint(
            date = date,
            value = TrendMath.median(
                measurements.filter { it.first in from..date }.map { it.second },
            ),
        )
    }
}

private fun weightPaceGuidance(rate: Double?): String = when {
    rate == null -> "8回以上かつ14日以上の体重データが必要です"
    rate < 0.0 -> "体重は維持〜増加傾向。まず測定条件と摂取・活動の継続を確認"
    rate < 0.15 -> "ほぼ維持。2週間以上続く場合だけ行動を小さく調整"
    rate < 0.30 -> "ゆっくりした減量。筋力維持を優先するなら許容範囲"
    rate <= 0.70 -> "筋力維持を優先した目安帯（0.3〜0.7%/週）"
    rate <= 1.00 -> "やや速め。筋力・睡眠・除脂肪量傾向を確認"
    else -> "速すぎる可能性。摂取不足や回復不足を確認"
}

private fun leanTrendGuidance(trend: Double?): String = when {
    trend == null -> "判定データ不足"
    trend < -0.15 -> "低下傾向・条件と筋力を確認"
    else -> "明確な低下なし・BIA参考"
}

private fun Double?.signedOrMissing(unit: String): String =
    this?.let { "${signed(it)} $unit" } ?: "未取得"

private val TREND_DATE_FORMAT = DateTimeFormatter.ofPattern("M/d")
private val TREND_DETAIL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d (E)", Locale.JAPAN)

private fun Long.asHoursMinutes(): String = "${this / 60}時間${this % 60}分"
private fun Double?.asDistance(): String = this?.let {
    if (it >= 1_000.0) {
        "${DecimalFormat("0.00").format(it / 1_000.0)} km"
    } else {
        "${it.roundToLong()} m"
    }
} ?: "未取得"
private fun Long.asFileSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "${DecimalFormat("0.#").format(this / 1024.0)} KB"
    else -> "${DecimalFormat("0.#").format(this / (1024.0 * 1024.0))} MB"
}
private fun Double?.kg(): String = this?.let { "${DecimalFormat("0.0").format(it)} kg" } ?: "未取得"
private fun Double?.clean(): String? = this?.let { DecimalFormat("0.##").format(it) }
private fun signed(value: Double): String = if (value >= 0) "+${DecimalFormat("0.##").format(value)}" else DecimalFormat("0.##").format(value)
private fun formatClock(epochMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm")
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
private fun formatInstant(epochMillis: Long): String = DateTimeFormatter.ofPattern("M/d HH:mm")
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
