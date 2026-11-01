# 心拍時系列・回復・28日比較とXiaomi Cloudの検証

## 実装済み（PR #22 の拡張）

- Room v12: `heart_rate_samples`（元レコードID + timestamp + bpm複合主キー、origin、関連sessionId、phase）を保存。同期範囲は原子更新し、90日以前をプルーニング。個別サンプルはアプリ外へ自動送信しない。
- MCP `get_heart_rate_series`: `from`/`to`（90日以内）、任意の `sessionId`、`resolutionMinutes`（0=生データ、1～60=分単位の実測バケット）、`limit`（1～500）と改訂番号に結びつく `nextCursor`。欠測区間の値を補完しない。
- 運動との紐付け: 運動開始から終了までを `workout`、終了後3分以内を `post_workout`、それ以外を `unclassified`。**unclassified は安静時心拍ではない**。複数セッション重複時は開始時刻が新しいものを優先する。
- 運動後心拍回復: 終了直前60秒と終了+1分/2分の±15秒それぞれ2点以上、運動セッション3分以上かつ観測カバレッジ50%以上のときだけ参考値として保存。運動を停止した姿勢・移動・センサ欠測で大きく変わるため医学的な健全性の判定には使わない。
- MCP `get_workout_hr_comparison`: 完了28日間を7日対21日に分け、種別と運動時間±20%かつ各期間2セッション以上の平均心拍を比較。**速度・距離・標高・パワー・気温が揃わない限り真の同一負荷比較ではない**。心肺適応、VO2maxや過負荷の断定に使わない。
- 週報・アプリ画面: セッション別の心拍観測数・回復観測値、週次の回復と28日比較。心拍ゾーンのZ1..Z5は取得できた観測分のみ、未観測時間は0扱いしない。

## Xiaomi Cloud （試験設計・実機未検証）

Google Health公式のMi Fitness連携案内では運動外心拍とHRVはHealth Connectへの共有対象外。したがって休息・睡眠中心拍の不足はRoom/MCPを増やすだけでは埋まらない。

確認した手段:
1. **公式の個人情報エクスポート**（優先）: Xiaomi公式 Privacy Support FAQの「Xiaomi Account > Privacy > Manage your data / Download profile」から対応サービスを選びデータを取得。日本アカウントにMi Fitnessの心拍・睡眠が含まれるかは未確認。ダウンロードされたデータの実形式を本人が確認してからローカルインポータを実装する。
2. **非公式読み取り**: [Jenwein/mifit](https://github.com/Jenwein/mifit) はQRログインと生心拍・安静時心拍サマリの読み取りを報告。ただし検証は中国リージョン中心、Xiaomi非公開APIに依存。日本リージョン/手元の機種で動作する保証なし。
3. **セキュリティ**: `mifit` の `passToken` はMi FitnessだけではなくXiaomiアカウント全体にアクセスし得る強い認証情報。**body-mon / GitHub / MCP / Gemini / ChatGPT にトークンやCookieを転送しない**。デフォルト無効、本人によるローカル検証・明示的な同意が先。非公式エンドポイントをAndroidアプリにハードコードしない。
4. 同期元の区別と重複排除: Cloudを将来追加しても `Health Connect/Xiaomi` と混合平均しない。どちらの値がどの記録由来か provenance と期間を出し、同時刻重複レコードを二重計上しない。

受入基準: 日本アカウントで **運動外HR、睡眠中心HR、安静時HR** が実際に観測できること、各記録の時刻・取得元が正しく保持されること、認証情報が端末外・ログ・クラッシュ送信に出ないこと。**HRVの利用可否は未確認**であり、取得できるとは主張しない。

資料:
- https://support.google.com/googlehealth/answer/14236613?hl=en
- https://privacy.mi.com/support/faq?locale=en
- https://github.com/Jenwein/mifit
