# 検証記録

## この環境で完了した確認

- AndroidManifestと全リソースXMLの構文解析
- Gradle Wrapperシェルスクリプトの構文確認
- APIキーらしき固定文字列がソースに含まれないことの走査
- TODO、競合マーカー、旧35日設定が確定仕様側に残っていないことの走査
- 体組成計算と週次集計のJUnitテストコード作成

## この環境では実行できなかった確認

Android SDKとGradle本体がなく、Gradle配布サーバーへのネットワーク接続も許可されていないため、次は未実行。

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

## Galaxy実機での確認手順

1. Android Studioでプロジェクトを開き、Android SDK 36を導入する。
2. Gradle Sync後に `testDebugUnitTest` を実行する。
3. `assembleDebug` またはAndroid StudioのRunでGalaxyへインストールする。
4. Health Connectの体組成、歩数、距離、運動、活動消費、バックグラウンド読取を許可する。
5. 同期し、「実機データ診断」でeufyとMi Fitnessのパッケージ名・件数を確認する。
6. 脂肪量が `体重 × 体脂肪率 ÷ 100` と一致することを数件確認する。
7. Health Connectに除脂肪量がある日とない日の表示・出典を確認する。
8. 今日、28日推移、週報、運動一覧を確認する。
9. 専用のGemini APIキーを設定し、週次分析と複数指標を含む自由質問を各1回試す。
10. 機内モードではローカル画面が使え、AI操作だけが失敗することを確認する。
11. アプリ内削除後、集計・目標・会話・APIキーが消え、Health Connect原データは残ることを確認する。
