package com.example

import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.time.Duration.Companion.seconds

class WebSocketManager {

    private val roomSessions = mutableMapOf<UUID, MutableMap<UUID, WebSocketSession>>()
    private val playerSessions = mutableMapOf<UUID, WebSocketSession>()

    fun addPlayerSession(roomId: UUID, playerId: UUID, session: WebSocketSession) {
        playerSessions[playerId] = session
        roomSessions.getOrPut(roomId) { mutableMapOf() }[playerId] = session
    }

    fun removePlayerSession(roomId: UUID, playerId: UUID) {
        playerSessions.remove(playerId)
        roomSessions[roomId]?.remove(playerId)
        if (roomSessions[roomId]?.isEmpty() == true) {
            roomSessions.remove(roomId)
        }
    }

    fun getPlayerSession(playerId: UUID): WebSocketSession? = playerSessions[playerId]

    fun getRoomSessions(roomId: UUID): List<WebSocketSession> {
        return roomSessions[roomId]?.values?.toList() ?: emptyList()
    }

    suspend fun broadcastToRoom(roomId: UUID, message: WsMessage) {
        val json = Json { ignoreUnknownKeys = true }
        val jsonString = buildJsonString(message)
        getRoomSessions(roomId).forEach { session ->
            try {
                session.send(jsonString)
            } catch (e: Exception) {
                // セッション切断時の例外処理
                println("ブロードキャスト失敗: ${e.message}")
            }
        }
    }

    suspend fun sendToPlayer(playerId: UUID, message: WsMessage) {
        val session = getPlayerSession(playerId) ?: return
        try {
            val jsonString = buildJsonString(message)
            session.send(jsonString)
        } catch (e: Exception) {
            println("プレイヤーへの送信失敗: ${e.message}")
        }
    }

    private fun buildJsonString(message: WsMessage): String {
        val json = StringBuilder()
        json.append("{\"type\":\"${escapeJsonString(message.type)}\",\"payload\":{")

        val payloadEntries = message.payload.entries.toList()
        payloadEntries.forEachIndexed { index, (key, value) ->
            json.append("\"${escapeJsonString(key)}\":")
            json.append(valueToJson(value))
            if (index < payloadEntries.size - 1) json.append(",")
        }

        json.append("}}")
        return json.toString()
    }

    private fun valueToJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escapeJsonString(value)}\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        is List<*> -> "[" + value.joinToString(",") { valueToJson(it) } + "]"
        is Map<*, *> -> {
            "{" + value.entries.joinToString(",") { (k, v) ->
                "\"${escapeJsonString(k.toString())}\":${valueToJson(v)}"
            } + "}"
        }
        is PlayerSummary -> valueToJson(mapOf(
            "playerId" to value.playerId,
            "userName" to value.userName,
            "seatOrder" to value.seatOrder,
            "isAlive" to value.isAlive,
            "isConnected" to value.isConnected,
            "isRoleRevealed" to value.isRoleRevealed,
            "role" to value.role,
            "isProtected" to value.isProtected,
            "skipNextTurn" to value.skipNextTurn,
            "applePreferenceAnswer" to value.applePreferenceAnswer,
            "mushroomPreferenceAnswer" to value.mushroomPreferenceAnswer
        ))
        is AppleSummary -> valueToJson(mapOf(
            "appleId" to value.appleId,
            "currentHolderPlayerId" to value.currentHolderPlayerId,
            "isPoisoned" to value.isPoisoned,
            "isPubliclyRevealed" to value.isPubliclyRevealed
        ))
        else -> "\"${escapeJsonString(value.toString())}\""
    }

    private fun escapeJsonString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

// ============================================================================
// WebSocket送信ヘルパー関数（イベント生成）
// ============================================================================

object WsHelpers {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun playerJoinedMessage(playerId: String, userName: String, seatOrder: Int): WsMessage {
        return WsMessage(
            type = ServerEvents.PLAYER_JOINED,
            payload = mapOf(
                "playerId" to playerId,
                "userName" to userName,
                "seatOrder" to seatOrder
            )
        )
    }

