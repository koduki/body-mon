# PC接続なしで更新する

GitHub ActionsでテストとリリースAPKのビルドを行い、別のジョブで署名・検証して
GitHub Releasesへ公開する。スマホはObtainiumで更新する。
アプリのHealth Connect・Room・Geminiの動作やデータ保存先は変更しない。

## 初回設定1: 今のアプリと同じ署名鍵を用意する

**既存アプリはアンインストールしない。** このアプリは会話・目標・APIキーを
バックアップしないため、削除すると復元できない。
新しい署名鍵を生成しても、既存アプリをそのまま更新することはできない。

Android StudioのRunで導入したdebug版なら、原則としてそのPCで使った
`debug.keystore`を引き継ぐ。CIで毎回生成されるdebug鍵を使ってはいけない。

| PC | 標準の鍵の場所 |
|---|---|
| Windows | `%USERPROFILE%\.android\debug.keystore` |
| macOS / Linux | `~/.android/debug.keystore` |

標準のdebug鍵はaliasが`androiddebugkey`、ストアと鍵のパスワードがともに`android`。
独自の署名設定がある場合はその値を使う。複数PCで開発していた場合は、実際に
現在のアプリへ署名した鍵を選ぶ。鍵そのものは秘密情報なので、Git・PR・チャットへ貼らない。

Android Studio付属JDKなどの`keytool`で、選んだ鍵の証明書SHA-256を確認する。
パスワードはプロンプトで入力する。出力されたSHA256の値は公開情報であり、秘密鍵ではない。

```bash
keytool -list -v -keystore /path/to/debug.keystore -alias androiddebugkey
```

元のAPKが手元にある場合は、Android SDKの`apksigner`で照合できる。

```bash
apksigner verify --print-certs /path/to/currently-installed.apk
```

両者の証明書SHA-256が同じであることを確認する。鍵が不明・紛失している場合は、
配布を有効にする前にデータのエクスポートなど移行方法を検討する。
この手順は新しい鍵へのローテーションやデータ移行を実装するものではない。

## 初回設定2: GitHubへ署名情報を登録する

