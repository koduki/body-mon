package com.master.healthcoach.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@Composable
fun ChatScreen(
    state: MainUiState,
    onSendChat: (String) -> Unit,
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onStopChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var submittedText by rememberSaveable { mutableStateOf("") }
    var copiedId by remember { mutableStateOf<Long?>(null) }
    var followLatest by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onAddAttachments)
    val nearBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val itemCount = (if (state.messages.isEmpty()) 1 else state.messages.size) +
        (if (state.isChatGenerating) 1 else 0) + (if (state.chatError != null) 1 else 0)
    suspend fun scrollToLatest() {
        autoScrolling = true
        try { listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0)) } finally { autoScrolling = false }
        followLatest = true
    }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }
            .collect { if (it.third && !autoScrolling) followLatest = !listState.canScrollForward }
    }
    LaunchedEffect(state.messages.lastOrNull()?.id, state.isChatGenerating, state.chatError) {
        if (followLatest) { withFrameNanos { }; scrollToLatest() }
        if (state.chatError != null && input.isBlank()) input = submittedText
    }
    LaunchedEffect(copiedId) { if (copiedId != null) { delay(1800); copiedId = null } }
    fun submit() {
        if (!state.apiKeyConfigured || state.isSending || state.isAddingAttachments || (input.isBlank() && state.chatAttachments.isEmpty())) return
        submittedText = input
        followLatest = true
        onSendChat(input)
        input = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (!state.apiKeyConfigured) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AIコーチに接続するには設定が必要です", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenSettings) { Text("設定を開く") }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)) {
                if (state.messages.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("今日はどんな相談をしますか？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("体の変化、食事、運動、睡眠について一緒に確認しましょう。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf("最近の体重の傾向を教えて", "摂取カロリーとPFCを確認したい", "今日の運動と睡眠を振り返りたい").forEach { prompt ->
                            Surface(onClick = { input = prompt }, shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                                Text(prompt, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    val isUser = message.role == "user"
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                        if (isUser) {
                            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(0.88f)) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SelectionContainer { Text(message.content, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)) }
                                    message.attachmentNames?.lineSequence()?.filter(String::isNotBlank)?.forEach { name ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.AttachFile, null, Modifier.size(16.dp))
                                            Text(name, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }
                            }
                        } else {
                            SelectionContainer { ChatMarkdownBody(message.content) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(message.content)); copiedId = message.id }) {
                                Icon(if (copiedId == message.id) Icons.Default.Check else Icons.Default.ContentCopy,
                                    if (copiedId == message.id) "コピーしました" else "メッセージをコピー", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isUser) IconButton(onClick = { input = message.content }) {
                                Icon(Icons.Default.Edit, "入力欄へコピー", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (state.isChatGenerating) item {
                    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("回答を作成しています", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.chatError?.let { error -> item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("回答を取得できませんでした", fontWeight = FontWeight.SemiBold)
                            Text(error, style = MaterialTheme.typography.bodySmall)
                            Text("入力欄の内容を確認して再送できます。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
            }
            if (!nearBottom && state.messages.isNotEmpty()) {
                FilledTonalIconButton(onClick = { scope.launch { scrollToLatest() } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                    Icon(Icons.Default.ArrowDownward, "最新のメッセージへ")
                }
            }
        }
        Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(8.dp)) {
                if (state.chatAttachments.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.chatAttachments, key = { it.id }) { file ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (file.mimeType.startsWith("image/")) Icons.Default.Image else Icons.Default.Description, null, Modifier.size(20.dp))
                                    Text(file.displayName, Modifier.padding(horizontal = 8.dp).widthIn(max = 160.dp), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                    IconButton(onClick = { onRemoveAttachment(file.id) }, enabled = !state.isSending) {
                                        Icon(Icons.Default.Close, "${file.displayName}の添付を外す", Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                TextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("健康コーチにメッセージ") }, minLines = 1, maxLines = 6,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    textStyle = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf", "text/*", "application/json")) },
                        enabled = state.apiKeyConfigured && !state.isSending && !state.isAddingAttachments) {
                        if (state.isAddingAttachments) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Add, "写真・ファイルを添付")
                    }
                    if (state.isChatGenerating) {
                        FilledIconButton(onClick = onStopChat, shape = CircleShape, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Stop, "回答の生成を停止")
                        }
                    } else {
                        FilledIconButton(onClick = ::submit, shape = CircleShape, modifier = Modifier.size(48.dp),
                            enabled = state.apiKeyConfigured && !state.isSending && !state.isAddingAttachments &&
                                (input.isNotBlank() || state.chatAttachments.isNotEmpty())) {
                            Icon(Icons.Default.ArrowUpward, "送信")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMarkdownBody(content: String) {
    val blocks = remember(content) { ChatMarkdown.parse(content) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block -> when (block) {
            is ChatBlock.Paragraph -> Text(chatInline(block.text), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
            is ChatBlock.Heading -> Text(chatInline(block.text), fontWeight = FontWeight.SemiBold,
                style = when (block.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium })
            is ChatBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(block.marker, Modifier.width(26.dp), style = MaterialTheme.typography.bodyLarge)
                Text(chatInline(block.text), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
            }
            is ChatBlock.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                Text(chatInline(block.text), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
            }
            is ChatBlock.Code -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (block.language.isNotBlank()) Text(block.language, style = MaterialTheme.typography.labelSmall)
                    Text(block.text, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is ChatBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                block.rows.forEachIndexed { index, cells ->
                    Row(Modifier.background(if (index == 0) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)) {
                        cells.forEach { cell -> Text(chatInline(cell), Modifier.width(164.dp).padding(10.dp),
                            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.bodyMedium) }
                    }
                    HorizontalDivider()
                }
            }
        } }
    }
}

private fun chatInline(text: String): AnnotatedString = buildAnnotatedString {
    var start = 0
    Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`").findAll(text).forEach { match ->
        append(text.substring(start, match.range.first))
        if (match.groupValues[1].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
        else withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(match.groupValues[2]) }
        start = match.range.last + 1
    }
    append(text.substring(start))
}
