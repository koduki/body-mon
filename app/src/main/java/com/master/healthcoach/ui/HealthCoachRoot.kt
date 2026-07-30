package com.master.healthcoach.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.master.healthcoach.data.db.BodyCompositionEntity
import com.master.healthcoach.data.db.DailyHealthSummaryEntity
import com.master.healthcoach.data.db.GoalEntity
import com.master.healthcoach.data.health.HealthConnectAvailability
import java.text.DecimalFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private enum class AppTab(val label: String, val icon: ImageVector) {
    TODAY("今日", Icons.Default.Home),
    TRENDS("推移", Icons.Default.ShowChart),
    WEEKLY("週報", Icons.Default.AutoAwesome),
    CHAT("相談", Icons.Default.Chat),
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
                    AppTab.CHAT -> ChatScreen(state, onSendChat)
                    AppTab.SETTINGS -> SettingsScreen(
                        state = state,
                        onSaveGoal = onSaveGoal,
                        onSaveApiKey = onSaveApiKey,
                        onClearApiKey = onClearApiKey,
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
            "体組成・歩数・運動・活動消費をHealth Connectから読み取ります。" +
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
    val latestBody = state.body.firstOrNull()
    val lastSeven = state.daily.take(7)
    val steps = lastSeven.mapNotNull { it.steps }.averageOrNull()?.roundToLong()
    val calories = lastSeven.mapNotNull { it.activeCaloriesKcal }.averageOrNull()
    val sessions = lastSeven.sumOf { it.exerciseSessionCount }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader("今の状態", "体重より、脂肪量と除脂肪量を中心に見ます")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "脂肪量",
                    latestBody?.fatMassKg.kg(),
                    "目標 ${state.goal?.targetFatMassKg.kg()}",
                    Modifier.weight(1f),
                )
                MetricCard(
                    "除脂肪量",
                    latestBody?.leanBodyMassKg.kg(),
                    if (latestBody?.leanMassSource == "calculated") "推定値" else "筋肉維持の参考",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard(
                    "平均歩数",
                    steps?.let { "%,d".format(Locale.JAPAN, it) } ?: "未取得",
                    "直近7日",
                    Modifier.weight(1f),
                    Icons.Default.DirectionsWalk,
                )
                MetricCard(
                    "活動消費",
                    calories?.let { "${it.roundToLong()} kcal" } ?: "未取得",
                    "1日平均",
                    Modifier.weight(1f),
                    Icons.Default.FitnessCenter,
                )
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("今週の運動", style = MaterialTheme.typography.labelLarge)
                        Text("${sessions}回・${lastSeven.sumOf { it.exerciseMinutes }}分", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "筋トレ ${lastSeven.sumOf { it.strengthMinutes }}分 / 有酸素 ${lastSeven.sumOf { it.cardioMinutes }}分",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.Default.FitnessCenter, null, Modifier.size(36.dp))
                }
            }
        }
        item { SectionHeader("直近7日", "日々の数字はぶれても、傾向を見ます") }
        items(lastSeven) { day -> DailyRow(day) }
        if (state.daily.isEmpty()) {
            item { EmptyCard("まだ集計データがありません", "同期するとHealth Connectから直近28日を読み取ります", onSync) }
        }
    }
}

