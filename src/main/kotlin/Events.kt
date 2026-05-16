package com.example

import kotlinx.serialization.Serializable

// ============================================================================
// WebSocket イベント型定義
// ============================================================================

// CLIENT -> SERVER イベント
object ClientEvents {
    const val GAME_START = "GAME_START"
    const val REMATCH_REQUEST = "REMATCH_REQUEST"
    const val ACTION_DRAW_CARD = "ACTION_DRAW_CARD"
    const val ACTION_USE_CARD = "ACTION_USE_CARD"
    const val ACTION_DISCARD_CARD = "ACTION_DISCARD_CARD"
    const val ACTION_EXCHANGE_HAND = "ACTION_EXCHANGE_HAND"
    const val ACTION_EXCHANGE_APPLE = "ACTION_EXCHANGE_APPLE"
    const val ACTION_CHECK_OWN_APPLE = "ACTION_CHECK_OWN_APPLE"
    const val ACTION_USE_ABILITY = "ACTION_USE_ABILITY"
    const val RESPONSE_PREFERENCE = "RESPONSE_PREFERENCE"
    const val RESPONSE_QUEEN_EXCHANGE = "RESPONSE_QUEEN_EXCHANGE"
}

// SERVER -> CLIENT イベント
object ServerEvents {
    // 接続・ルーム
    const val PLAYER_JOINED = "PLAYER_JOINED"
    const val PLAYER_DISCONNECTED = "PLAYER_DISCONNECTED"

    // ゲーム開始
    const val GAME_STARTED = "GAME_STARTED"
    const val YOUR_INITIAL_INFO = "YOUR_INITIAL_INFO"

    // ゲーム状態同期
    const val GAME_STATE_SYNC = "GAME_STATE_SYNC"
    const val YOUR_APPLE_STATUS = "YOUR_APPLE_STATUS"
    const val BLACK_APPLE_UPDATE = "BLACK_APPLE_UPDATE"

    // アクション通知
    const val NOTIFY_DRAW_CARD = "NOTIFY_DRAW_CARD"
    const val NOTIFY_USE_CARD = "NOTIFY_USE_CARD"
    const val NOTIFY_DISCARD_CARD = "NOTIFY_DISCARD_CARD"
    const val NOTIFY_EXCHANGE_HAND = "NOTIFY_EXCHANGE_HAND"
    const val NOTIFY_EXCHANGE_APPLE = "NOTIFY_EXCHANGE_APPLE"
    const val NOTIFY_APPLE_PUBLICLY_REVEALED = "NOTIFY_APPLE_PUBLICLY_REVEALED"
    const val NOTIFY_ROULETTE = "NOTIFY_ROULETTE"
    const val NOTIFY_PREFERENCE_ANSWERED = "NOTIFY_PREFERENCE_ANSWERED"
    const val NOTIFY_PLAYER_DIED = "NOTIFY_PLAYER_DIED"
    const val NOTIFY_PLAYER_SKIPPED = "NOTIFY_PLAYER_SKIPPED"
    const val NOTIFY_GRAY_ABILITY_ACTIVATED = "NOTIFY_GRAY_ABILITY_ACTIVATED"
    const val NOTIFY_LIGHT_ABILITY_ACTIVATED = "NOTIFY_LIGHT_ABILITY_ACTIVATED"
    const val NOTIFY_KNIGHT_BLOCKED = "NOTIFY_KNIGHT_BLOCKED"
    const val NOTIFY_GUARD_ACTIVATED = "NOTIFY_GUARD_ACTIVATED"
    const val NOTIFY_TIMEOUT = "NOTIFY_TIMEOUT"

    // 入力要求
    const val REQUEST_PREFERENCE = "REQUEST_PREFERENCE"
    const val REQUEST_QUEEN_EXCHANGE = "REQUEST_QUEEN_EXCHANGE"

    // フェイズ・ターン
    const val PHASE_CHANGED = "PHASE_CHANGED"
    const val TURN_CHANGED = "TURN_CHANGED"

    // ゲーム終了
    const val GAME_RESULT = "GAME_RESULT"
    const val NOTIFY_REMATCH_STARTING = "NOTIFY_REMATCH_STARTING"

    // エラー
    const val ERROR = "ERROR"
}

// エラーコード
object ErrorCodes {
    const val NOT_YOUR_TURN = "NOT_YOUR_TURN"
    const val INVALID_PHASE = "INVALID_PHASE"
    const val CARD_NOT_IN_HAND = "CARD_NOT_IN_HAND"
    const val CANNOT_DISCARD_CURSED_RING = "CANNOT_DISCARD_CURSED_RING"
    const val INVALID_TARGET = "INVALID_TARGET"
    const val TARGET_IS_PROTECTED = "TARGET_IS_PROTECTED"
    const val ABILITY_ALREADY_USED = "ABILITY_ALREADY_USED"
    const val INVALID_CARD_COUNT = "INVALID_CARD_COUNT"
    const val NOT_HOST = "NOT_HOST"
    const val ROOM_NOT_FOUND = "ROOM_NOT_FOUND"
    const val GAME_NOT_STARTED = "GAME_NOT_STARTED"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val REQUEST_EXPIRED = "REQUEST_EXPIRED"
    const val DUPLICATE_USERNAME = "DUPLICATE_USERNAME"
}

