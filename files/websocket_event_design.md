# WebSocketイベント設計書：白雪姫のアップルルーレット

**バージョン：** 2.0  
**作成日：** 2026年5月10日  

---

## 目次

1. [概要と設計方針](#1-概要と設計方針)
2. [メッセージの共通フォーマット](#2-メッセージの共通フォーマット)
3. [接続管理](#3-接続管理)
4. [クライアント→サーバー イベント](#4-クライアントサーバー-イベント)
5. [サーバー→クライアント イベント](#5-サーバークライアント-イベント)
6. [主要シナリオのイベントフロー](#6-主要シナリオのイベントフロー)
7. [エラーコード一覧](#7-エラーコード一覧)

---

## 1. 概要と設計方針

### 1-1. HTTP と WebSocket の役割分担

| 種別 | 使用プロトコル | 対象処理 |
|-----|-------------|---------|
| ルーム作成・参加 | HTTP REST | ルームの作成、参加コードでの入室、プレイヤー登録 |
| リアルタイムゲーム通信 | WebSocket | ゲーム進行中のすべての状態同期・アクション送受信 |

- ルーム作成・参加はHTTP APIで処理し、レスポンスで得た `playerId` と `roomId` を用いてWebSocket接続を確立する
- WebSocket接続後のすべてのゲームイベントはJSON形式のメッセージで行う

### 1-2. 状態同期の方針

- プレイヤーのアクションごとにサーバーは `GAME_STATE_SYNC` を全プレイヤーへブロードキャストする
- `GAME_STATE_SYNC` の内容は受信者の権限に応じてフィルタリングする（役職・手札・リンゴ情報は本人のみ）
- アニメーションや通知が必要なアクション（ルーレット・死亡等）は専用の `NOTIFY_*` イベントを `GAME_STATE_SYNC` の前に送信する

### 1-3. リクエスト/レスポンスパターン

サーバーが特定のプレイヤーへ入力を求める場合（好み回答・女王の交換選択など）は以下の流れとなる。

```
サーバー → 対象プレイヤー : REQUEST_*（入力要求）
対象プレイヤー → サーバー : RESPONSE_*（回答）
サーバー → 全員 : NOTIFY_* + GAME_STATE_SYNC（結果通知）
```

---

## 2. メッセージの共通フォーマット

すべてのWebSocketメッセージはJSON形式とする。

### 送信メッセージ（クライアント→サーバー）

```json
{
  "type": "イベント種別（後述の定数名）",
  "payload": { }
}
```

### 受信メッセージ（サーバー→クライアント）

```json
{
  "type": "イベント種別",
  "payload": { }
}
```

### 共通型定義

```kotlin
// 全メッセージの基底型
data class WsMessage(
    val type: String,
    val payload: JsonObject
)

// プレイヤー情報（複数箇所で使用）
data class PlayerSummary(
    val playerId: String,       // UUID
    val userName: String,
    val seatOrder: Int,
    val isAlive: Boolean,
    val isConnected: Boolean,   // WebSocket接続中か（切断中は false・切断中バッジ表示に使用）
    val isRoleRevealed: Boolean,
    val role: String?,          // isRoleRevealed=true の場合のみ含む
    val isProtected: Boolean,
    val skipNextTurn: Boolean,
    val applePreferenceAnswer: Boolean?,
    val mushroomPreferenceAnswer: Boolean?
)

// リンゴ情報（受信者の権限に応じてフィルタリング済み）
data class AppleSummary(
    val appleId: String,            // UUID
    val currentHolderPlayerId: String,
    val isPoisoned: Boolean?,       // 知っている場合のみセット。知らない場合はnull
    val isPubliclyRevealed: Boolean
)
```

---

## 3. 接続管理

### 3-1. WebSocket接続の確立

HTTP APIでルーム参加後、以下のエンドポイントへWebSocket接続する。

```
ws://<host>/ws/game?roomId=<roomId>&playerId=<playerId>
```

接続確立後、サーバーは接続プレイヤーを特定し、ルーム待機画面の状態を返す。

### 3-2. 再接続

接続が切断された場合、クライアントは同じ `roomId`・`playerId` で再接続できる。サーバーはゲーム中であれば現在のゲーム状態を再送信する。

### 3-3. 切断時の挙動

- ゲーム開始前の切断：待機中のプレイヤーとして扱い、他プレイヤーへ `PLAYER_DISCONNECTED` を通知する
- **ゲーム中（白雪姫以外）の切断：** 切断を検知してから **1分（60秒）以内に再接続**した場合はゲームを続行する。1分経過しても再接続がなかった場合は死亡扱いとし `NOTIFY_PLAYER_DIED { cause: "DISCONNECTED" }` を全員へ送信する
- **ゲーム中（白雪姫）の切断：** 白雪姫以外と同様に **1分（60秒）以内に再接続**した場合はゲームを続行する。1分経過しても再接続がなかった場合は女王陣営の勝利としてゲームを強制終了する（シナリオ6-9参照）

### 3-4. タイムアウト定義

ゲーム進行に関わるすべてのタイムアウト値を以下に定義する。

| 種別 | タイムアウト | タイムアウト時の挙動 |
|-----|------------|------------------|
| 手番全体 | **3分（180秒）** | 使用中・選択中のカードを捨て山へ送り手番終了。`NOTIFY_DISCARD_CARD` → `GAME_STATE_SYNC` → `TURN_CHANGED` の順に送信する。呪いの指輪を持ちながら山札を引いた後にタイムアウトした場合は新たに引いたカードを捨て山へ送る（呪いの指輪は手元に残る） |
| 切断プレイヤーの手番 | **1分（60秒）** | 1分以内に再接続した場合はスキップしない。1分経過で死亡扱いとして手番をスキップし次の手番へ進む |
| `REQUEST_PREFERENCE` への応答 | **1分（60秒）** | タイムアウト時は**役職に応じた正解を自動回答する**（ネイビーはランダムに Yes / No を選択） |
| `REQUEST_QUEEN_EXCHANGE` への応答 | **3分（180秒）** | タイムアウト時はランダムな生存プレイヤーを交換対象として自動選択する |

> **タイムアウトのカウント方法：** 手番開始（`TURN_CHANGED` 送信）と同時にサーバー側でタイマーを開始する。`REQUEST_*` を送信した場合はそのタイマーとは別に入力待ちタイマーを開始する。

---

## 4. クライアント→サーバー イベント

### 4-1. ゲーム準備

#### GAME_START

- **送信者：** ホストのみ
- **タイミング：** ルーム待機画面で現在の参加人数が選択した役職数と一致した後
- **ペイロード：** なし

```json
{ "type": "GAME_START", "payload": {} }
```

#### REMATCH_REQUEST

- **送信者：** ホストのみ
- **タイミング：** ゲーム終了後の勝敗画面
- **ペイロード：** なし

```json
{ "type": "REMATCH_REQUEST", "payload": {} }
```

---

### 4-2. 手番アクション

手番中のプレイヤーのみが送信できる。他プレイヤーが送信した場合はエラーを返す。

#### ACTION_DRAW_CARD（①山札を引く）

- **ペイロード：** なし

```json
{ "type": "ACTION_DRAW_CARD", "payload": {} }
```

> サーバーは引いたカードを手番プレイヤーのみに開示し、全体へは山札の残枚数のみを通知する。

#### ACTION_USE_CARD（カードを使用する）

- **ペイロード：** カード種別に応じて追加パラメータが変わる

```json
{
  "type": "ACTION_USE_CARD",
  "payload": {
    "cardId": "UUID",
    "cardType": "KNIFE",
    "params": {
      "targetPlayerId": "UUID"
    }
  }
}
```

**カード種別ごとの `params` 定義：**

| cardType | params |
|---------|--------|
| `APPLE_QUESTION` | `{ "targetPlayerId": "UUID" }` |
| `MUSHROOM_QUESTION` | `{ "targetPlayerId": "UUID" }` |
| `ITADAKIMASU` | `{ "count": 1〜3 }` |
| `ROULETTE_1` / `ROULETTE_2` / `ROULETTE_3` | `{ "direction": "CLOCKWISE" \| "COUNTER_CLOCKWISE" }` |
| `KNIFE` | `{ "targetPlayerId": "UUID" }` |
| `ROPE` | `{ "targetPlayerId": "UUID" }` |
| `PRESENT_EXCHANGE` | `{ "targetPlayerIdA": "UUID", "targetPlayerIdB": "UUID" }` |
| `POISON_COMB` | `{ "targetPlayerId": "UUID" }` |

> `CURSED_RING`・`KNIGHT`・`GUARD` は使用・廃棄ともに不可のためこのイベントでは送信できない。

#### ACTION_DISCARD_CARD（カードを効果なしで捨てる）

- **ペイロード：** 捨てるカードのID

```json
{
  "type": "ACTION_DISCARD_CARD",
  "payload": {
    "cardId": "UUID"
  }
}
```

> `CURSED_RING` は廃棄不可のため、このイベントで送信した場合はエラーを返す。

#### ACTION_EXCHANGE_HAND（②手札の交換）

```json
{
  "type": "ACTION_EXCHANGE_HAND",
  "payload": {
    "targetPlayerId": "UUID"
  }
}
```

> グレイの保護中のプレイヤーを対象にした場合はエラーを返す。

#### ACTION_EXCHANGE_APPLE（③リンゴの交換）

```json
{
  "type": "ACTION_EXCHANGE_APPLE",
  "payload": {
    "targetPlayerId": "UUID"
  }
}
```

> グレイの保護中のプレイヤーを対象にした場合はエラーを返す。

#### ACTION_CHECK_OWN_APPLE（④自分のリンゴの確認）

- **ペイロード：** なし

```json
{ "type": "ACTION_CHECK_OWN_APPLE", "payload": {} }
```

> サーバーは手番プレイヤーのみに `YOUR_APPLE_STATUS` を返す。

#### ACTION_USE_ABILITY（⑤キャラクター能力の発動）

役職によって `params` が異なる。

```json
{
  "type": "ACTION_USE_ABILITY",
  "payload": {
    "params": { ... }
  }
}
```

**役職ごとの `params` 定義：**

| 役職 | params |
|-----|--------|
| グレイ | `{}` （対象なし、発動宣言のみ） |
| ライト | `{ "targetPlayerIdA": "UUID", "targetPlayerIdB": "UUID" }` |

---

### 4-3. インタラクティブ応答

サーバーからの `REQUEST_*` イベントへの返答として送信する。手番外のプレイヤーも送信可能。

#### RESPONSE_PREFERENCE（好み質問への回答）

- **タイミング：** `REQUEST_PREFERENCE` 受信後

```json
{
  "type": "RESPONSE_PREFERENCE",
  "payload": {
    "answer": true
  }
}
```

#### RESPONSE_QUEEN_EXCHANGE（女王のリンゴ交換対象選択）

- **タイミング：** `REQUEST_QUEEN_EXCHANGE` 受信後（女王のみ送信可）

```json
{
  "type": "RESPONSE_QUEEN_EXCHANGE",
  "payload": {
    "targetPlayerId": "UUID"
  }
}
```

> 女王のリンゴが通常リンゴだった場合、このイベントは送信不要（サーバーが自動スキップ）。

---

## 5. サーバー→クライアント イベント

### 5-1. 接続・ルーム

#### PLAYER_JOINED（他プレイヤーが参加）

- **送信先：** ルーム内の全プレイヤー

```json
{
  "type": "PLAYER_JOINED",
  "payload": {
    "playerId": "UUID",
    "userName": "プレイヤー名",
    "seatOrder": 2
  }
}
```

#### PLAYER_DISCONNECTED（プレイヤーが切断）

- **送信先：** ルーム内の全プレイヤー

```json
{
  "type": "PLAYER_DISCONNECTED",
  "payload": {
    "playerId": "UUID",
    "userName": "プレイヤー名"
  }
}
```

---

### 5-2. ゲーム開始

#### GAME_STARTED（ゲーム開始通知）

- **送信先：** 全員（共通情報）

```json
{
  "type": "GAME_STARTED",
  "payload": {
    "gameId": "UUID",
    "players": [ PlayerSummary, ... ],  // 全プレイヤー情報（役職なし）
    "firstTurnPlayerId": "UUID"
  }
}
```

#### YOUR_INITIAL_INFO（初期個人情報 - 各プレイヤーへ個別送信）

- **送信先：** 各プレイヤーへ個別に送信
- **内容：** 受信者の権限に応じた初期情報をすべて含む

```json
{
  "type": "YOUR_INITIAL_INFO",
  "payload": {
    "role": "GRAY",
    "faction": "SNOW_WHITE_FACTION",
    "myApple": {
      "appleId": "UUID",
      "isPoisoned": false
    },
    "myHand": [
      { "cardId": "UUID", "cardType": "ROPE" }
    ],
    // グリーンのみ含まれる
    "snowWhitePlayerId": "UUID",
    // ブラックのみ含まれる
    "poisonAppleHolderIds": ["UUID", "UUID"]
  }
}
```

---

### 5-3. ゲーム状態同期

#### GAME_STATE_SYNC（ゲーム状態の全体同期）

- **送信先：** 全員（内容は受信者ごとにフィルタリング）
- **タイミング：** 各アクション処理後に必ず送信

```json
{
  "type": "GAME_STATE_SYNC",
  "payload": {
    "phase": "STORY",
    "currentTurnPlayerId": "UUID",
    "deckRemainingCount": 15,
    "discardPile": [
      { "cardId": "UUID", "cardType": "ROPE" }
    ],
    "players": [ PlayerSummary, ... ],
    "apples": [ AppleSummary, ... ],
    // 自分の手札のみ含まれる
    "myHand": [
      { "cardId": "UUID", "cardType": "KNIFE" }
    ]
  }
}
```

#### YOUR_APPLE_STATUS（自分のリンゴの確認結果）

- **送信先：** ④自分のリンゴの確認を行ったプレイヤーのみ

```json
{
  "type": "YOUR_APPLE_STATUS",
  "payload": {
    "appleId": "UUID",
    "isPoisoned": true
  }
}
```

#### BLACK_APPLE_UPDATE（ブラックへの毒リンゴ位置更新）

- **送信先：** ブラック役のプレイヤーのみ
- **タイミング：** リンゴの移動（交換・ルーレット）後

```json
{
  "type": "BLACK_APPLE_UPDATE",
  "payload": {
    "poisonApples": [
      { "appleId": "UUID", "currentHolderPlayerId": "UUID" }
    ]
  }
}
```

---

### 5-4. アクション通知（アニメーション・ログ用）

`GAME_STATE_SYNC` の前に送信し、UIアニメーションのトリガーとして使用する。

#### NOTIFY_DRAW_CARD（山札を引いた）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_DRAW_CARD",
  "payload": {
    "playerId": "UUID",
    "deckRemainingCount": 14
  }
}
```

#### NOTIFY_USE_CARD（カードが使用された）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_USE_CARD",
  "payload": {
    "playerId": "UUID",
    "cardType": "KNIFE",
    "params": {
      "targetPlayerId": "UUID"
    }
  }
}
```

#### NOTIFY_DISCARD_CARD（カードが効果なしで捨てられた）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_DISCARD_CARD",
  "payload": {
    "playerId": "UUID",
    "cardType": "ROPE"
  }
}
```

#### NOTIFY_EXCHANGE_HAND（手札が交換された）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_EXCHANGE_HAND",
  "payload": {
    "playerIdA": "UUID",
    "playerIdB": "UUID"
  }
}
```

#### NOTIFY_EXCHANGE_APPLE（リンゴが交換された）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_EXCHANGE_APPLE",
  "payload": {
    "playerIdA": "UUID",
    "playerIdB": "UUID"
  }
}
```

#### NOTIFY_APPLE_PUBLICLY_REVEALED（リンゴが全体公開された）

- **送信先：** 全員
- **タイミング：** 包丁使用時・エンディングフェイズ

```json
{
  "type": "NOTIFY_APPLE_PUBLICLY_REVEALED",
  "payload": {
    "appleId": "UUID",
    "holderPlayerId": "UUID",
    "isPoisoned": true
  }
}
```

#### NOTIFY_ROULETTE（アップルルーレット実行）

- **送信先：** 全員
- **用途：** アニメーションのトリガー

```json
{
  "type": "NOTIFY_ROULETTE",
  "payload": {
    "cardType": "ROULETTE_2",
    "direction": "CLOCKWISE",
    "steps": 2,
    "excludedPlayerIds": ["UUID"]  // 死亡プレイヤーのIDリスト（除外対象）
  }
}
```

#### NOTIFY_PREFERENCE_ANSWERED（好み回答）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_PREFERENCE_ANSWERED",
  "payload": {
    "playerId": "UUID",
    "questionType": "APPLE",
    "answer": true
  }
}
```

#### NOTIFY_PLAYER_DIED（プレイヤー死亡）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_PLAYER_DIED",
  "payload": {
    "playerId": "UUID",
    "cause": "POISON_COMB"  // "POISON_COMB" | "CURSED_RING" | "POISON_APPLE" | "DISCONNECTED"
  }
}
```

#### NOTIFY_PLAYER_SKIPPED（ロープによるスキップ）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_PLAYER_SKIPPED",
  "payload": {
    "playerId": "UUID"
  }
}
```

#### NOTIFY_GRAY_ABILITY_ACTIVATED（グレイ能力発動）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_GRAY_ABILITY_ACTIVATED",
  "payload": {
    "playerId": "UUID"
  }
}
```

#### NOTIFY_LIGHT_ABILITY_ACTIVATED（ライト能力発動）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_LIGHT_ABILITY_ACTIVATED",
  "payload": {
    "playerId": "UUID",
    "role": "LIGHT",
    "swappedPlayerIdA": "UUID",
    "swappedPlayerIdB": "UUID"
  }
}
```

#### NOTIFY_KNIGHT_BLOCKED（騎士による毒の櫛無効化）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_KNIGHT_BLOCKED",
  "payload": {
    "targetPlayerId": "UUID"
  }
}
```

#### NOTIFY_GUARD_ACTIVATED（ガード発動）

- **送信先：** 全員

```json
{
  "type": "NOTIFY_GUARD_ACTIVATED",
  "payload": {
    "playerId": "UUID"
  }
}
```

#### NOTIFY_TIMEOUT（タイムアウトによる自動アクション）

- **送信先：** 全員
- **タイミング：** タイムアウトにより自動アクションが発動した場合に送信する

```json
{
  "type": "NOTIFY_TIMEOUT",
  "payload": {
    "timeoutType": "TURN",
    // timeoutType の種類：
    // "TURN"            - 手番タイムアウト（カードを捨て山へ送り強制終了）
    // "PREFERENCE"      - 好み回答タイムアウト（役職に応じた正解を自動回答。ネイビーはランダム）
    // "QUEEN_EXCHANGE"  - 女王選択タイムアウト（ランダム選択）
    "playerId": "UUID",             // タイムアウトしたプレイヤー
    "autoAction": "CARD_DISCARDED", // 実際に行われた自動アクションの説明
    // "CARD_DISCARDED"   - 手番タイムアウト（カードを捨て山へ）
    // "ANSWERED_BY_ROLE" - 好み回答タイムアウト（役職準拠の自動回答）
    // "RANDOM_SELECTED"  - 女王選択タイムアウト（ランダム選択）
    "discardedCardType": "KNIFE"    // TURN タイムアウト時のみ含む。捨てられたカードの種別
  }
}
```

---

### 5-5. 入力要求

#### REQUEST_PREFERENCE（好み質問への回答要求）

- **送信先：** 対象プレイヤーのみ

```json
{
  "type": "REQUEST_PREFERENCE",
  "payload": {
    "questionType": "APPLE",
    "askedByPlayerId": "UUID"
  }
}
```

#### REQUEST_QUEEN_EXCHANGE（女王のリンゴ交換対象選択要求）

- **送信先：** 女王のみ
- **送信条件：** 女王のリンゴが**毒リンゴの場合のみ**送信する。通常リンゴの場合は送信せずそのまま `ENDING_REVEAL` フェイズへ移行する

```json
{
  "type": "REQUEST_QUEEN_EXCHANGE",
  "payload": {
    "availableTargetPlayerIds": ["UUID", "UUID"]  // 生存プレイヤー（女王自身を除く）
  }
}
```

---

### 5-6. フェイズ・ターン

#### PHASE_CHANGED（フェイズ変更）

- **送信先：** 全員

```json
{
  "type": "PHASE_CHANGED",
  "payload": {
    "newPhase": "LAST_TURN",
    "triggerPlayerId": "UUID"  // フェイズ移行のトリガーとなったプレイヤー。エンディングなどプレイヤー起因でない場合はnull
  }
}
```

#### TURN_CHANGED（手番変更）

- **送信先：** 全員

```json
{
  "type": "TURN_CHANGED",
  "payload": {
    "currentTurnPlayerId": "UUID",
    "timeoutSeconds": 180
  }
}
```

---

### 5-7. ゲーム終了

#### GAME_RESULT（ゲーム結果）

- **送信先：** 全員

```json
{
  "type": "GAME_RESULT",
  "payload": {
    "winFaction": "SNOW_WHITE_FACTION",
    "reason": "NORMAL",  // "NORMAL" | "SNOW_WHITE_DISCONNECTED"
    "players": [
      {
        "playerId": "UUID",
        "userName": "プレイヤー名",
        "role": "SNOW_WHITE",
        "faction": "SNOW_WHITE_FACTION",
        "isAlive": true,
        "isWinner": true,
        "apple": {
          "appleId": "UUID",
          "isPoisoned": false
        }
      }
    ]
  }
}
```

#### NOTIFY_REMATCH_STARTING（再ゲーム開始通知）

- **送信先：** 全員
- **タイミング：** ホストが `REMATCH_REQUEST` を送信した直後

```json
{
  "type": "NOTIFY_REMATCH_STARTING",
  "payload": {}
}
```

> フロントエンドはこれを受け取りルーム待機画面へ遷移する。

---

### 5-8. エラー

#### ERROR

- **送信先：** エラーを起こしたプレイヤーのみ

```json
{
  "type": "ERROR",
  "payload": {
    "code": "NOT_YOUR_TURN",
    "message": "自分の手番ではありません"
  }
}
```

---

## 6. 主要シナリオのイベントフロー

### 6-1. ゲーム開始フロー

```
全員がWebSocket接続済みの状態でホストがゲーム開始ボタンを押す

Client(ホスト) → Server : GAME_START

Server → 全員        : GAME_STARTED
Server → 各プレイヤー : YOUR_INITIAL_INFO  ※個別送信
Server → 全員        : GAME_STATE_SYNC
Server → 全員        : TURN_CHANGED { currentTurnPlayerId }
```

---

### 6-2. 手番の基本フロー（山札を引いてカード使用）

```
手番プレイヤーが山札を引き、包丁を使用する例

Client(手番) → Server : ACTION_DRAW_CARD

Server → 全員          : NOTIFY_DRAW_CARD
Server → 手番プレイヤー : GAME_STATE_SYNC  ※引いたカードを含む手札を送信
Server → 他プレイヤー   : GAME_STATE_SYNC  ※手札情報なし

Client(手番) → Server : ACTION_USE_CARD { cardType: "KNIFE", params: { targetPlayerId } }

Server → 全員 : NOTIFY_USE_CARD
Server → 全員 : NOTIFY_APPLE_PUBLICLY_REVEALED { appleId, holderPlayerId, isPoisoned }
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : TURN_CHANGED { nextTurnPlayerId }
```

---

### 6-3. 「リンゴは好き？」カードのフロー

```
手番プレイヤーがリンゴは好き？カードを使用し、対象プレイヤーが回答する

Client(手番) → Server : ACTION_USE_CARD { cardType: "APPLE_QUESTION", params: { targetPlayerId } }

Server → 全員      : NOTIFY_USE_CARD
Server → 対象プレイヤー : REQUEST_PREFERENCE { questionType: "APPLE", askedByPlayerId }

  ※対象プレイヤーがUIでYes/Noを選択

Client(対象) → Server : RESPONSE_PREFERENCE { answer: true }

Server → 全員 : NOTIFY_PREFERENCE_ANSWERED { playerId, questionType: "APPLE", answer: true }
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : TURN_CHANGED
```

```
【タイムアウト時（1分経過）：役職に応じた正解を自動回答】

Server → 全員 : NOTIFY_TIMEOUT {
  timeoutType: "PREFERENCE",
  playerId: 対象プレイヤーID,
  autoAction: "ANSWERED_BY_ROLE"
}
Server → 全員 : NOTIFY_PREFERENCE_ANSWERED { playerId, questionType: "APPLE", answer: <役職準拠の正解> }
  ※ネイビーの場合は answer がランダム（true / false）
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : TURN_CHANGED
```

---

### 6-4. アップルルーレットのフロー

```
手番プレイヤーがアップルルーレット！！を使用する

Client(手番) → Server : ACTION_USE_CARD {
  cardType: "ROULETTE_2",
  params: { direction: "CLOCKWISE" }
}

Server → 全員      : NOTIFY_ROULETTE { direction, steps: 2, excludedPlayerIds }
                   ※フロントエンドはこれを受けてアニメーションを再生
Server → ブラック  : BLACK_APPLE_UPDATE  ※新しい毒リンゴ位置
Server → 全員      : GAME_STATE_SYNC
Server → 全員      : TURN_CHANGED
```

---

### 6-5. 最後の手番フェイズ（呪いの指輪発動）フロー

```
山札を引き切ってフェイズが移行し、呪いの指輪持ちに手番が回ってきた

Server → 全員 : PHASE_CHANGED { newPhase: "LAST_TURN", triggerPlayerId }
Server → 全員 : TURN_CHANGED { currentTurnPlayerId }  ※呪いの指輪を持つプレイヤー

  ※手番開始時点で即座に判定

Server → 全員 : NOTIFY_PLAYER_DIED { playerId, cause: "CURSED_RING" }
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : TURN_CHANGED { nextTurnPlayerId }
```

---

### 6-6. 毒の櫛 + 騎士（無効化）のフロー

```
白雪姫が騎士を持っており、毒の櫛で狙われた場合

Client(手番) → Server : ACTION_USE_CARD {
  cardType: "POISON_COMB",
  params: { targetPlayerId: "白雪姫のID" }
}

  ※サーバーが騎士所持チェック
  ※対象が白雪姫 かつ 騎士を手札に持つ → 無効化

Server → 全員 : NOTIFY_USE_CARD
Server → 全員 : NOTIFY_KNIGHT_BLOCKED { targetPlayerId }
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : TURN_CHANGED
```

---

### 6-7. エンディングフェイズ（女王特権）フロー

エンディングフェイズはすべてのパターンを以下に示す。

```
【共通：フェイズ移行と女王のリンゴ公開】
※ パターンAを除く全パターンで最初に実行される

Server → 全員 : PHASE_CHANGED { newPhase: "ENDING_QUEEN", triggerPlayerId: null }
Server → 全員 : NOTIFY_APPLE_PUBLICLY_REVEALED { appleId, holderPlayerId: 女王ID, isPoisoned }
Server → 全員 : GAME_STATE_SYNC
```

```
【パターンA：女王が死亡済みの場合】
※ 毒の櫛・呪いの指輪によって最後の手番フェイズ中に死亡していた場合
※ 共通ステップ（リンゴ公開・REQUEST）はすべてスキップする

Server → 全員 : PHASE_CHANGED { newPhase: "ENDING_QUEEN", triggerPlayerId: null }
  ※女王死亡済みのためサーバーが即座に自動スキップ
Server → 全員 : PHASE_CHANGED { newPhase: "ENDING_REVEAL", triggerPlayerId: null }
```

```
【パターンB：女王のリンゴが通常リンゴの場合】

Server → 全員 : PHASE_CHANGED { newPhase: "ENDING_QUEEN", triggerPlayerId: null }
Server → 全員 : NOTIFY_APPLE_PUBLICLY_REVEALED { appleId, holderPlayerId: 女王ID, isPoisoned: false }
Server → 全員 : GAME_STATE_SYNC
  ※通常リンゴのため交換なし、即座に次フェイズへ
Server → 全員 : PHASE_CHANGED { newPhase: "ENDING_REVEAL", triggerPlayerId: null }
```

```
【パターンC：女王のリンゴが毒リンゴ・交換成立の場合】

（共通ステップ実行後）
Server → 女王 : REQUEST_QUEEN_EXCHANGE { availableTargetPlayerIds }

Client(女王) → Server : RESPONSE_QUEEN_EXCHANGE { targetPlayerId }

  ※対象がガードを持っていない場合
Server → 全員 : NOTIFY_EXCHANGE_APPLE { playerIdA: 女王, playerIdB: 対象 }
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : PHASE_CHANGED { newPhase: "ENDING_REVEAL", triggerPlayerId: null }
```

```
【パターンD：女王のリンゴが毒リンゴ・対象がガードを持っている場合】

  ※パターンCで女王が交換対象を指定した瞬間にサーバーがガード保持チェック
  ※ガードを持っている場合、プレイヤーの操作なしに自動発動する

Server → 全員 : NOTIFY_GUARD_ACTIVATED { playerId: 対象 }
Server → 全員 : GAME_STATE_SYNC  ※交換なし
Server → 全員 : PHASE_CHANGED { newPhase: "ENDING_REVEAL", triggerPlayerId: null }
```

```
【パターンE：女王選択タイムアウトの場合（3分経過）】

  ※パターンCでREQUEST_QUEEN_EXCHANGEを送信後、3分経過
Server → 全員 : NOTIFY_TIMEOUT { timeoutType: "QUEEN_EXCHANGE", playerId: 女王, autoAction: "RANDOM_SELECTED" }
Server → 全員 : NOTIFY_EXCHANGE_APPLE { playerIdA: 女王, playerIdB: ランダム選択されたプレイヤー }
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : PHASE_CHANGED { newPhase: "ENDING_REVEAL", triggerPlayerId: null }
```

```
【全パターン共通：ENDING_REVEAL以降】

Server → 全員 : NOTIFY_APPLE_PUBLICLY_REVEALED  ※未公開の全リンゴ分を順次送信
Server → 全員 : NOTIFY_PLAYER_DIED              ※毒リンゴ保持者分（死亡順に送信）
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : GAME_RESULT
```

---

### 6-8. グレイ能力発動フロー

```
グレイが手番で能力を発動する

Client(グレイ) → Server : ACTION_USE_ABILITY { params: {} }

Server → 全員 : NOTIFY_GRAY_ABILITY_ACTIVATED { playerId }
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : TURN_CHANGED

  ※次の手番でグレイが保護されている間に、他プレイヤーが交換を試みた場合
Client(他) → Server : ACTION_EXCHANGE_APPLE { targetPlayerId: グレイのID }
Server → 他  : ERROR { code: "TARGET_IS_PROTECTED" }
```

---

### 6-9. 白雪姫切断によるゲーム強制終了フロー

```
白雪姫が切断した場合（白雪姫以外の切断と同じく1分の猶予あり）

切断検知
  → サーバーが1分タイマー開始
  → isConnected = false に更新

Server → 全員 : GAME_STATE_SYNC  ※isConnected = false が反映される

【パターンA：1分以内に再接続した場合】

1分以内に再接続
  → isConnected = true に更新

Server → 全員 : GAME_STATE_SYNC  ※isConnected = true が反映される
  ※ゲーム進行は通常通り継続

【パターンB：1分経過しても再接続しなかった場合（強制終了）】

1分タイムアウト
  → isConnected = false, isAlive = false に更新

Server → 全員 : NOTIFY_PLAYER_DIED {
  playerId: 白雪姫のID,
  cause: "DISCONNECTED"
}
Server → 全員 : GAME_RESULT {
  winFaction: "QUEEN_FACTION",
  reason: "SNOW_WHITE_DISCONNECTED",
  players: [ ...全プレイヤーの役職・生死・リンゴ情報（すべて公開） ]
}

  ※GAME_RESULTの reason フィールドは以下の値を持つ
  "NORMAL"                  - 通常の勝敗判定
  "SNOW_WHITE_DISCONNECTED" - 白雪姫の切断による強制終了
```

> **注意：** 白雪姫の1分タイムアウト確定時はエンディングフェイズに移行せず、即座に `GAME_RESULT` を送信する。リンゴ公開（`NOTIFY_APPLE_PUBLICLY_REVEALED`）や `PHASE_CHANGED` は行わない。フロントエンドは `reason: "SNOW_WHITE_DISCONNECTED"` を受け取った場合、通常の勝敗画面とは異なるメッセージ（「白雪姫が切断したためゲームが終了しました」）を表示する。

---

### 6-10. 白雪姫以外のプレイヤー切断フロー

```
【パターンA：1分以内に再接続した場合】

切断検知
  → サーバーが1分タイマー開始
  → isConnected = false に更新

Server → 全員 : GAME_STATE_SYNC  ※isConnected = false が反映される

1分以内に再接続
  → isConnected = true に更新

Server → 全員 : GAME_STATE_SYNC  ※isConnected = true が反映される
  ※ゲーム進行は通常通り継続
```

```
【パターンB：1分経過しても再接続しなかった場合】

1分タイムアウト
  → isConnected = false, isAlive = false に更新

Server → 全員 : NOTIFY_PLAYER_DIED { playerId, cause: "DISCONNECTED" }
Server → 全員 : GAME_STATE_SYNC
  ※ゲームはそのまま続行。以降の手番は自動スキップ

  ※1分タイムアウトが切断プレイヤーの手番中に発生した場合は
    NOTIFY_PLAYER_DIED の直後に TURN_CHANGED を送信する
Server → 全員 : TURN_CHANGED  ※次のプレイヤーへ
```

---

### 6-11. ライト能力発動フロー

```
ライトが手番で役職公開＋2名のリンゴ強制交換を行う

1. フロントエンドでライトが2名を選択（チェックボックス式）
2. 「交換する」ボタンを押すと ACTION_USE_ABILITY を送信

Client(ライト) → Server : ACTION_USE_ABILITY {
  params: {
    "targetPlayerIdA": "UUID",
    "targetPlayerIdB": "UUID"
  }
}

Server → 全員 : NOTIFY_LIGHT_ABILITY_ACTIVATED {
  playerId: ライトのID,
  role: "LIGHT",
  swappedPlayerIdA: "UUID",
  swappedPlayerIdB: "UUID"
}
Server → 全員 : GAME_STATE_SYNC
Server → 全員 : TURN_CHANGED  ※ライトの手番終了
```

> **制約：** ライトの能力は1ゲームに1回限り。2回目の `ACTION_USE_ABILITY` には `ABILITY_ALREADY_USED` エラーを返す。グレイ保護中のプレイヤーを `targetPlayerIdA` または `targetPlayerIdB` に指定した場合は `TARGET_IS_PROTECTED` エラーを返す。

---

### 6-12. 再ゲーム（リマッチ）フロー

```
ゲーム終了後、ホストが同じメンバー・同じ設定で再ゲームを開始する

【ホスト側】
Client(ホスト) → Server : REMATCH_REQUEST

【全員へ通知】
Server → 全員 : NOTIFY_REMATCH_STARTING
  ※フロントエンドはこれを受け取りルーム待機画面へ遷移する
  ※切断していないプレイヤーはルームIDを再入力せずそのまま待機画面に戻る
  ※ゲーム設定（役職・カード枚数・毒リンゴ数）は前ゲームをそのまま引き継ぐ

【サーバー側の処理】
  - rooms.status を WAITING に更新
  - 切断中（isConnected = false）のプレイヤーを players テーブルから DELETE
  - 残プレイヤーの seat_order を 0 始まりで連番に詰め直す
  - インメモリの GameState を破棄

【ホストがゲーム開始した場合】
Client(ホスト) → Server : GAME_START
Server → 全員 : GAME_STARTED
Server → 各自 : YOUR_INITIAL_INFO  ※新しい役職・手札・リンゴ情報
```

> **設定変更について：** 再ゲーム時に設定変更はできない。変更したい場合は「ルームを解散する」からスタート画面に戻り、新しいルームを作成する。切断中プレイヤーが除外されて役職数と参加人数が一致しなくなった場合もゲーム開始不可となるため、同様に新しいルームを作成する。

---

## 7. エラーコード一覧

| コード | 説明 |
|-------|------|
| `NOT_YOUR_TURN` | 自分の手番ではない |
| `INVALID_PHASE` | 現在のフェイズでは実行できない |
| `CARD_NOT_IN_HAND` | 指定したカードを手札に持っていない |
| `CANNOT_DISCARD_CURSED_RING` | 呪いの指輪は捨てられない |
| `INVALID_TARGET` | 無効なターゲット（存在しない・死亡済み） |
| `TARGET_IS_PROTECTED` | 対象プレイヤーはグレイの保護中 |
| `ABILITY_ALREADY_USED` | 役職能力は既に使用済み（グレイ・ライト） |
| `INVALID_CARD_COUNT` | いただきます。の枚数が不正（山札残枚数超過など） |
| `NOT_HOST` | ホスト限定の操作をホスト以外が実行しようとした |
| `ROOM_NOT_FOUND` | ルームが存在しない |
| `GAME_NOT_STARTED` | ゲームが開始されていない |
| `UNAUTHORIZED` | 認証エラー（不正なplayerId） |
| `REQUEST_EXPIRED` | タイムアウトにより既に自動アクションが完了しているため、送信した `RESPONSE_*` は無効 |
| `DUPLICATE_USERNAME` | 同一ルーム内に同じユーザー名のプレイヤーが既に存在する |

---

## 附記：未決定事項・要確認事項

現時点で未確定の設計事項はありません。
