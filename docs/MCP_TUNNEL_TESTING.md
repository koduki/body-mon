# Secure MCP Tunnel実機確認

## 前提

OpenAI Platformで個人専用トンネルを作成し、利用権限を持つ実行用credentialを用意する。
トンネルをChatGPT側で利用できる組織/ワークスペースへ紐付ける。管理者キーや共有の組織全権キーをAPKへ埋め込まない。
この実装はapi.openai.comへの直接HTTPSを使用し、企業専用CA、制御プレーンmTLS、OAuth連携は実装していない。

## 接続

1. アプリをインストールし、設定タブを開く。Health Connect同期日時を確認する。
2. ChatGPT連携へトンネルIDと実行用キーを入力する。必要な場合だけ組織IDを入力する。
3. 最初は健康情報のみ共有し、15分で接続を開始する。通知を許可する。
4. ChatGPTのカスタムMCP設定でTunnelを選び、同じIDを登録する。検出中もアプリを接続状態にする。
5. get_app_context、get_profile_and_goals、get_health_metrics(metric=body, from=..., to=...)を呼ぶ。
6. 同じスマホでChatGPTへ切り替え、応答することを確認する。
7. 会話・診断・根拠を試す場合はいったん停止し、共有項目を変えて再接続する。必要ならChatGPT側のツール一覧を再読み込みする。

## 権限・停止

- 会話共有OFFでsearch_conversationsを直接呼び出してもPERMISSION_DENIEDになる。
- 診断のみでは健康値・会話本文・詳細入力を取得できない。
- 停止ボタン、通知の停止、画面OFF、15分期限、タスク除去、アプリ強制停止で接続を終了する。
- 接続中の機内モードと復帰、Wi-Fi/モバイル回線切替を確認する。停止後に古い応答を送らない。
- 再起動後は停止状態。設定を削除したらキーの再入力が必要。
- 認証を失効させると再試行を続けず停止する。

## 診断

- 同期してsyncの開始/成功と種別件数を取得する。権限不足で定期同期がスキップされた場合はskipped_permissionになる。
- 詳細記録をONにしてアプリ内Geminiで相談する。対象assistant messageIdのget_response_evidenceから初期文脈とツール結果を読む。
- OFF時の回答はmetadataのみ。未記録のpromptが存在するように見せない。
- 同期または目標保存後のlist_weekly_reports→get_calculation_traceで入力と結果・コード版を照合する。
- 診断削除と全データ削除で該当ストアを消去する。接続停止後に確認する。

## 電池

同じ端末・回線・時間・充電条件で接続OFF/ONを比較する。画面OFFで終了する仕様のため、初期版は相談中の画面ON条件で評価する。
診断の開始/終了電池残量、CPU時間、poll回数、空poll回数、通信byte数、再試行を記録する。端末の全体消費をトンネルだけの消費と解釈しない。

## 検証範囲

CI: Debug/Release単体テストとビルド、模擬トンネル通信、MCP権限・入力検証、SQLite migrationとrevisionのrollback、配布ツール。
実機未確認: 実際のOpenAIトンネル/ChatGPTとの疎通、端末固有のサービス制限、画面切替・通知・電池消費。
