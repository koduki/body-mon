---
name: android-wireless-deploy
description: Androidへのデプロイ（deploy）要求において無条件で適用されるスキル。Wi-Fi（ワイヤレスデバッグ）経由で実機端末（Galaxy S24等）を自動検出し、接続・ペアリングからGradleビルド・インストール・アプリ起動までを行う。
---

# Android ワイヤレスデプロイスキル (無条件デフォルト)

> **注記**: 本プロジェクトにおける Android への「デプロイ（deploy）」指示は、**無条件に Wi-Fi（ワイヤレスデバッグ）経由の実機デプロイ**を意味し、本スキル手順に従って自動実行されます。

---

## 前置要件
- 開発PCとAndroid端末が同一のWi-Fiネットワークに接続されていること。
- Android端末側で **「設定」>「開発者向けオプション」>「ワイヤレスデバッグ」** が有効になっていること。
- プロジェクト直下に `local.properties` が配置され、`sdk.dir` が正しく設定されていること。

---

## 自動実行フロー

### ステップ 1: `local.properties` の確認・補正
プロジェクトルートの `local.properties` に Android SDK パスが定義されているか確認・生成します。

```properties
sdk.dir=C\:/Users/koduki/AppData/Local/Android/Sdk
```

---

### ステップ 2: mDNS による実機端末の自動検出
以下のコマンドでWi-Fiネットワーク上の実機端末およびサービスポートを検索します。

```powershell
adb mdns services
```

**検出例**:
- `_adb-tls-pairing._tcp 192.168.50.187:40935` （ペアリング用ポート）
- `_adb-tls-connect._tcp 192.168.50.187:34811` （接続用ポート）

---

### ステップ 3: 端末のペアリング（未接続・初回時）
未ペアリングの場合は端末に表示される6桁のペア設定コードでペアリングを実行します。

```powershell
adb pair <IPアドレス>:<ペアリング用ポート> <6桁のペア設定コード>
```

---

### ステップ 4: ADB 接続の確立
検出された接続用ポートへ接続します。

```powershell
adb connect <IPアドレス>:<接続用ポート>
adb devices
```

---

### ステップ 5: アプリのビルド & 実機デプロイ
Gradleでデバッグ用APKをビルドし、実機端末へインストールします。

```powershell
.\gradlew.bat assembleDebug
adb -s <接続済みの端末IP:PORT> install -r app/build/outputs/apk/debug/app-debug.apk
```

---

### ステップ 6: アプリの自動起動
インストール後、Main Activityを実機上で起動します。

```powershell
adb -s <接続済みの端末IP:PORT> shell am start -n com.master.healthcoach/.MainActivity
```
