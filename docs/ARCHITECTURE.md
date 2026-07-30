# アーキテクチャ判断記録

## Health Connectを正本とする

センサー・ベンダーアプリの生データはHealth Connectに残し、アプリ独自DBへは表示・比較に必要な集計値と直近の運動セッションだけを保存する。これにより、同じ歩数を複数ソースから二重加算する問題と、健康ログの不要な複製を避ける。

## Roomを使う理由

Health Connectを画面描画のたびに読むと、権限・ページング・集計・レート制御がUIへ漏れる。Roomを読取りモデルとして置き、起動時、手動操作、許可されたバックグラウンド処理で28日を再集計する。標準の履歴読取り権限を追加せず取得できる範囲に収め、BigQueryは使わない。

## Geminiへ全データを渡さない

Geminiには次のローカル関数だけを公開する。

- `get_body_composition`
- `get_activity_summary`
- `get_exercise_summary`
- `get_sleep_summary`
- `get_heart_rate_summary`
- `get_activity_intensity_summary`
- `get_metabolism_summary`
- `get_goal_progress`

Geminiが質問に必要な関数と期間を返し、AndroidアプリがRoomを問い合わせて加工済みJSONを返す。生レコード、位置情報、内部IDは渡さない。

## 会話メモリー

全履歴はRoomへ保存するが、Geminiへ送るのは長期要約と直近20件。利用者発言6件ごと、および週次分析の直前に、食事・睡眠・運動・仕事・生活リズム・好み・制約を中心とした要約へ更新する。アシスタントの提案は習慣として記録しない。これにより、一つの会話として継続しつつ入力トークンの無制限な増加を防ぐ。

## 任意のHealth Connect指標

体重・体脂肪・歩数・距離・運動・活動消費を必須権限とし、睡眠・心拍・基礎代謝・活動強度を任意権限とする。任意項目の権限拒否や端末非対応があっても既存同期は継続する。活動強度は`FEATURE_ACTIVITY_INTENSITY`を実行時に確認する。

Roomはv2で日別集計へ任意列を追加し、v1からの`ALTER TABLE`移行で目標・会話・既存集計を保持する。

## 直接APIキー方式

完全個人利用のため、利用者本人が入力したGemini APIキーをAndroid Keystoreで暗号化する。コードにはキーを含めない。一般配布へ移行する場合、この判断は無効となり、認証付きバックエンドが必要になる。
