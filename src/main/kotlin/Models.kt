package com.example

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.*

// ============================================================================
// Enum定義
// ============================================================================

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

enum class Faction {
    SNOW_WHITE_FACTION,  // 白雪姫陣営
    QUEEN_FACTION,       // 女王陣営
    THIRD_FACTION        // 第三陣営
}

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

enum class CardLocation {
    DECK,    // 山札
    HAND,    // 手札
    DISCARD  // 捨て山
}

enum class GamePhase {
    STORY,           // ストーリーフェイズ
    LAST_TURN,       // 最後の手番フェイズ
    ENDING_QUEEN,    // エンディング：女王の特権処理中
    ENDING_REVEAL,   // エンディング：全員リンゴ公開中
    FINISHED         // ゲーム終了
}

enum class RoomStatus {
    WAITING,   // 参加者待機中
    IN_GAME,   // ゲーム進行中
    FINISHED   // ゲーム終了
}

// ============================================================================
// Serializable型定義（WebSocket通信ペイロード用）
// ============================================================================

// JSON用のメッセージクラス（Stringで送受信）
data class WsMessage(
    val type: String,
    val payload: Map<String, Any?> = emptyMap()
)

@Serializable
data class PlayerSummary(
    val playerId: String,       // UUID
    val userName: String,
    val seatOrder: Int,
    val isAlive: Boolean,
    val isConnected: Boolean,
    val isRoleRevealed: Boolean,
    val role: String? = null,   // isRoleRevealed=true の場合のみ含む
    val isProtected: Boolean,
    val skipNextTurn: Boolean,
    val applePreferenceAnswer: Boolean? = null,
    val mushroomPreferenceAnswer: Boolean? = null
)

@Serializable
data class AppleSummary(
    val appleId: String,
    val currentHolderPlayerId: String,
    val isPoisoned: Boolean? = null,  // 知っている場合のみセット
    val isPubliclyRevealed: Boolean
)

// ============================================================================
// インメモリゲーム状態用のデータクラス
// ============================================================================

data class GameState(
    val gameId: UUID,
    val roomId: UUID,
    val phase: GamePhase,
    val turnOrder: List<UUID>,
    val currentTurnIndex: Int,
    val lastTurnStartPlayerIndex: Int? = null,
    val players: Map<UUID, GamePlayer> = emptyMap(),
    val apples: List<Apple> = emptyList(),
    val cards: Map<UUID, GameCard> = emptyMap(),
    val deckOrder: List<UUID> = emptyList(),
    val discardOrder: List<UUID> = emptyList(),
    val queenSpecialDone: Boolean = false
)

data class GamePlayer(
    val playerId: UUID,
    val userName: String,
    val seatOrder: Int,
    val role: Role,
    val faction: Faction,
    val isAlive: Boolean = true,
    val isConnected: Boolean = true,
    val isRoleRevealed: Boolean = false,
    val grayAbilityUsed: Boolean = false,
    val lightAbilityUsed: Boolean = false,
    val isProtected: Boolean = false,
    val skipNextTurn: Boolean = false,
    val applePreferenceAnswer: Boolean? = null,
    val mushroomPreferenceAnswer: Boolean? = null
)

data class Apple(
    val appleId: UUID,
    val isPoisoned: Boolean,
    val currentHolderPlayerId: UUID,
    val isPubliclyRevealed: Boolean = false,
    val privatelyKnownBy: Set<UUID> = emptySet()
)

data class GameCard(
    val cardId: UUID,
    val cardType: CardType,
    val location: CardLocation,
    val holderPlayerId: UUID? = null
)

// データベース用のモデル（シリアライズ不要）
data class Room(
    val id: UUID,
    val roomCode: String,
    val hostPlayerId: UUID?,
    val status: RoomStatus,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class RoomSettings(
    val id: UUID,
    val roomId: UUID,
    val poisonAppleCount: Int,
    val roles: List<Role>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class RoomCardSettings(
    val id: UUID,
    val roomId: UUID,
    val cardType: CardType,
    val count: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

data class PlayerRecord(
    val id: UUID,
    val roomId: UUID,
    val userName: String,
    val seatOrder: Int,
    val isHost: Boolean,
    val isConnected: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)