@Composable
private fun TrendsScreen(state: MainUiState) {
    val body = state.body.take(28).reversed()
    val daily = state.daily.take(28).reversed()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionHeader("28日トレンド", "欠損日は線から除外しています") }
        item { TrendCard("脂肪量", "kg", body.mapNotNull { it.fatMassKg }) }
        item { TrendCard("除脂肪量", "kg", body.mapNotNull { it.leanBodyMassKg }) }
        item { TrendCard("歩数", "歩", daily.mapNotNull { it.steps?.toDouble() }) }
        item { TrendCard("活動消費", "kcal", daily.mapNotNull { it.activeCaloriesKcal }) }
        item { SectionHeader("最近の運動", "Health Connectの運動セッション") }
        if (state.exerciseSessions.isEmpty()) {
            item { EmptyCard("運動セッションは未取得です", "Mi FitnessのHealth Connect共有設定を確認してください") }
        } else {
            items(state.exerciseSessions.take(12), key = { it.recordId }) { session ->
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
                "除脂肪量は筋肉量そのものではなく、水分・骨などを含む筋肉維持の参考指標です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeeklyScreen(state: MainUiState, onAnalyzeWeek: () -> Unit) {
    val report = state.weekly
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("自動数値レポート", "Gemini分析はボタンを押した時だけ実行します") }
        if (report == null) {
            item { EmptyCard("週報はまだありません", "Health Connectを同期すると自動作成されます") }
        } else {
            item {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${report.weekStart} 〜 ${report.weekEnd}", fontWeight = FontWeight.SemiBold)
                        ChangeRow("脂肪量", report.fatMassChangeKg, "kg", lowerIsBetter = true)
                        ChangeRow("除脂肪量", report.leanMassChangeKg, "kg", lowerIsBetter = false)
                        ChangeRow("体重", report.weightChangeKg, "kg", lowerIsBetter = true)
                        HorizontalDivider()
                        Text("平均歩数 ${report.stepsDailyAverage?.let { "%,d".format(it) } ?: "未取得"}")
                        Text("平均活動消費 ${report.activeCaloriesDailyAverage?.roundToLong() ?: "未取得"} kcal")
                        Text("運動 ${report.exerciseSessions}回・${report.exerciseMinutes}分")
                    }
                }
            }
            if (report.dataLimitations.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("データの注意", fontWeight = FontWeight.SemiBold)
                            report.dataLimitations.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = onAnalyzeWeek,
                    enabled = state.apiKeyConfigured && !state.isSending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isSending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.apiKeyConfigured) "Geminiで今週を分析" else "設定でAPIキーを登録してください")
                }
            }
        }
        state.weeklyAdvice?.let { advice ->
            item { SectionHeader("AI分析", "加工済み週次データだけを送信しました") }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(advice.summary, style = MaterialTheme.typography.bodyLarge)
                        advice.positiveChange?.let { AdviceBlock("よかった変化", it) }
                        advice.caution?.let { AdviceBlock("注意点", it) }
                        if (advice.nextActions.isNotEmpty()) {
                            Text("次の一手", fontWeight = FontWeight.SemiBold)
                            advice.nextActions.forEachIndexed { index, action ->
                                Text("${index + 1}. $action")
                            }
                        }
                        Text("確からしさ: ${advice.confidence}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(state: MainUiState, onSendChat: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
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
                        "健康データについて質問できます",
                        "例：先月、脂肪量と活動量はどう変わった？\n必要な期間だけ端末側で集計してGeminiへ渡します。",
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
                        Text(message.content)
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("健康データについて質問") },
                maxLines = 4,
            )
            Button(
                onClick = {
                    onSendChat(input)
                    input = ""
                },
                enabled = input.isNotBlank() && state.apiKeyConfigured && !state.isSending,
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
    onOpenHealthConnect: () -> Unit,
    onClearLocalData: () -> Unit,
) {
    var age by rememberSaveable(state.goal) { mutableStateOf(state.goal?.age?.toString().orEmpty()) }
    var height by rememberSaveable(state.goal) { mutableStateOf(state.goal?.heightCm.clean().orEmpty()) }
    var sex by rememberSaveable(state.goal) { mutableStateOf(state.goal?.sex.orEmpty()) }
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
        SmallField(deadline, { deadline = it }, "目標期限 YYYY-MM-DD", Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(fatTarget, { fatTarget = it }, "目標脂肪量 kg", Modifier.weight(1f), KeyboardType.Decimal)
            SmallField(leanMinimum, { leanMinimum = it }, "維持する除脂肪量 kg", Modifier.weight(1f), KeyboardType.Decimal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallField(steps, { steps = it }, "1日歩数", Modifier.weight(1f), KeyboardType.Number)
            SmallField(sessions, { sessions = it }, "週の運動回数", Modifier.weight(1f), KeyboardType.Number)
        }
        SmallField(calories, { calories = it }, "1日の活動消費目標 kcal", Modifier.fillMaxWidth(), KeyboardType.Decimal)
        Button(
            onClick = {
                onSaveGoal(
                    GoalEntity(
                        age = age.toIntOrNull(),
                        heightCm = height.toDoubleOrNull(),
                        sex = sex.ifBlank { null },
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
                    }
                    Icon(
                        if (source.status == "取得可能") Icons.Default.CheckCircle else Icons.Default.Warning,
                        null,
                        tint = if (source.status == "取得可能") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (state.sources.isEmpty()) Text("同期後に診断結果が表示されます。")
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

@Composable
private fun DailyRow(day: DailyHealthSummaryEntity) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(day.date.substring(5), fontWeight = FontWeight.Medium)
        Column(horizontalAlignment = Alignment.End) {
            Text("${day.steps?.let { "%,d歩".format(it) } ?: "歩数未取得"}  ${day.activeCaloriesKcal?.roundToLong()?.let { "$it kcal" } ?: ""}")
            if (day.exerciseMinutes > 0) Text("運動 ${day.exerciseMinutes}分", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TrendCard(title: String, unit: String, values: List<Double>) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(values.lastOrNull()?.let { "${DecimalFormat("0.#").format(it)} $unit" } ?: "未取得")
            }
            Spacer(Modifier.height(12.dp))
            if (values.size < 2) {
                Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.Center) {
                    Text("表示に十分なデータがありません", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Sparkline(values, Modifier.fillMaxWidth().height(92.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${values.size}測定", style = MaterialTheme.typography.labelSmall)
                    Text("変化 ${signed(values.last() - values.first())} $unit", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val span = (max - min).takeIf { it > 0.0001 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val point = Offset(
                x = stepX * index,
                y = size.height - (((value - min) / span).toFloat() * size.height * 0.82f) - size.height * 0.09f,
            )
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = StrokeCap.Round))
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
private fun ChangeRow(label: String, value: Double?, unit: String, lowerIsBetter: Boolean) {
    val favorable = value?.let { if (lowerIsBetter) it <= 0 else it >= 0 }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(
            value?.let { "${signed(it)} $unit" } ?: "データ不足",
            color = when (favorable) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
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

private fun List<Number>.averageOrNull(): Double? =
    if (isEmpty()) null else sumOf { it.toDouble() } / size

private fun Double?.kg(): String = this?.let { "${DecimalFormat("0.0").format(it)} kg" } ?: "未取得"
private fun Double?.clean(): String? = this?.let { DecimalFormat("0.##").format(it) }
private fun signed(value: Double): String = if (value >= 0) "+${DecimalFormat("0.##").format(value)}" else DecimalFormat("0.##").format(value)
private fun formatInstant(epochMillis: Long): String = DateTimeFormatter.ofPattern("M/d HH:mm")
    .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
