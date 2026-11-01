# 活動消費の独立補助推定（activity-hr-v1）

**目的**: Mi Fitness/Xiaomi が書き出す `ActiveCaloriesBurnedRecord` を置き換えず、歩数・運動セッション・心拍サンプルから **独立** に算出した参考値と比較する。日次・セッション別結果を端末Roomに保存し、画面、週報JSON、アプリ内AI、MCPで同じデータを使う。

## 計算

- `E_walk = steps_outside_workouts × (height_cm × stride_ratio / 100 / 1000) × weight_kg × 0.5` kcal。stride_ratio（男性0.415、女性0.413、不明0.414）。平地歩行の**安静時を除く**参考コスト。セッションと重なるステップ区間を時間割合で控除し、重複カウントしない。
- `E_session = Σ[ max(0, MET_gross × HR_factor − 1) × weight_kg × duration_hours ]` kcal。重なるセッションでは同時間帯の最大MET種別を一度だけ数える。
- `E_assist = E_walk + E_session`、比較値は `Xiaomi_active`。差は `Xiaomi_active − E_assist`。両者の小/大は**比較レンジ**であり統計的信頼区間や校正済み真値ではない。
- 日次歩数合計が取得できない、時間帯を特定する歩数記録がなく運動重複を控除できない、体重・身長がない場合、補助推定は `null`。残る既存歩数モデルを比較参考として使う。
- 一般運動（`OTHER`）は内容不明なので4.0 gross METの暫定仮定を使い、筋トレだと断定しない。

### gross MET（代表仮定）

| 種類 | gross MET |
|---|---:|
| Walking | 3.5 |
| Running | 8.3 |
| Cycling | 5.5 |
| Strength | 5.0 |
| Morning routine / unknown | 4.0 |
| Hiking / swimming / rowing | 6.0 |
| HIIT | 8.0 |

出典: [2024 Adult Compendium of Physical Activities](https://pacompendium.com/adult-compendium/)（種目ごとの代表的な強度。個人の実測ではない）。

### 心拍補正（限定的な感度分析）

- Health Connect の `HeartRateRecord.samples` を**運動時間帯だけ**に紐付ける。安静時や日常心拍、睡眠中心拍をこれで推定しない。
- 40〜220bpmのサンプルを分単位で集計。各運動区間で「2分以上かつ時間の50%以上」に有効な心拍サンプルがあるときだけ使用。
- 推定最大心拍 `HRmax = 208 − 0.7 × 年齢`（18〜90歳のみ）。`HR_factor = clamp(1 + (平均運動時HR / HRmax − 0.70), 0.85, 1.15)`。十分な心拍または年齢がない場合は1.0。
- HR補正は適当な回帰式で個別に校正された消費量ではなく、活動種別METに対する**上限±15%の感度調整**。最大心拍の推定誤差や薬剤、暑熱、脱水、運動強度以外による心拍上昇には十分に対応できない。
- 5ゾーン（HRmaxの60/70/80/90%で区切る）の観測分数をセッションと日別に保存。未観測区間のゾーンを埋めない。

## UI / API

- 今日画面: Xiaomi活動消費、補助推定、心拍補正適用分、参考比較レンジ。
- 週報: `assistedActiveCaloriesDailyAverage` / `assistedActiveValidDays` / `assistedHeartObservedMinutes` / `assistedHeartAdjustedMinutes`。
- 週報JSON v3: 日別の補助推定・内訳・心拍ゾーン、アクティビティ別補助推定と観測ゾーン（`assistedZoneMinutesJson`は配列のJSON文字列）、データ品質にモデル説明とカバレッジ。
- MCP: 既存 `get_health_metrics(metric="activity"|"exercise")` に同じ情報を追加。アプリ内Geminiの週次契約にも補助推定と観測カバレッジを追加。
- データは通常31日分を再同期し、Room migration 10→11を適用する。アプリ側で同期しない限り、MCPは古いキャッシュのまま。

## 解釈上の制約

Xiaomiは未記録の活動も推定する可能性があり、逆に補助推定は階段、坂道、非記録活動、運動種別誤判定、運動強度、電動アシストなどに依存して変動する。**Xiaomiが大きいだけで過大評価とは言えない**。両者を平均して摂取カロリー・エネルギー赤字・減量指示を決めない。将来、十分な食事記録と体重推移が揃った場合のみ、期間統計でどちらが整合しやすいかを検証する。
