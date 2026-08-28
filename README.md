# Health Coach

Health Connectに集約された体組成・活動・睡眠・心拍・基礎代謝・食事記録を端末内で分析し、必要なときだけGeminiへ加工済みデータを渡して相談できる、完全個人用のAndroidアプリです。

確定した機能範囲と受入条件は [`docs/MVP_SPEC.md`](docs/MVP_SPEC.md)、設計判断は
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)、KPIの計算と根拠は
[`docs/KPI_DESIGN.md`](docs/KPI_DESIGN.md)、専門家判定の方針は
[`docs/COACH_POLICY.md`](docs/COACH_POLICY.md) にまとめています。

## MVPでできること

- eufy Smart Scale P2 Pro由来の体重・体脂肪率を読取り
- 脂肪量と除脂肪量（体重−脂肪量）の算出
- Xiaomi Smart Band 10 / Mi Fitness由来の歩数・距離・運動・活動消費の読取り
- 睡眠時間・心拍数・基礎代謝・中／高強度アクティビティの読取り
- あすけん由来の摂取カロリー・たんぱく質・脂質・炭水化物の読取り
- NutritionRecordのstart/endから食事回数を数え、食事ごとと1日のPFCをグラフ表示
- 履歴権限がある場合の初回90日バックフィル、通常時の直近28日再集計
- 「今日」画面で当日の体組成・歩数・距離・運動・睡眠・心拍・食事記録を実測／日次値中心に表示
- 7日中央値、28日ロバスト傾向、体重比の週次減量ペース
- Health Connectの「その他」を朝の5分ルーティン（軽い筋トレ＋有酸素）として評価
- 朝トレ実施日数、歩数維持、中強度換算活動、主睡眠、睡眠中心拍のKPI
- 7日／28日／90日トレンドと、体組成グラフへの7日中央値の重ね表示
- トレンド期間の左右スワイプと、グラフタップによる日付・実測値表示
- Health Connectへ実際に届いたデータ型・件数・提供元の診断
- 減量開始日、目標脂肪量、維持する除脂肪量、歩数、朝トレ目標日数の設定
- 自分のGemini APIキーを使った手動週次分析
- Gemini Function Callingで、質問に必要な期間だけ端末内DBから取得する自由質問
- チャットへの写真、PDF、テキスト文書の添付（最大4件・合計12MB）
- 一つの会話履歴（画面・モデル文脈は直近20件）と、習慣・制約に絞った長期メモリーの端末内保存
- 設定画面からの会話メモリー参照
- 会話から確認した習慣を、手動の週次AI分析へ反映
- 端末内KPIを専門家判定・根拠・最大2件の行動へ変換し、Gemini応答後に合成
- 新しいKPIやコーチシグナルをGeminiへ追加送信しないプライバシー境界

アカウント、アプリ用バックエンド、クラウドDB、BigQuery、広告・分析SDKは使用しません。

## 必要な環境

- Android Studio（JDK 17）
- Android SDK 36
- Health Connectを利用できるGalaxy端末
- Mi Fitnessとeufyアプリ、あすけんがHealth Connectへの書込みを許可済みであること
- AI機能を使う場合だけ、Google AI Studioで発行したGemini APIキー

## ビルドと実行

1. Android Studioでこのフォルダを開きます。
2. Gradle Syncを実行します。Gradle Wrapper 8.13、AGP 8.13.0を同梱しています。
3. SDK ManagerでAndroid SDK 36がなければ追加します。
4. Galaxy端末でUSBデバッグを有効化し、`app`構成を実行します。
5. 初回画面でHealth Connect権限を許可します。あすけんの食事記録を使う場合は栄養の読取りも許可します。
6. 同期後、設定の「実機データ診断」でMi Fitness、eufy、あすけんの項目を確認します。

コマンドラインの場合:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

生成APKは `app/build/outputs/apk/debug/app-debug.apk` に出力されます。

## PC接続なしで更新する（GitHub Releases + Obtainium）

