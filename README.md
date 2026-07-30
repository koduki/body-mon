# Health Coach

Health Connectに集約された体組成・歩数・運動・活動消費カロリーを端末内で分析し、必要なときだけGeminiへ加工済みデータを渡して相談できる、完全個人用のAndroidアプリです。

確定した機能範囲と受入条件は [`docs/MVP_SPEC.md`](docs/MVP_SPEC.md)、設計判断は
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) にまとめています。

## MVPでできること

- eufy Smart Scale P2 Pro由来の体重・体脂肪率・除脂肪量を読取り
- 脂肪量の算出と、除脂肪量を使った筋肉維持の参考表示
- Xiaomi Smart Band 10 / Mi Fitness由来の歩数・距離・運動・活動消費の読取り
- 直近28日の端末内集計、7日週報、28日トレンド
- Health Connectへ実際に届いたデータ型・件数・提供元の診断
- 目標脂肪量、維持する除脂肪量、歩数、運動回数、活動消費の設定
- 自分のGemini APIキーを使った手動週次分析
- Gemini Function Callingで、質問に必要な期間だけ端末内DBから取得する自由質問
- 一つの会話履歴と、長期メモリー要約の端末内保存

アカウント、アプリ用バックエンド、クラウドDB、BigQuery、広告・分析SDKは使用しません。

## 必要な環境

- Android Studio（JDK 17）
- Android SDK 36
- Health Connectを利用できるGalaxy端末
- Mi FitnessとeufyアプリがHealth Connectへの書込みを許可済みであること
- AI機能を使う場合だけ、Google AI Studioで発行したGemini APIキー

## ビルドと実行

1. Android Studioでこのフォルダを開きます。
2. Gradle Syncを実行します。Gradle Wrapper 8.13、AGP 8.13.0を同梱しています。
3. SDK ManagerでAndroid SDK 36がなければ追加します。
4. Galaxy端末でUSBデバッグを有効化し、`app`構成を実行します。
5. 初回画面でHealth Connect権限を許可します。
6. 同期後、設定の「実機データ診断」でMi Fitnessとeufyの項目を確認します。

コマンドラインの場合:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

生成APKは `app/build/outputs/apk/debug/app-debug.apk` に出力されます。

## Geminiの設定

1. Google AI Studioで、この個人アプリ専用のAPIキーを作ります。
2. 利用上限を低く設定します。
3. アプリの設定画面でキーを入力します。
4. 初期モデルは `gemini-3.6-flash` です。モデルIDは設定で変更できます。

APIキーはコードへ埋め込まず、Android Keystoreで暗号化して端末内に保存します。Googleは一般の配布アプリではクライアントへAPIキーを置かずバックエンドを利用するよう推奨しています。この実装は、本人が自分の端末だけで自分のキーを使う前提です。第三者へ配布する場合はGemini呼出しを認証付きバックエンドへ移してください。

## データの流れ

```text
eufy / Mi Fitness
        ↓
Health Connect（生データの正本）
        ↓
28日再集計 → Room（日別集計・運動・週報・目標・会話）
        ↓                         ↓
Compose UI               Gemini Function Calling
                                  ↓
                       必要な期間の加工済みJSONのみ
```

歩数・距離・活動消費はHealth Connectの集計APIを使い、生レコードを単純加算しません。体重と体脂肪率は10分以内の測定だけを組み合わせます。Health Connectから除脂肪量が取得できれば優先し、なければ体重−脂肪量を推定値として表示します。

## 自動処理

- アプリ起動時・手動更新時: 直近28日を再集計
- バックグラウンド権限が許可された場合: WorkManagerでおおむね1日1回再集計
- 数値週報: 同期時に端末内で自動更新
- Gemini分析: 必ず利用者がボタンを押したときだけ実行

Androidの省電力制御によりWorkManagerの実行時刻は厳密ではありません。バックグラウンド権限を許可しない場合も、アプリ起動時・手動同期は利用できます。

## プライバシー上の制約

- Room、APIキー、会話はバックアップ・端末移行の対象外です。
- アプリ内削除で集計・目標・会話・APIキーを消去できます。
- Health Connect内の原データはアプリ内削除では消しません。
- Geminiへ氏名、メールアドレス、Health ConnectのレコードIDは送りません。
- 食事記録がないため、AIは一般的な食事改善だけを提案し、摂取カロリーやカロリー赤字を断定しません。
- 医療診断、疾病推測、服薬指示を目的としません。

## プロジェクト構成

- `data/health`: Health Connect読取り・集計・実機診断
- `data/db`: RoomエンティティとDAO
- `data/llm`: Gemini REST、Function Calling、ローカル健康ツール
- `data/security`: Android KeystoreによるAPIキー保護
- `domain`: 体組成計算・週次レポート
- `ui`: Compose画面とViewModel
- `work`: 日次WorkManager

## 現在の制約

- 骨格筋量はHealth Connect標準型ではないため、除脂肪量を明記して代用します。
- eufyアプリ内の16指標すべてがHealth Connectへ出るとは限りません。
- 端末内DBは28日分の再取得範囲より古いHealth Connect修正を自動反映しません。
- アプリ削除・機種変更時に目標とAI会話は復元されません。