[Settings → Secrets and variables → Actions](https://github.com/koduki/body-mon/settings/secrets/actions)
で以下を設定する。署名鍵は復元不能になると更新できなくなるため、GitHubとは別にも安全に保管する。

| 種別 | 名前 | 値 |
|---|---|---|
| Secret | `ANDROID_KEYSTORE_BASE64` | 上で確認したkeystore全体をBase64にした値 |
| Secret | `ANDROID_KEYSTORE_PASSWORD` | ストアのパスワード |
| Secret | `ANDROID_KEY_ALIAS` | 鍵のalias |
| Secret | `ANDROID_KEY_PASSWORD` | 鍵のパスワード |
| Variable | `ANDROID_SIGNING_CERT_SHA256` | 確認した証明書のSHA-256。コロン区切りでも可 |
| Variable | `ANDROID_RELEASE_ENABLED` | 設定完了後、最後に`true`へ変更 |

GitHub CLIが使える場合は、Base64を画面やファイルに残さず、標準入力で登録できる。
下の`/path/to/debug.keystore`を実際のファイルへ置き換える。Windowsでは必要に応じて
`python3`を`python`に読み替える。

```bash
python3 -c "import base64,pathlib,sys; sys.stdout.write(base64.b64encode(pathlib.Path(sys.argv[1]).read_bytes()).decode())" /path/to/debug.keystore | gh secret set ANDROID_KEYSTORE_BASE64 --repo koduki/body-mon
gh secret set ANDROID_KEYSTORE_PASSWORD --repo koduki/body-mon
gh secret set ANDROID_KEY_ALIAS --repo koduki/body-mon
gh secret set ANDROID_KEY_PASSWORD --repo koduki/body-mon
```

残りのコマンドは値を対話入力する。VariableはGitHub画面から設定できる。
署名指紋を固定することで、Secretを誤って別の鍵へ置き換えても公開前に停止する。
配布ジョブの有効化前にGitHub側のActions実行が許可されていることも確認する。

**現在のリポジトリは公開なので、ReleaseのAPKも誰でもダウンロードできる。**
Gemini APIキーや健康データはAPKには含めない。APKの公開範囲を限定したい場合は、
この配布経路を有効化する前に方針を変更する。

## 初回設定3: 最初のReleaseを作る

1. CI改修を`main`へマージする。
2. 署名設定と`ANDROID_RELEASE_ENABLED=true`を確認する。
3. [Actions → Android](https://github.com/koduki/body-mon/actions/workflows/android.yml)の
   **Run workflow**で`main`を指定して実行する。設定後の次の`main`へのpushでもよい。
4. `test`と`release`が成功し、Releasesに`body-mon.apk`、`SHA256SUMS`、
   `release-metadata.json`が揃っていることを確認する。

公開APKはdebuggableではないreleaseビルドだが、移行時には既存のdebug鍵で署名してもよい。
これは個人用の直接配布のための手順であり、Google Playへの公開用署名設定とは別である。

## 初回設定4: スマホにObtainiumを設定する

1. [Obtainium公式Releases](https://github.com/ImranR98/Obtainium/releases)からObtainiumを導入する。
2. Obtainiumのアプリ追加で`https://github.com/koduki/body-mon`を登録する。ソースはGitHub。
3. 取得対象が`body-mon.apk`であることを確認する。この配布は各ReleaseにつきAPKが1個だけなので、
   通常はAPK選択フィルターもプレリリースを含める設定も不要。
4. 初回は手動でインストール・更新を承認する。既存アプリがある場合は上書き更新する。
   署名の不一致などで失敗した場合、アンインストールせず初回設定1へ戻る。
5. Obtainium全体とbody-mon個別のバックグラウンド更新を有効にする。
6. OSに求められたインストール元の許可は、信頼して導入したObtainiumに対して設定する。

Android 12以降、更新対象が最近のAPIをtargetにしていること（本アプリは36）、
現在のインストーラーがObtainiumであることなどの条件を満たすと、確認なしで更新可能。
初回の上書き後にObtainiumがインストーラーとして扱われるかも実機で確認する。
条件を満たさない場合は更新通知から手動で承認する。rootやADBは通常の運用には不要。

自動更新は定期確認とOSのバックグラウンド実行制約に従う。マージ直後の反映は保証しない。
急ぐ場合はObtainiumを開いて更新を確認する。Galaxyで更新が遅れる場合は、まず
Obtainiumのバックグラウンド実行設定やスリープ中アプリへの分類を確認する。
端末全体のセキュリティ機能を一律に無効化する手順にはしない。

## 日々の運用

- `main`へのpush・PRマージで自動ビルドし、テスト成功後に公開する。
- PRではdebug/releaseのテスト・ビルドと使い捨て鍵による署名テストだけを実行する。
  本番用の鍵を使わず、APKの公開もしない。
- バージョンは`versionCode = 1000 + GITHUB_RUN_NUMBER`、`versionName = 0.1.<versionCode>`、
  タグは`v<versionName>`。APKとReleaseの番号が一致し、Obtainiumが更新を判定できる。
- 同じ実行の再実行は同じ番号になる。公開済みAPKは上書きしない。
  アップロード途中のdraftは再実行で復旧する。古いコミットの再実行は公開しない。
- mainの実行を途中キャンセルしない。複数pushが続いた場合、GitHubのconcurrencyにより
  中間の待機実行が省略されることがある。すべてのコミットを必ず配布する仕組みではない。
- `.github/workflows/android.yml`の実行番号がリセットされる移設・変更をする場合は、
  既に配布した最大versionCodeを超える採番へ移行する。番号を下げない。
- ローカルdebugビルドの既定は従来の`1 / 0.1.0`なので、CI版へ上書きすると
  ダウングレードとして拒否される。通常はObtainiumを使い、拒否を理由にアプリを削除しない。
- 不具合は修正またはrevertをmainへ入れ、**新しい番号**のAPKとして配る。
  Roomスキーマを変更した場合、単純revertが安全とは限らないため、データ移行互換性も確認する。
- 自動配布を止めるには`ANDROID_RELEASE_ENABLED`を`false`にし、必要なら進行中の配布も止める。
  公開済みReleaseやスマホへ取得済みの更新は自動では取り消されない。

秘密鍵は署名ステップの一時ディレクトリにのみ復元し、成功・失敗時とも削除する。
Gradleビルドには本番鍵を渡さない。unsigned APKのActions artifactは3日保持するが、
Obtainiumの配布元はActions artifactではなくGitHub Releasesである。

## 検証

ローカルで実行できる配布ロジックのテスト:

```bash
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
```

Android SDKがあるCIでは、既存のJUnitに加えてreleaseビルドもコンパイルし、
生成した実APKを使い捨て鍵で署名・署名検証する。使い捨て鍵のAPKは公開しない。
mainで有効化した本配布でも、アプリID・バージョン・debuggableフラグ・署名指紋・
チェックサム・アップロード完了を確認してからdraftを公開する。

初回は、実機で会話・目標・APIキー・Health Connect権限が保たれ、同期とチャットが
動くことを確認する。続くReleaseをObtainiumから受け取れることも確認する。

## 参考

- [Obtainium: 自動更新の条件](https://wiki.obtainium.imranr.dev/app_tracking/#silent-installation)
- [Android: アプリの更新条件](https://developer.android.com/google/play/app-updates)
- [GitHub: ActionsのSecrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
