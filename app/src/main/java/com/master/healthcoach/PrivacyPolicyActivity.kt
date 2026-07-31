package com.master.healthcoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.master.healthcoach.ui.theme.HealthCoachTheme

class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthCoachTheme {
                PrivacyPolicy(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun PrivacyPolicy(onClose: () -> Unit) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "健康データの取扱い",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            item {
                PolicySection(
                    "利用目的",
                    "体組成、歩数、距離、運動セッション、活動消費カロリー、睡眠、心拍、" +
                        "基礎代謝、活動強度を、本人向けの推移表示・KPI集計・健康相談にのみ" +
                        "使用します。",
                )
            }
            item {
                PolicySection(
                    "保存場所",
                    "Health Connectを原データの正本とし、表示用の日別集計、目標、週報、" +
                        "AI会話をこの端末内に保存します。アカウントやアプリ用クラウドDBはありません。",
                )
            }
            item {
                PolicySection(
                    "Geminiへの送信",
                    "AI分析またはチャットを本人が操作した時だけ、質問に必要な期間の加工済みデータを" +
                        "Gemini APIへ送信します。氏名、メールアドレス、位置情報、Health Connectの" +
                        "内部レコードIDは送りません。",
                )
            }
            item {
                PolicySection(
                    "削除",
                    "設定から、端末内の集計、目標、週報、会話、Gemini APIキーを削除できます。" +
                        "この操作ではHealth Connect内の原データは削除しません。",
                )
            }
            item {
                Text(
                    "本アプリは医療診断、疾病の推測、服薬指示を目的としません。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("閉じる")
                }
            }
        }
    }
}

@Composable
private fun PolicySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
