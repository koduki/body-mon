package com.master.healthcoach.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.master.healthcoach.HealthCoachApplication
import com.master.healthcoach.data.mcp.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TunnelSettingsCard() {
    val context = LocalContext.current
    val container = (context.applicationContext as HealthCoachApplication).container
    val settings = container.tunnelSettings
    val state by TunnelService.state.collectAsState()
    var id by remember { mutableStateOf(settings.id()) }
    var key by remember { mutableStateOf("") } // credentials are never saved into SavedState/Bundle
    var organization by remember { mutableStateOf(settings.organization()) }
    var minutes by remember { mutableIntStateOf(settings.minutes()) }
    var shares by remember { mutableStateOf(settings.permissions()) }
    var detailed by remember { mutableStateOf(container.diagnostics.detailed) }
    var capture by remember { mutableStateOf(container.diagnostics.enabled) }
    var message by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(settings.hasKey()) }
    var lastSync by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.status) { lastSync = container.database.dao().metadata("lastSyncSuccess") }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) TunnelService.start(context) else message = "接続状態と停止ボタンを表示するため通知を許可してください"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ChatGPT連携", style = MaterialTheme.typography.titleLarge)
            Text("開始した時間だけ接続します。共有した情報はChatGPTへ送られます。初回はOpenAI Platformでトンネルを作成し、ChatGPTのカスタムMCPでそのトンネルを登録してください。")
            OutlinedTextField(id, { id = it }, label = { Text("トンネルID") }, enabled = !state.running, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(key, { key = it }, label = { Text(if (hasKey) "実行用キー（保存済み・変更時のみ入力）" else "実行用キー") }, visualTransformation = PasswordVisualTransformation(), enabled = !state.running, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(organization, { organization = it }, label = { Text("組織ID（任意）") }, enabled = !state.running, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("共有する情報")
            ShareRow("健康メトリクス・週報・プロフィール", shares.health, !state.running) { shares = shares.copy(health = it, evidence = shares.evidence && it) }
            ShareRow("会話履歴・メモリー", shares.conversations, !state.running) { shares = shares.copy(conversations = it, evidence = shares.evidence && it) }
            ShareRow("アプリ診断・操作と処理の記録", shares.diagnostics, !state.running) { shares = shares.copy(diagnostics = it) }
            ShareRow("回答・計算の詳細根拠（健康情報と会話を含む）", shares.evidence, !state.running && shares.health && shares.conversations) { shares = shares.copy(evidence = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { duration -> FilterChip(selected = duration == minutes, onClick = { minutes = duration }, enabled = !state.running, label = { Text("${duration}分") }) }
            }
            Text("最終同期: " + (lastSync?.toLongOrNull()?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM/dd HH:mm")) } ?: "未記録（必要なら画面上部の同期を実行）"))
            Text(when (state.status) {
                "starting" -> "接続準備中"
                "connected" -> "接続中。ChatGPTに切り替えて利用できます"
                "reconnecting" -> "回線を再接続中"
                "auth" -> "認証に失敗しました。キーとトンネルの利用権限を確認してください"
                "start_failed" -> "開始できませんでした。設定・通知・Androidの実行制限を確認してください"
                "failure" -> "接続に失敗しました。設定と回線を確認して再度開始してください"
                "expired" -> "接続期限で終了しました"
                "locked" -> "画面ロックで終了しました"
                "os_timeout" -> "Androidの実行時間制限で終了しました"
                else -> "停止中"
            })
            if (state.running) Text("終了時刻: " + Instant.ofEpochMilli(state.expiresAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.running, onClick = {
                    runCatching { settings.save(id, key, organization, minutes, shares) }.onSuccess {
                        key = ""; hasKey = true; message = ""
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else TunnelService.start(context)
                    }.onFailure { message = "ID・キー・組織IDの入力を確認してください" }
                }) { Text("接続を開始") }
                OutlinedButton(enabled = state.running, onClick = { TunnelService.stop(context) }) { Text("停止") }
            }
            TextButton(onClick = { TunnelService.stop(context); settings.clear(); id = ""; key = ""; organization = ""; hasKey = false; message = "接続設定を削除しました" }) { Text("連携設定を削除") }
            HorizontalDivider()
            Text("端末内の診断記録", style = MaterialTheme.typography.titleMedium)
            ShareRow("処理時間・結果・操作種別を記録", capture, true) { capture = it; container.diagnostics.enabled = it }
            ShareRow("回答時の入力文脈を詳細記録（健康情報・会話を含む）", detailed, capture) { detailed = it; container.diagnostics.detailed = it }
            Text("通常の診断に会話本文やキーは記録しません。詳細入力は暗号化し最大7日保持します。停止すると新しい記録を作成しません。")
            TextButton(onClick = { scope.launch { runCatching { TunnelService.stopAndAwait(context); container.diagnostics.clear() }.onSuccess { message = "診断記録を削除しました" }.onFailure { message = "接続停止後にもう一度削除してください" } } }) { Text("診断記録を削除") }
            if (message.isNotEmpty()) Text(message)
        }
    }
}

@Composable
private fun ShareRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp)); Switch(checked, onChange, enabled = enabled)
    }
}