    fun playerDisconnectedMessage(playerId: String, userName: String): WsMessage {
        return WsMessage(
            type = ServerEvents.PLAYER_DISCONNECTED,
            payload = mapOf(
                "playerId" to playerId,
                "userName" to userName
            )
        )
    }

    fun gameStartedMessage(gameId: String, players: List<PlayerSummary>, firstTurnPlayerId: String): WsMessage {
        return WsMessage(
            type = ServerEvents.GAME_STARTED,
            payload = mapOf(
                "gameId" to gameId,
                "players" to players,
                "firstTurnPlayerId" to firstTurnPlayerId
            )
        )
    }

    fun yourInitialInfoMessage(
        role: String,
        faction: String,
        myApple: Map<String, Any>,
        myHand: List<Map<String, String>>,
        snowWhitePlayerId: String? = null,
        poisonAppleHolderIds: List<String>? = null
    ): WsMessage {
        val payload = mutableMapOf<String, Any?>(
            "role" to role,
            "faction" to faction,
            "myApple" to myApple,
            "myHand" to myHand
        )

        if (snowWhitePlayerId != null) {
            payload["snowWhitePlayerId"] = snowWhitePlayerId
        }

        if (poisonAppleHolderIds != null) {
            payload["poisonAppleHolderIds"] = poisonAppleHolderIds
        }

        return WsMessage(
            type = ServerEvents.YOUR_INITIAL_INFO,
            payload = payload
        )
    }

    fun gameStateSyncMessage(
        phase: String,
        currentTurnPlayerId: String,
        deckRemainingCount: Int,
        discardPile: List<Map<String, String>>,
        players: List<PlayerSummary>,
        apples: List<AppleSummary>,
        myHand: List<Map<String, String>>? = null
    ): WsMessage {
        val payload = mutableMapOf<String, Any?>(
            "phase" to phase,
            "currentTurnPlayerId" to currentTurnPlayerId,
            "deckRemainingCount" to deckRemainingCount,
            "discardPile" to discardPile,
            "players" to players,
            "apples" to apples
        )

        if (myHand != null) {
            payload["myHand"] = myHand
        }

        return WsMessage(
            type = ServerEvents.GAME_STATE_SYNC,
            payload = payload
        )
    }

    fun yourAppleStatusMessage(appleId: String, isPoisoned: Boolean): WsMessage {
        return WsMessage(
            type = ServerEvents.YOUR_APPLE_STATUS,
            payload = mapOf(
                "appleId" to appleId,
                "isPoisoned" to isPoisoned
            )
        )
    }

    fun blackAppleUpdateMessage(poisonApples: List<Map<String, String>>): WsMessage {
        return WsMessage(
            type = ServerEvents.BLACK_APPLE_UPDATE,
            payload = mapOf(
                "poisonApples" to poisonApples
            )
        )
    }