初回設定後は、`main`へのマージでテスト・ビルド・署名を行い、
[GitHub Releases](https://github.com/koduki/body-mon/releases)へAPKを自動公開します。
スマホの[Obtainium](https://github.com/ImranR98/Obtainium)にこのリポジトリを登録すると、
更新を検知し、Androidの条件を満たす場合はバックグラウンドでインストールします。
PCとスマホを同じWi-Fiへ接続する必要はありません。

**初回は既存アプリの署名鍵を確認してください。鍵が違うAPKは上書きできません。
会話・目標・APIキーを守るため、アプリをアンインストールしないでください。**

署名鍵・GitHub設定・Obtainium設定の手順は
[`docs/DISTRIBUTION.md`](docs/DISTRIBUTION.md)を参照してください。
配布は`ANDROID_RELEASE_ENABLED=true`にするまで無効です。
アプリ内に更新SDKやクラウドDBは追加していません。

## Geminiの設定

1. Google AI Studioで、この個人アプリ専用のAPIキーを作ります。
2. 利用上限を低く設定します。
3. アプリの設定画面でキーを入力します。
4. 初期モデルは `gemini-3.7-flash` です。モデルIDは設定で変更できます。

APIキーはコードへ埋め込まず、Android Keystoreで暗号化して端末内に保存します。Googleは一般の配布アプリではクライアントへAPIキーを置かずバックエンドを利用するよう推奨しています。この実装は、本人が自分の端末だけで自分のキーを使う前提です。第三者へ配布する場合はGemini呼び出しを認証付きバックエンドへ移してください。

## データの流れ

```text
eufy / Mi Fitness / あすけん
        ↓
Health Connect（生データの正本）
        ↓
初回90日／通常28日再集計 → Room（日別集計・運動・KPI週報・目標・会話）
        ↓                         ↓
Compose UI               Gemini Function Calling
                                  ↓
                       必要な期間の加工済みJSONのみ
```

歩数・距離・活動消費・心拍・基礎代謝・活動強度はHealth Connectの集計APIを使い、生レコードを単純加算しません。睡眠は起床日に終了した最長セッションを主睡眠として扱い、その区間の心拍を睡眠中心拍にします。体重と体脂肪率は10分以内の測定だけを組み合わせます。除脂肪量はデータ元による定義差を避けるため、常に体重−脂肪量で計算します。あすけんがHealth Connectへ書き出す栄養素は摂取カロリーとPFCに限られるため、ビタミンや食塩相当量は取得しません。

Health Connectの運動種別が`Other Workout`のセッションは、本人の朝の5分ルーティン
として識別します。記録された実時間を軽い筋トレ相当の朝トレ時間として残し、同じ
実時間を有酸素時間にも含めます。週次の習慣KPIは一般的な筋トレ日数ではなく、この
セッションが記録された日数です。

## 自動処理

- 初回同期: 履歴権限が許可されていれば最大90日をバックフィル
- アプリ起動時・手動更新時: 以後は直近28日を再集計
- バックグラウンド権限が許可された場合: WorkManagerでおおむね1日1回再集計
- 数値週報: 同期時に端末内で自動更新
- Gemini分析: 必ず利用者がボタンを押したときだけ実行

Androidの省電力制御によりWorkManagerの実行時刻は厳密ではありません。バックグラウンド権限を許可しない場合も、アプリ起動時・手動同期は利用できます。

## プライバシー上の制約

- Room、APIキー、会話はバックアップ・端末移行の対象外です。
- アプリ内削除で集計・目標・会話・APIキーを消去できます。
- Health Connect内の原データはアプリ内削除では消しません。
- Geminiへ氏名、メールアドレス、Health ConnectのレコードIDは送りません。
- 添付本体はRoomへ保存せず、選択したファイルをチャット送信時にだけGeminiへ送ります。
  会話履歴にはファイル名だけを残し、添付本体は次のターンへ再送しません。
- 食事記録がある日は摂取カロリーとPFCを観測値として扱います。欠測日を0kcalとせず、消費カロリーから摂取量や赤字量を逆算しません。
- 医療診断、疾患推測、服薬指示を目的としません。

## プロジェクト構成

- `data/health`: Health Connect読取り・集計・実機診断
- `data/db`: RoomエンティティとDAO
- `data/llm`: Gemini REST、Function Calling、ローカル健康ツール
- `data/security`: Android KeystoreによるAPIキー保護
- `domain`: 体組成計算・週次レポート・端末内の専門コーチ判定
- `ui`: Compose画面とViewModel
- `work`: 日次WorkManager

## 現在の制約

- 骨格筋量はHealth Connect標準型ではないため、計算した除脂肪量を明記して代用します。
- アクティビティ強度は対応するHealth Connect機能がある端末でのみ取得します。
- eufyアプリ内の16指標すべてがHealth Connectへ出るとは限りません。
- あすけんがHealth Connectへ書き出す栄養素は摂取カロリーとPFCに限られます。
- 食事回数は`NutritionRecord`のstart/endクラスターから数えます。同期時刻からは数えません。
- 初回バックフィル後、28日より古いHealth Connect修正は自動反映しません。
- Health Connectで`Other Workout`として記録されない朝トレは継続日数へ反映されません。
- 添付は画像（JPEG / PNG / WebP / HEIC / HEIF / BMP）、PDF、対応するテキスト形式に限り、
  1ファイル10MB・最大4件・合計12MBです。添付を直接参照する追加質問では再添付が必要です。
- アプリ削除・機種変更時に目標とAI会話は復元されません。