// ============================================================================
// ロール・陣営のマッピング
// ============================================================================

fun Role.toFaction(): Faction = when (this) {
    Role.SNOW_WHITE -> Faction.SNOW_WHITE_FACTION
    Role.GREEN -> Faction.SNOW_WHITE_FACTION
    Role.BROWN -> Faction.SNOW_WHITE_FACTION
    Role.GRAY -> Faction.SNOW_WHITE_FACTION
    Role.LIGHT -> Faction.SNOW_WHITE_FACTION
    Role.QUEEN -> Faction.QUEEN_FACTION
    Role.BLACK -> Faction.QUEEN_FACTION
    Role.NAVY -> Faction.QUEEN_FACTION
    Role.ROSE -> Faction.THIRD_FACTION
}

// 好み設定（ロール別）
fun Role.getApplePreference(): Boolean = when (this) {
    Role.SNOW_WHITE -> true   // 好き（Yes固定）
    Role.QUEEN -> true         // 好き（Yes固定）
    Role.GREEN -> false        // 嫌い（No固定）
    Role.BLACK -> false        // 嫌い（No固定）
    Role.BROWN -> true         // 好き（Yes固定）
    Role.GRAY -> false         // 嫌い（No固定）
    Role.NAVY -> throw IllegalArgumentException("ネイビーは自由選択")
    Role.ROSE -> false         // 嫌い（No固定）
    Role.LIGHT -> true         // 好き（Yes固定）
}

fun Role.getMushroomPreference(): Boolean = when (this) {
    Role.SNOW_WHITE -> false   // 嫌い（No固定）
    Role.QUEEN -> false        // 嫌い（No固定）
    Role.GREEN -> true         // 好き（Yes固定）
    Role.BLACK -> true         // 好き（Yes固定）
    Role.BROWN -> true         // 好き（Yes固定）
    Role.GRAY -> true          // 好き（Yes固定）
    Role.NAVY -> throw IllegalArgumentException("ネイビーは自由選択")
    Role.ROSE -> false         // 嫌い（No固定）
    Role.LIGHT -> false        // 嫌い（No固定）
}

// ============================================================================
// ユーティリティ関数
// ============================================================================

fun generateRoomCode(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..6).map { chars.random() }.joinToString("")
}

// 参加人数別の推奨役職セット
fun getRecommendedRoles(playerCount: Int): List<Role> = when (playerCount) {
    4 -> listOf(Role.SNOW_WHITE, Role.QUEEN, Role.GREEN, Role.BLACK)
    5 -> listOf(Role.SNOW_WHITE, Role.QUEEN, Role.GREEN, Role.BLACK, Role.LIGHT)
    6 -> listOf(Role.SNOW_WHITE, Role.QUEEN, Role.GREEN, Role.BLACK, Role.BROWN, Role.LIGHT)
    7 -> listOf(Role.SNOW_WHITE, Role.QUEEN, Role.GREEN, Role.BLACK, Role.BROWN, Role.LIGHT, Role.ROSE)
    8 -> listOf(Role.SNOW_WHITE, Role.QUEEN, Role.GREEN, Role.BLACK, Role.BROWN, Role.NAVY, Role.LIGHT, Role.ROSE)
    9 -> Role.values().toList()
    else -> emptyList()
}

// 参加人数別の推奨毒リンゴ数
fun getRecommendedPoisonAppleCount(playerCount: Int): Int = when (playerCount) {
    4 -> 1
    5 -> 2
    6 -> 2
    7 -> 2
    8 -> 3
    9 -> 3
    else -> 0
}

// デフォルトカード枚数設定
fun getDefaultCardSettings(): Map<CardType, Int> = mapOf(
    CardType.APPLE_QUESTION to 5,
    CardType.MUSHROOM_QUESTION to 5,
    CardType.ITADAKIMASU to 3,
    CardType.ROULETTE_1 to 1,
    CardType.ROULETTE_2 to 1,
    CardType.ROULETTE_3 to 1,
    CardType.KNIFE to 1,
    CardType.CURSED_RING to 1,
    CardType.POISON_COMB to 1,
    CardType.KNIGHT to 1,
    CardType.GUARD to 1,
    CardType.ROPE to 1,
    CardType.PRESENT_EXCHANGE to 1
)