    fun notifyDrawCardMessage(playerId: String, deckRemainingCount: Int): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_DRAW_CARD,
            payload = mapOf(
                "playerId" to playerId,
                "deckRemainingCount" to deckRemainingCount
            )
        )
    }

    fun notifyUseCardMessage(playerId: String, cardType: String, params: Map<String, Any?>): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_USE_CARD,
            payload = mapOf(
                "playerId" to playerId,
                "cardType" to cardType,
                "params" to params
            )
        )
    }

    fun notifyDiscardCardMessage(playerId: String, cardType: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_DISCARD_CARD,
            payload = mapOf(
                "playerId" to playerId,
                "cardType" to cardType
            )
        )
    }

    fun notifyExchangeAppleMessage(playerIdA: String, playerIdB: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_EXCHANGE_APPLE,
            payload = mapOf(
                "playerIdA" to playerIdA,
                "playerIdB" to playerIdB
            )
        )
    }

    fun notifyApplePubliclyRevealedMessage(
        appleId: String,
        holderPlayerId: String,
        isPoisoned: Boolean
    ): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_APPLE_PUBLICLY_REVEALED,
            payload = mapOf(
                "appleId" to appleId,
                "holderPlayerId" to holderPlayerId,
                "isPoisoned" to isPoisoned
            )
        )
    }

    fun notifyPlayerDiedMessage(playerId: String, cause: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_PLAYER_DIED,
            payload = mapOf(
                "playerId" to playerId,
                "cause" to cause
            )
        )
    }

    fun notifyPreferenceAnsweredMessage(playerId: String, questionType: String, answer: Boolean): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_PREFERENCE_ANSWERED,
            payload = mapOf(
                "playerId" to playerId,
                "questionType" to questionType,
                "answer" to answer
            )
        )
    }

    fun requestPreferenceMessage(questionType: String, askedByPlayerId: String): WsMessage {
        return WsMessage(
            type = ServerEvents.REQUEST_PREFERENCE,
            payload = mapOf(
                "questionType" to questionType,
                "askedByPlayerId" to askedByPlayerId
            )
        )
    }

    fun requestQueenExchangeMessage(availableTargetPlayerIds: List<String>): WsMessage {
        return WsMessage(
            type = ServerEvents.REQUEST_QUEEN_EXCHANGE,
            payload = mapOf(
                "availableTargetPlayerIds" to availableTargetPlayerIds
            )
        )
    }

    fun notifyRouletteMessage(
        cardType: String,
        direction: String,
        steps: Int,
        excludedPlayerIds: List<String> = emptyList()
    ): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_ROULETTE,
            payload = mapOf(
                "cardType" to cardType,
                "direction" to direction,
                "steps" to steps,
                "excludedPlayerIds" to excludedPlayerIds
            )
        )
    }

    fun phaseChangedMessage(newPhase: String, triggerPlayerId: String?): WsMessage {
        return WsMessage(
            type = ServerEvents.PHASE_CHANGED,
            payload = mapOf(
                "newPhase" to newPhase,
                "triggerPlayerId" to triggerPlayerId
            )
        )
    }

    fun turnChangedMessage(currentTurnPlayerId: String, timeoutSeconds: Int): WsMessage {
        return WsMessage(
            type = ServerEvents.TURN_CHANGED,
            payload = mapOf(
                "currentTurnPlayerId" to currentTurnPlayerId,
                "timeoutSeconds" to timeoutSeconds
            )
        )
    }

    fun gameResultMessage(
        winFaction: String,
        reason: String,
        players: List<Map<String, Any?>>
    ): WsMessage {
        return WsMessage(
            type = ServerEvents.GAME_RESULT,
            payload = mapOf(
                "winFaction" to winFaction,
                "reason" to reason,
                "players" to players
            )
        )
    }

    fun notifyRematchStartingMessage(): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_REMATCH_STARTING,
            payload = emptyMap()
        )
    }

    fun notifyExchangeHandMessage(playerIdA: String, playerIdB: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_EXCHANGE_HAND,
            payload = mapOf(
                "playerIdA" to playerIdA,
                "playerIdB" to playerIdB
            )
        )
    }

    fun notifyGrayAbilityActivatedMessage(playerId: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_GRAY_ABILITY_ACTIVATED,
            payload = mapOf(
                "playerId" to playerId
            )
        )
    }

    fun notifyLightAbilityActivatedMessage(playerId: String, playerIdA: String, playerIdB: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_LIGHT_ABILITY_ACTIVATED,
            payload = mapOf(
                "playerId" to playerId,
                "role" to "LIGHT",
                "swappedPlayerIdA" to playerIdA,
                "swappedPlayerIdB" to playerIdB
            )
        )
    }

    fun notifyGuardActivatedMessage(playerId: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_GUARD_ACTIVATED,
            payload = mapOf(
                "playerId" to playerId
            )
        )
    }

    fun notifyKnightBlockedMessage(targetPlayerId: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_KNIGHT_BLOCKED,
            payload = mapOf("targetPlayerId" to targetPlayerId)
        )
    }

    fun notifyPlayerSkippedMessage(playerId: String): WsMessage {
        return WsMessage(
            type = ServerEvents.NOTIFY_PLAYER_SKIPPED,
            payload = mapOf(
                "playerId" to playerId
            )
        )
    }

    fun errorMessage(code: String, message: String): WsMessage {
        return WsMessage(
            type = ServerEvents.ERROR,
            payload = mapOf(
                "code" to code,
                "message" to message
            )
        )
    }
}







