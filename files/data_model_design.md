# データモデル設計書：白雪姫のアップルルーレット

**バージョン：** 2.3  
**作成日：** 2026年5月10日  

---

## 目次

1. [設計方針](#1-設計方針)
2. [Enum定義](#2-enum定義)
3. [PostgreSQLテーブル設計（永続化）](#3-postgresqlテーブル設計永続化)
4. [インメモリ状態設計（ゲーム中）](#4-インメモリ状態設計ゲーム中)
5. [データの流れ概要](#5-データの流れ概要)
6. [設計上の補足と注意点](#6-設計上の補足と注意点)

---

## 1. 設計方針

### 1-1. 永続化とインメモリの分離

このゲームはリアルタイム性が高く、1ゲームの状態変化が非常に多い（手番ごとにリンゴ・手札・フェイズが更新される）ため、以下の方針で管理を分離する。

| 種別 | 保存先 | 対象 |
|-----|--------|------|
| 永続データ | PostgreSQL | ルーム情報・設定・プレイヤー情報 |
| ゲーム中の状態 | サーバーメモリ（Kotlin object） | フェイズ・手番・手札・リンゴ位置・プレイヤー状態 |

- ゲーム中の状態はサーバーメモリで高速に管理し、都度DBに書き込まない
- ゲーム結果（勝利陣営・プレイヤー別結果）は保存しない。勝敗はリアルタイムで表示するのみ
- サーバー再起動によるゲーム状態の消失は許容する（Renderの特性上、開発フェーズでは許容範囲）

### 1-2. リンゴの追跡

リンゴは移動しても個体を識別し続ける必要がある（一度判明したリンゴは追跡可能）。そのため各リンゴにはユニークIDを付与し、「現在の持ち主」をIDで参照する形で追跡する。

### 1-3. リンゴの公開状態

リンゴの毒/通常の情報は、以下の4種類の公開状態を持つ。

| 状態 | 説明 | 発生タイミング |
|-----|------|-------------|
| 非公開 | 誰も知らない | — |
| 個別公開（本人） | 持ち主本人が知っている | **ゲーム開始時（全員）**、④自分のリンゴの確認 |
| 個別公開（第三者） | 持ち主以外の特定プレイヤーが知っている | ブラックの初期情報 |
| 全体公開 | 全プレイヤーが知っている | 包丁カード使用時、エンディングフェイズ |

---

## 2. Enum定義

### Role（役職）

```kotlin
enum class Role {
    SNOW_WHITE,  // 白雪姫
    QUEEN,       // 女王
    GREEN,       // グリーン
    BLACK,       // ブラック
    BROWN,       // ブラウン
    GRAY,        // グレイ
    NAVY,        // ネイビー
    ROSE,        // ロゼ
    LIGHT        // ライト
}
```

### Faction（陣営）

```kotlin
enum class Faction {
    SNOW_WHITE_FACTION,  // 白雪姫陣営
    QUEEN_FACTION,       // 女王陣営
    THIRD_FACTION        // 第三陣営
}
```

### CardType（カード種別）

```kotlin
enum class CardType {
    APPLE_QUESTION,      // リンゴは好き？
    MUSHROOM_QUESTION,   // きのこは好き？
    ITADAKIMASU,         // いただきます。
    ROULETTE_1,          // アップルルーレット！
    ROULETTE_2,          // アップルルーレット！！
    ROULETTE_3,          // アップルルーレット！！！
    KNIFE,               // 包丁
    CURSED_RING,         // 呪いの指輪
    POISON_COMB,         // 毒の櫛
    KNIGHT,              // 騎士
    GUARD,               // ガード
    ROPE,                // ロープ
    PRESENT_EXCHANGE     // プレゼント交換
}
```

### CardLocation（カードの場所）

```kotlin
enum class CardLocation {
    DECK,    // 山札
    HAND,    // 手札
    DISCARD  // 捨て山
}
```

### GamePhase（ゲームフェイズ）

```kotlin
enum class GamePhase {
    STORY,           // ストーリーフェイズ
    LAST_TURN,       // 最後の手番フェイズ
    ENDING_QUEEN,    // エンディング：女王の特権処理中
    ENDING_REVEAL,   // エンディング：全員リンゴ公開中
    FINISHED         // ゲーム終了
}
```

### RoomStatus（ルーム状態）

```kotlin
enum class RoomStatus {
    WAITING,   // 参加者待機中
    IN_GAME,   // ゲーム進行中
    FINISHED   // ゲーム終了
}
```

### WinFaction（勝利陣営）

`Faction` と値が同一のため独立したEnumは定義しない。勝利陣営の判定には `Faction` の値をそのまま使用する。

```kotlin
// WinFaction は定義しない。勝利陣営の判定には Faction を使用する。
// GAME_RESULT.winFaction → Faction.SNOW_WHITE_FACTION / QUEEN_FACTION / THIRD_FACTION
```

---

## 3. PostgreSQLテーブル設計（永続化）

### 3-1. rooms（ルーム）

ルーム情報と状態を管理する。

```sql
CREATE TABLE rooms (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_code     VARCHAR(6) NOT NULL UNIQUE,   -- 参加用コード（英数字6桁）
    host_player_id UUID,                         -- ホストプレイヤーのID。循環依存のためFK制約は後述のALTER TABLEで追加
    status        VARCHAR(20) NOT NULL DEFAULT 'WAITING',  -- RoomStatus
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);

-- players テーブル作成後に追加（rooms → players の循環依存を回避）
-- ALTER TABLE rooms
--     ADD CONSTRAINT fk_rooms_host_player
--     FOREIGN KEY (host_player_id) REFERENCES players(id) ON DELETE SET NULL;
```

### 3-2. room_settings（ルーム設定）

ルーム作成時に設定するゲームパラメータ。

```sql
CREATE TABLE room_settings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id             UUID NOT NULL UNIQUE REFERENCES rooms(id) ON DELETE CASCADE,  -- 1ルームに1設定のみ
    poison_apple_count  INTEGER NOT NULL DEFAULT 2,  -- 毒リンゴ枚数
    roles               TEXT[] NOT NULL,             -- 使用する役職リスト（Role enum の配列）
    created_at          TIMESTAMP NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP NOT NULL DEFAULT now()  -- 最終更新日時
);
```

### 3-3. room_card_settings（カード枚数設定）

カード種別ごとの使用枚数設定。

```sql
CREATE TABLE room_card_settings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id     UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    card_type   VARCHAR(30) NOT NULL,   -- CardType
    count       INTEGER NOT NULL,
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),  -- 最終更新日時
    UNIQUE (room_id, card_type)
);
```

### 3-4. players（プレイヤー）

ルームに参加しているプレイヤーの情報。

```sql
CREATE TABLE players (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id      UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_name    VARCHAR(12) NOT NULL,
    seat_order   INTEGER NOT NULL,    -- 座席順（0始まり）。アップルルーレットの移動方向の基準
    is_host      BOOLEAN NOT NULL DEFAULT false,
    is_connected BOOLEAN NOT NULL DEFAULT true,
    created_at   TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (room_id, user_name),      -- 同一ルーム内でユーザー名重複禁止
    UNIQUE (room_id, seat_order)
);
```

---

## 4. インメモリ状態設計（ゲーム中）

ゲーム進行中の状態はサーバーメモリ上のKotlin data classで管理する。サーバー上で `GameId → GameState` のMapとして保持する。

### 4-1. GameState（ゲーム全体の状態）

```kotlin
data class GameState(
    val gameId: UUID,                        // ゲームセッションの識別子（インメモリのみ。DBには保存しない）
    val roomId: UUID,
    val phase: GamePhase,
    val turnOrder: List<UUID>,              // 手番順のプレイヤーIDリスト（seat_orderベース）
    val currentTurnIndex: Int,              // turnOrder上の現在の手番インデックス
    val lastTurnStartPlayerIndex: Int?,     // 最後の手番フェイズの開始プレイヤーのインデックス（STORYフェイズ中はnull）
    val players: Map<UUID, GamePlayer>,     // playerId → GamePlayer
    val apples: List<Apple>,                // リンゴ一覧（各AppleのcurrentHolderPlayerIdで持ち主を特定）
    val cards: Map<UUID, GameCard>,         // cardId → GameCard（全カードの単一ソース）
    val deckOrder: List<UUID>,              // 山札の順番（先頭が次に引くカード）。cardIdのリスト
    val discardOrder: List<UUID>,           // 捨て山の順番（末尾が最新）。cardIdのリスト
    val queenSpecialDone: Boolean = false   // 女王の特権処理が完了したか
)
```

**カード管理の方針：**
- 全カードは `cards: Map<UUID, GameCard>` で一元管理する（単一のソース）
- `deckOrder` / `discardOrder` はカードの順序のみを保持するIDリスト
- 手札は `GameCard.location == HAND && GameCard.holderPlayerId == playerId` で導出する
- カードの移動（引く・使う・捨てる・交換）は必ず `cards` の該当エントリを更新して行う

### 4-2. GamePlayer（ゲーム中のプレイヤー状態）

```kotlin
data class GamePlayer(
    val playerId: UUID,
    val userName: String,
    val seatOrder: Int,
    val role: Role,
    val faction: Faction,
    val isAlive: Boolean = true,
    val isConnected: Boolean = true,       // WebSocket接続中か（切断時はfalse・死亡扱い）

    // 役職の公開状態
    val isRoleRevealed: Boolean = false,  // ライトの能力などで役職が全体公開されたか

    // 役職能力の使用状況
    val grayAbilityUsed: Boolean = false,     // グレイ：能力使用済みか
    val lightAbilityUsed: Boolean = false,    // ライト：能力使用済みか

    // グレイの保護状態
    val isProtected: Boolean = false,         // グレイ能力が発動中（交換対象外）か
                                               // ※グレイ本人の手番開始時に false へリセットする
                                               //   ただしロープでスキップされた手番はリセット対象外
                                               //   （スキップされない次の手番開始時にリセット）

    // ロープによるスキップ
    val skipNextTurn: Boolean = false,        // 次の手番をスキップするか

    // 好み回答履歴（UIに表示するため保持）
    val applePreferenceAnswer: Boolean? = null,    // リンゴは好き？の回答（null=未回答）
    val mushroomPreferenceAnswer: Boolean? = null  // きのこは好き？の回答（null=未回答）
)
```

> **`isConnected` と `isAlive` の連動ルール：**
> - WebSocket切断を検知した時点で `isConnected = false` にのみ更新し、1分のタイマーを開始する
> - **白雪姫（`role == SNOW_WHITE`）が切断した場合のみ**、即座にゲーム強制終了処理を行う（5-3参照）
> - 白雪姫以外の切断で **1分以内に再接続**した場合は `isConnected = true` に戻しゲームを続行する
> - 白雪姫以外の切断で **1分経過しても再接続なし**の場合、`isConnected = false` かつ `isAlive = false` を同時にcopyパターンで更新し、死亡扱いとする

> **手札の取得方法：** `GamePlayer`に`hand`フィールドは持たない。プレイヤーの手札は`GameState.cards`から`location == HAND && holderPlayerId == playerId`で導出する。カードの単一ソースを`GameState.cards`に集約することで状態の不整合を防ぐ。

**役職とFactionのマッピング（初期化時に設定）**

| Role | Faction |
|------|---------|
| SNOW_WHITE | SNOW_WHITE_FACTION |
| GREEN | SNOW_WHITE_FACTION |
| BROWN | SNOW_WHITE_FACTION |
| GRAY | SNOW_WHITE_FACTION |
| LIGHT | SNOW_WHITE_FACTION |
| QUEEN | QUEEN_FACTION |
| BLACK | QUEEN_FACTION |
| NAVY | QUEEN_FACTION |
| ROSE | THIRD_FACTION |

### 4-3. Apple（リンゴ）

```kotlin
data class Apple(
    val appleId: UUID,                          // リンゴの個体ID（移動しても変わらない）
    val isPoisoned: Boolean,                    // 毒かどうか
    val currentHolderPlayerId: UUID,            // 現在の持ち主のプレイヤーID

    // 公開状態
    val isPubliclyRevealed: Boolean = false,    // 全員に公開済みか（包丁・エンディング）
    val privatelyKnownBy: Set<UUID> = emptySet() // 個別に知っているプレイヤーIDのセット
                                                 // （ゲーム開始時の本人・④自分確認・ブラックの初期情報）
)
```

> **更新パターン：** `Apple` は全フィールドが `val` のイミュータブルな data class とする。持ち主の変更や公開状態の更新は `apple.copy(currentHolderPlayerId = newId)` のようにcopyパターンで新しいインスタンスを生成し、`GameState.apples` ごと差し替えて行う。

**初期化時の公開状態の設定：**
- **全プレイヤー：** 各自が持つリンゴの `privatelyKnownBy` に、そのリンゴの持ち主プレイヤーIDを追加する（ゲーム開始時に自分のリンゴの毒/通常を知ることができる）
- **ブラック：** ブラック役のプレイヤーIDを**全毒リンゴ**の `privatelyKnownBy` に追加する
- 結果として、毒リンゴを持つプレイヤーのリンゴの `privatelyKnownBy` には「持ち主本人」と「ブラック」の両方が含まれる

**交換・移動後の扱い：**
- リンゴが交換・アップルルーレットで移動しても `privatelyKnownBy` はリセットしない（過去に知った情報は保持）
- ただし新しい持ち主は自動的に `privatelyKnownBy` に追加**されない**。新しい持ち主が知るためには④自分のリンゴの確認アクションが必要

### 4-4. GameCard（カード）

```kotlin
data class GameCard(
    val cardId: UUID,
    val cardType: CardType,
    val location: CardLocation,             // DECK / HAND / DISCARD
    val holderPlayerId: UUID?               // HAND の場合に持ち主のプレイヤーID。DECK/DISCARDはnull
)
```

- 全カードは `GameState.cards: Map<UUID, GameCard>` で一元管理する
- 山札の順序は `GameState.deckOrder: List<UUID>`（先頭が次に引くカード）
- 捨て山の順序は `GameState.discardOrder: List<UUID>`（末尾が最新）
- カードの場所変更は必ず `cards` の該当エントリを更新して行う（`deckOrder`/`discardOrder`も合わせて更新）

---

## 5. データの流れ概要

### 5-1. ゲーム開始までの流れ

```
1. ホストが rooms, room_settings, room_card_settings をDBに保存
2. 参加者が players テーブルに登録（seat_orderをルーム参加順に割り当て）
3. ホストがゲーム開始 → rooms.status を IN_GAME に更新
4. サーバーがメモリ上に GameState を初期化：
   - 役職をシャッフルして各プレイヤーに割り当て
   - リンゴをシャッフルして各プレイヤーに割り当て（appleId付与）
   - 各リンゴの privatelyKnownBy に「持ち主本人のプレイヤーID」を追加（全員が自分のリンゴを知る）
   - ブラックの privatelyKnownBy に全毒リンゴの情報を追加
   - カードをシャッフルして `cards`（Map）と `deckOrder`（IDリスト）を構成
   - 各プレイヤーに手札を1枚配る（該当カードの location を HAND に、holderPlayerId を設定）
   - 最初の手番プレイヤーをランダムに決定
5. 各プレイヤーへ初期情報をWebSocketで送信：
   - 全員：自分の役職・自分のリンゴが毒か通常か
   - グリーン：白雪姫が誰か（`GameState.players` から `role == SNOW_WHITE` のプレイヤーIDを導出して送信。専用フィールドは持たず、常にこの方法で導出する）
   - ブラック：毒リンゴを持つプレイヤーが誰か
```

### 5-2. 手番中の状態更新

```
プレイヤーの行動
 → WebSocketでサーバーへ送信
 → サーバー側で GameState を更新（インメモリ）
 → 更新後の状態を全プレイヤーへ WebSocket でブロードキャスト
   （各プレイヤーへ送る情報は権限に応じてフィルタリング）
```

**情報フィルタリングの原則：**

| 情報 | 送信対象 |
|-----|---------|
| 自分の役職 | 本人のみ |
| 他プレイヤーの役職 | 原則なし（ライト公開・グリーン特権を除く） |
| 公開済みの役職（`isRoleRevealed=true`） | 全員（ライト能力発動時にブロードキャスト） |
| 自分の手札 | 本人のみ |
| 自分のリンゴの毒/通常 | 本人のみ（ゲーム開始時に送信。交換後は④確認アクションで再取得） |
| 他プレイヤーのリンゴの毒/通常 | `isPubliclyRevealed=true` → 全員。`privatelyKnownBy` に含まれるプレイヤーのみ |
| グリーンへの白雪姫情報 | グリーン本人のみ（ゲーム開始時に1回送信） |
| ブラックへの毒リンゴ情報 | ブラック本人のみ（ゲーム開始時・交換・ルーレット後も継続して送信） |
| プレイヤーの生死状態（`isAlive`） | 全員 |
| プレイヤーの接続状態（`isConnected`） | 全員（切断時にブロードキャスト） |
| 好み回答（`applePreferenceAnswer` / `mushroomPreferenceAnswer`） | 全員（回答のたびにブロードキャスト） |
| 捨て山の内容 | 全員 |
| 現在の手番プレイヤー | 全員 |

> **`isConnected` と `isAlive` の連動ルール：**
> - WebSocket切断を検知した時点で `isConnected = false` にのみ更新し、1分のタイマーを開始する
> - **白雪姫・白雪姫以外ともに**1分以内に再接続した場合は `isConnected = true` に戻しゲームを続行する
> - 1分経過しても再接続がなかった場合、`isConnected = false` かつ `isAlive = false` を同時にcopyパターンで更新し、死亡扱いとする
> - **白雪姫が1分経過しても再接続しなかった場合のみ**、死亡更新後に即座にゲーム強制終了処理を行う（下記5-3参照）

### 5-3. ゲーム終了時の流れ

**通常終了（エンディングフェイズを経由した場合）**

```
1. GameState.phase が FINISHED になる
2. 勝利陣営を判定し GAME_RESULT を全員へ送信
3. rooms.status を FINISHED に更新
4. インメモリの GameState を破棄
```

**強制終了（白雪姫が1分再接続しなかった場合）**

```
1. 白雪姫の1分タイムアウト確定
   → isConnected = false, isAlive = false に更新
2. NOTIFY_PLAYER_DIED { playerId: 白雪姫ID, cause: "DISCONNECTED" } を全員へ送信
3. GAME_RESULT { winFaction: "QUEEN_FACTION", reason: "SNOW_WHITE_DISCONNECTED", players } を全員へ送信
   ※エンディングフェイズ（ENDING_QUEEN / ENDING_REVEAL）はスキップ
   ※GAME_RESULT.players にはサーバーが保持する全役職・全リンゴ情報を含めて送信する
4. rooms.status を FINISHED に更新
5. インメモリの GameState を破棄
```

> **ゲーム結果の永続化について：** 勝敗結果はDBに保存しない。`GAME_RESULT` イベントでリアルタイムに全員へ送信するのみ。

### 5-4. 再ゲーム（リマッチ）時の流れ

```
1. ホストが REMATCH_REQUEST を送信 → rooms.status を WAITING に更新
2. 前ゲームで切断により isConnected = false のままのプレイヤーを
   players テーブルから DELETE する
   ※削除後に参加人数が room_settings.roles の配列長を下回る場合はゲーム開始不可
   ※この場合ホストはルームを解散して新しい部屋を作り直す必要がある
3. 残プレイヤーの seat_order を 0 始まりで連番に UPDATE する（欠番を詰める）
   例：seat_order が [0, 2, 3] → [0, 1, 2] に更新
4. NOTIFY_REMATCH_STARTING を全員へ送信 → フロントエンドがルーム待機画面へ遷移
   ※ゲーム設定（役職・カード枚数・毒リンゴ数）は前ゲームをそのまま引き継ぐ
   ※設定を変更したい場合は新しいルームを作成する

【ホストがゲーム開始した場合】
5. GAME_START を受信 → rooms.status を IN_GAME に更新
6. 新しい GameState をインメモリに初期化（役職・リンゴ・カードをシャッフルして再配布）
7. GAME_STARTED → YOUR_INITIAL_INFO を各プレイヤーへ送信
```

> **seat_order の詰め直し：** アップルルーレットの移動計算は `seat_order` の連番を前提とする（6-1参照）。プレイヤー除外で欠番が生じた場合は必ずUPDATEで詰め直す。

---

## 6. 設計上の補足と注意点

### 6-1. アップルルーレットの実装

アップルルーレット使用時は `Apple.currentHolderPlayerId` を `seat_order`（座席の物理的な並び順）に従ってずらす。

- 死亡プレイヤーは座席順から除外してローテーションを計算する
- ずらした後もリンゴの `appleId` と `isPoisoned`・`isPubliclyRevealed`・`privatelyKnownBy` は変わらない
- `turnOrder`（手番順）とは独立しており、`seat_order` の昇順を時計回りとして扱う

### 6-2. 呪いの指輪の手札制約

呪いの指輪（`CardType.CURSED_RING`）は手札から捨てることができない。サーバー側で以下の操作可否を制御する。

| 操作 | 可否 | 備考 |
|-----|-----|------|
| ①山札を引いた後に呪いの指輪を捨て山へ移動 | ❌ ブロック | 新たに引いたカードを使用/廃棄しなければならない |
| 手番でカードを使用せず捨て山へ廃棄 | ❌ ブロック | 呪いの指輪は「使用」も「廃棄」もできない |
| ②手札の交換で他プレイヤーへ渡す | ✅ 許可 | `holderPlayerId` の付け替えのみ行う |
| プレゼント交換で他プレイヤーへ渡す | ✅ 許可 | `holderPlayerId` の付け替えのみ行う |

- 呪いの指輪を持つプレイヤーが①山札を引いた場合、そのプレイヤーの手札（`cards`から導出）は一時的に2枚になる。このとき新たに引いたカードを使用するか捨て山へ廃棄することで手札を1枚に戻す（呪いの指輪は手元に残る）
- 受け渡し後、新たな持ち主の `holderPlayerId` が更新される。新たな持ち主は次の自分の手番から効果対象になる

### 6-3. グレイの保護対象

`isProtected = true` のプレイヤーは以下の操作でターゲットに指定できない：
- ③リンゴの交換
- ②手札の交換
- プレゼント交換（の対象として指定）
- ライトの能力によるリンゴ交換の対象

保護対象**外**（`isProtected` に関わらず対象にできる）：
- 包丁（リンゴ公開）
- 毒の櫛（死亡）
- ロープ（スキップ）
- リンゴは好き？ / きのこは好き？（回答要求）

### 6-4. ロープによるスキップの管理

`skipNextTurn = true` のプレイヤーに手番が回ってきた場合、サーバー側で自動的に手番をスキップし `skipNextTurn = false` にリセットする。フェイズをまたいでも `skipNextTurn` フラグはリセットされない。

**グレイの保護中にスキップされた場合：**
グレイの保護（`isProtected = true`）は「次の自分の手番が来るまで」有効だが、ロープによってその手番がスキップされた場合、スキップされた手番はグレイが行動できる手番とはみなさない。そのため `isProtected` はスキップされた手番では解除されず、次の（スキップされない）手番が来た時点で解除される。

```
例：グレイが能力発動 → ロープでスキップ → 次の手番開始時に isProtected = false にリセット
```

### 6-5. 座席順とターン順の関係

- `seat_order`（0始まり）は固定。座席の物理的な並び順であり、アップルルーレットの移動方向の基準になる
- `turnOrder`（`GameState` 内）はゲーム開始時に `seat_order` をもとに生成するリスト。ランダムに決定された最初の手番プレイヤーを起点として時計回りに並べる
- アップルルーレットは `seat_order` ベースで移動し、`turnOrder` とは独立している

### 6-6. 騎士の有効条件

サーバー側で毒の櫛の対象が指定された場合、以下の両条件をチェックする：

```
対象プレイヤーのRole == SNOW_WHITE
AND
対象プレイヤーの手札に CardType == KNIGHT が存在する
```

両方満たす場合のみ死亡を無効化する。

### 6-7. ENDING_QUEENフェイズの自動スキップ

フェイズが `ENDING_QUEEN` に遷移した時点で、サーバーは女王の生存状態を確認する。

```
if (queenPlayer.isAlive == false) {
    // 女王が死亡済みの場合、特権処理をスキップして即座に次のフェイズへ
    queenSpecialDone = true
    phase = ENDING_REVEAL
}
```

女王が毒の櫛・呪いの指輪によって最後の手番フェイズ中に死亡していた場合、`ENDING_QUEEN` は発動せず `ENDING_REVEAL`（全員リンゴ公開）に自動遷移する。

### 6-8. 包丁使用時のApple更新

包丁（`CardType.KNIFE`）が使用されると、サーバーは指定されたプレイヤーが現在持っているリンゴを特定し、そのリンゴの公開状態を更新する。

```
// 包丁の対象プレイヤーが持っているリンゴを取得
val targetApple = apples.first { it.currentHolderPlayerId == targetPlayerId }

// 全体公開フラグを true に更新（copyパターン）
val updatedApple = targetApple.copy(isPubliclyRevealed = true)
```

- `isPubliclyRevealed = true` になったリンゴの毒/通常情報は、以降の状態ブロードキャスト時に全プレイヤーへ送信される
- リンゴが移動（交換・ルーレット）した後も `isPubliclyRevealed = true` は維持されるため、一度公開されたリンゴはどこへ移っても全員に見え続ける
- グレイの保護（`isProtected = true`）は包丁の対象指定を防がない（6-3参照）

### 6-9. 勝利条件の判定ロジック

エンディングフェイズの `ENDING_REVEAL` 完了後にサーバーが勝利陣営を決定する。

```kotlin
val rosePlayer = players.values.find { it.role == Role.ROSE }

val winFaction = when {
    // 白雪姫が死亡 → 女王陣営勝利
    snowWhitePlayer.isAlive == false -> Faction.QUEEN_FACTION

    // ロゼが存在しない、または存在して死亡 → 白雪姫陣営勝利
    rosePlayer == null || rosePlayer.isAlive == false -> Faction.SNOW_WHITE_FACTION

    // 白雪姫が生存 かつ ロゼが生存 → 第三陣営勝利
    else -> Faction.THIRD_FACTION
}
```

> **ロゼ不在時の扱い：** `rosePlayer == null`（ロゼが選択されていない）の場合は「ロゼが死んでいる」条件を自動的に満たしたものとみなす。

### 6-10. 手番タイムアウト時のカード処理

手番の3分タイムアウト発生時、サーバーは以下のロジックでカードを処理する。

```kotlin
// タイムアウト発生時の手札を取得
val hand = cards.values.filter {
    it.location == CardLocation.HAND && it.holderPlayerId == currentTurnPlayerId
}

if (hand.size == 1) {
    // 通常（手札1枚）：そのカードを捨て山へ
    // ただし呪いの指輪は捨て山に送れない → 何もしない（手元に残る）
    if (hand.first().cardType != CardType.CURSED_RING) {
        discardCard(hand.first().cardId)
    }
} else if (hand.size == 2) {
    // 山札を引いた後の状態（手札2枚）
    val cursedRing = hand.find { it.cardType == CardType.CURSED_RING }
    if (cursedRing != null) {
        // 呪いの指輪を持っている → もう片方（新たに引いたカード）を捨て山へ
        val otherCard = hand.first { it.cardId != cursedRing.cardId }
        discardCard(otherCard.cardId)
    } else {
        // 通常の2枚 → 使用中・選択中だったカードを捨て山へ
        // （サーバーはどちらを使用中だったかを追跡）
        discardCard(activeCardId)
    }
}
```

---

## 附記：未決定事項・要確認事項

現時点で未確定の設計事項はありません。