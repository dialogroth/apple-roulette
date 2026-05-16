package com.example

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = System.getenv("HOST") ?: "0.0.0.0"

    embeddedServer(Netty, port = port, host = host) {
        module()
    }.start(wait = true)
}

// ============================================================================
// REST API用のリクエスト/レスポンス型
// ============================================================================

@Serializable
data class CreateRoomRequest(
    val hostUserName: String,
    val playerCount: Int,
    val roles: List<String>,
    val poisonAppleCount: Int,
    val cardSettings: Map<String, Int>
)

@Serializable
data class CreateRoomResponse(
    val roomId: String,
    val roomCode: String,
    val hostPlayerId: String
)

@Serializable
data class JoinRoomRequest(
    val roomCode: String,
    val userName: String
)

@Serializable
data class JoinRoomResponse(
    val roomId: String,
    val playerId: String,
    val seatOrder: Int
)

fun Application.module() {

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
    }

    val wsManager = WebSocketManager()
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ゲーム状態・ルーム情報を保持するメモリストレージ（本来はDB）
    val rooms = mutableMapOf<UUID, Room>()
    val roomSettings = mutableMapOf<UUID, RoomSettings>()
    val players = mutableMapOf<UUID, PlayerRecord>()
    val roomCodeMap = mutableMapOf<String, UUID>()
    val games = mutableMapOf<UUID, GameState>()  // ゲーム状態を保持
    val gamesByRoom = mutableMapOf<UUID, UUID>()  // roomId -> gameId の対応

    routing {

        staticResources("/", "")

        // ============================================================================
        // REST API: ルーム作成
        // ============================================================================
        post("/api/rooms") {
            try {
                val request = call.receive<CreateRoomRequest>()

                val roomId = UUID.randomUUID()
                val hostPlayerId = UUID.randomUUID()
                val roomCode = generateRoomCode()

                val room = Room(
                    id = roomId,
                    roomCode = roomCode,
                    hostPlayerId = hostPlayerId,
                    status = RoomStatus.WAITING
                )

                val roles = request.roles.mapNotNull { roleName ->
                    runCatching { Role.valueOf(roleName) }.getOrNull()
                }

                val settings = RoomSettings(
                    id = UUID.randomUUID(),
                    roomId = roomId,
                    poisonAppleCount = request.poisonAppleCount,
                    roles = roles
                )

                val hostPlayer = PlayerRecord(
                    id = hostPlayerId,
                    roomId = roomId,
                    userName = request.hostUserName,
                    seatOrder = 0,
                    isHost = true,
                    isConnected = true
                )

                rooms[roomId] = room
                roomSettings[roomId] = settings
                players[hostPlayerId] = hostPlayer
                roomCodeMap[roomCode] = roomId

                call.respond(HttpStatusCode.Created, CreateRoomResponse(
                    roomId = roomId.toString(),
                    roomCode = roomCode,
                    hostPlayerId = hostPlayerId.toString()
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Unknown error")))
            }
        }

        // ============================================================================
        // REST API: ルーム参加
        // ============================================================================
        post("/api/rooms/join") {
            try {
                val request = call.receive<JoinRoomRequest>()

                val roomId = roomCodeMap[request.roomCode]
                    ?: throw Exception("ルームが見つかりません")

                val room = rooms[roomId] ?: throw Exception("ルームが見つかりません")

                // 重複チェック
                if (players.values.any { it.roomId == roomId && it.userName == request.userName }) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "同じユーザー名は使用できません"))
                    return@post
                }

                val settings = roomSettings[roomId] ?: throw Exception("ルーム設定が見つかりません")
                val currentPlayerCount = players.values.count { it.roomId == roomId }

                if (currentPlayerCount >= settings.roles.size) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "ルームが満員です"))
                    return@post
                }

                val playerId = UUID.randomUUID()
                val newPlayer = PlayerRecord(
                    id = playerId,
                    roomId = roomId,
                    userName = request.userName,
                    seatOrder = currentPlayerCount,
                    isHost = false,
                    isConnected = true
                )

                players[playerId] = newPlayer

                call.respond(HttpStatusCode.OK, JoinRoomResponse(
                    roomId = roomId.toString(),
                    playerId = playerId.toString(),
                    seatOrder = currentPlayerCount
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Unknown error")))
            }
        }

        // ============================================================================
        // WebSocket: ゲーム通信
        // ============================================================================
        webSocket("/ws/game") {
            try {
                val roomIdParam = call.parameters["roomId"]?.let { UUID.fromString(it) }
                val playerIdParam = call.parameters["playerId"]?.let { UUID.fromString(it) }

                if (roomIdParam == null || playerIdParam == null) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid parameters"))
                    return@webSocket
                }

                val room = rooms[roomIdParam]
                val player = players[playerIdParam]

                if (room == null || player == null || player.roomId != roomIdParam) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Unauthorized"))
                    return@webSocket
                }

                wsManager.addPlayerSession(roomIdParam, playerIdParam, this)
                println("プレイヤー接続: ${player.userName} (${playerIdParam})")

                // 新規プレイヤー参加を全員に通知
                wsManager.broadcastToRoom(
                    roomIdParam,
                    WsHelpers.playerJoinedMessage(playerIdParam.toString(), player.userName, player.seatOrder)
                )

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        try {
                            val messageText = frame.readText()
                            val jsonObject = json.parseToJsonElement(messageText) as? JsonObject ?: continue
                            val type = jsonObject["type"]?.jsonPrimitiveContentOrNull() ?: continue
                            val payload = (jsonObject["payload"] as? JsonObject)
                                ?.let { jsonObjectToMap(it) }
                                ?: emptyMap()

                            val message = WsMessage(type, payload)
                            handleGameEvent(
                                message,
                                roomIdParam,
                                playerIdParam,
                                wsManager,
                                rooms,
                                roomSettings,
                                players,
                                games,
                                gamesByRoom
                            )
                        } catch (e: Exception) {
                            println("メッセージ処理エラー: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                println("WebSocketエラー: ${e.message}")
            } finally {
                // 接続情報を取得
                val roomIdParam = try {
                    call.parameters["roomId"]?.let { UUID.fromString(it) }
                } catch (e: Exception) {
                    null
                }

                val playerIdParam = try {
                    call.parameters["playerId"]?.let { UUID.fromString(it) }
                } catch (e: Exception) {
                    null
                }

                if (roomIdParam != null && playerIdParam != null) {
                    wsManager.removePlayerSession(roomIdParam, playerIdParam)

                    val player = players[playerIdParam]
                    if (player != null) {
                        wsManager.broadcastToRoom(
                            roomIdParam,
                            WsHelpers.playerDisconnectedMessage(playerIdParam.toString(), player.userName)
                        )
                        println("プレイヤー切断: ${player.userName}")
                    }
                }
            }
        }
    }
}

private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> =
    jsonObject.mapValues { (_, value) -> jsonElementToAny(value) }

private fun jsonElementToAny(value: JsonElement): Any? = when (value) {
    JsonNull -> null
    is JsonObject -> jsonObjectToMap(value)
    is JsonArray -> value.map { jsonElementToAny(it) }
    is JsonPrimitive -> when {
        value.isString -> value.content
        value.content == "true" -> true
        value.content == "false" -> false
        value.content.toIntOrNull() != null -> value.content.toInt()
        value.content.toLongOrNull() != null -> value.content.toLong()
        value.content.toDoubleOrNull() != null -> value.content.toDouble()
        else -> value.content
    }
    else -> null
}

private fun JsonElement.jsonPrimitiveContentOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

// ============================================================================
// WebSocketイベントハンドラー
// ============================================================================

suspend fun handleGameEvent(
    message: WsMessage,
    roomId: UUID,
    playerId: UUID,
    wsManager: WebSocketManager,
    rooms: MutableMap<UUID, Room>,
    roomSettings: MutableMap<UUID, RoomSettings>,
    players: MutableMap<UUID, PlayerRecord>,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID> = mutableMapOf()  // roomId -> gameId
) {
    when (message.type) {
        ClientEvents.GAME_START -> {
            handleGameStart(
                roomId, playerId, wsManager, rooms, roomSettings, players, games, gamesByRoom
            )
        }
        ClientEvents.REMATCH_REQUEST -> {
            println("再ゲーム要求: $playerId")
            handleRematchRequest(roomId, playerId, wsManager, rooms, roomSettings, players, games, gamesByRoom)
        }
        ClientEvents.ACTION_DRAW_CARD -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            handleActionDrawCard(gameState, playerId, roomId, wsManager, games, gamesByRoom, rooms)
        }
        ClientEvents.ACTION_USE_CARD -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val cardId = (message.payload["cardId"] as? String)?.let { UUID.fromString(it) } ?: return
            @Suppress("UNCHECKED_CAST")
            val params = (message.payload["params"] as? Map<String, Any?>) ?: emptyMap()
            handleActionUseCard(gameState, playerId, cardId, params, roomId, wsManager, games, gamesByRoom, rooms)
        }
        ClientEvents.ACTION_DISCARD_CARD -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val cardId = (message.payload["cardId"] as? String)?.let { UUID.fromString(it) } ?: return
            handleActionDiscardCard(gameState, playerId, cardId, roomId, wsManager, games, gamesByRoom, rooms)
        }
        ClientEvents.ACTION_EXCHANGE_HAND -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val targetPlayerId = (message.payload["targetPlayerId"] as? String)?.let { UUID.fromString(it) } ?: return
            handleActionExchangeHand(gameState, playerId, targetPlayerId, roomId, wsManager, games, gamesByRoom, rooms)
        }
        ClientEvents.ACTION_EXCHANGE_APPLE -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val targetPlayerId = (message.payload["targetPlayerId"] as? String)?.let { UUID.fromString(it) } ?: return
            handleActionExchangeApple(gameState, playerId, targetPlayerId, roomId, wsManager, games, gamesByRoom, rooms)
        }
        ClientEvents.ACTION_CHECK_OWN_APPLE -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            handleActionCheckOwnApple(gameState, playerId, roomId, wsManager, games, gamesByRoom, rooms)
        }
        ClientEvents.ACTION_USE_ABILITY -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            @Suppress("UNCHECKED_CAST")
            val params = (message.payload["params"] as? Map<String, Any?>) ?: emptyMap()
            handleActionUseAbility(gameState, playerId, params, roomId, wsManager, games, gamesByRoom, rooms)
        }
        ClientEvents.RESPONSE_PREFERENCE -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val answer = message.payload["answer"] as? Boolean ?: return
            val questionType = message.payload["questionType"] as? String ?: "APPLE"
            handleResponsePreference(gameState, playerId, answer, questionType, roomId, wsManager, games, gamesByRoom, rooms)
        }
        ClientEvents.RESPONSE_QUEEN_EXCHANGE -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val targetPlayerId = (message.payload["targetPlayerId"] as? String)?.let { UUID.fromString(it) } ?: return
            handleResponseQueenExchange(gameState, playerId, targetPlayerId, roomId, wsManager, games, gamesByRoom, rooms)
        }
        else -> {
            println("未処理のイベント: ${message.type}")
        }
    }
}

suspend fun handleGameStart(
    roomId: UUID,
    playerId: UUID,
    wsManager: WebSocketManager,
    rooms: MutableMap<UUID, Room>,
    roomSettings: MutableMap<UUID, RoomSettings>,
    players: MutableMap<UUID, PlayerRecord>,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>
) {
    val room = rooms[roomId] ?: return
    val settings = roomSettings[roomId] ?: return

    // ホストのみがゲーム開始可能
    if (room.hostPlayerId != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(
            ErrorCodes.NOT_HOST, "ホストのみがゲーム開始できます"
        ))
        return
    }

    // ゲーム開始処理
    val gameState = GameInitializer.startGame(
        roomId, playerId, wsManager, players, settings
    )

    if (gameState != null) {
        games[gameState.gameId] = gameState
        gamesByRoom[roomId] = gameState.gameId
        println("ゲーム開始: ${gameState.gameId}")
    }
}

// ================================================================================
// アクションハンドラー関数群
// ================================================================================

suspend fun handleActionDrawCard(
    gameState: GameState,
    playerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    if (gameState.turnOrder[gameState.currentTurnIndex] != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.NOT_YOUR_TURN, "自分の手番ではありません"))
        return
    }

    val (updatedState, error) = GameActionHandler.drawCard(gameState, playerId)
    if (error != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.INVALID_PHASE, error))
        return
    }

    games[gameState.gameId] = updatedState

    // ブロードキャスト
    wsManager.broadcastToRoom(roomId, WsHelpers.notifyDrawCardMessage(playerId.toString(), updatedState.deckOrder.size))
    GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)
}

suspend fun handleActionUseCard(
    gameState: GameState,
    playerId: UUID,
    cardId: UUID,
    params: Map<String, Any?>,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    if (gameState.turnOrder[gameState.currentTurnIndex] != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.NOT_YOUR_TURN, "自分の手番ではありません"))
        return
    }

    val card = gameState.cards[cardId] ?: return

    val (updatedState, error) = GameActionHandler.useCard(gameState, playerId, cardId, params)

    // 騎士無効化チェック
    if (error == "KNIGHT_BLOCKED") {
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyUseCardMessage(playerId.toString(), card.cardType.toString(), params))
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyKnightBlockedMessage(params["targetPlayerId"].toString()))

        val nextState = GameActionHandler.advanceTurn(gameState)
        games[gameState.gameId] = nextState
        GameInitializer.sendGameStateSync(roomId, nextState, wsManager)

        val finalState = checkAndHandlePhaseTransition(nextState, roomId, wsManager, games, gamesByRoom, rooms)
        games[gameState.gameId] = finalState
        return
    }

    if (error != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.INVALID_PHASE, error))
        return
    }

    // フェイズ移行チェック
    if (updatedState.phase == GamePhase.LAST_TURN && gameState.phase == GamePhase.STORY) {
        wsManager.broadcastToRoom(roomId, WsHelpers.phaseChangedMessage("LAST_TURN", playerId.toString()))
    }

    wsManager.broadcastToRoom(roomId, WsHelpers.notifyUseCardMessage(playerId.toString(), card.cardType.toString(), params))

    // 好み質問カードの場合
    if (card.cardType in listOf(CardType.APPLE_QUESTION, CardType.MUSHROOM_QUESTION)) {
        val targetPlayerId = (params["targetPlayerId"] as? String)?.let { UUID.fromString(it) }
        if (targetPlayerId != null) {
            val questionType = if (card.cardType == CardType.APPLE_QUESTION) "APPLE" else "MUSHROOM"
            wsManager.sendToPlayer(
                targetPlayerId,
                WsHelpers.requestPreferenceMessage(questionType, playerId.toString())
            )
            games[gameState.gameId] = updatedState
            GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)
            return
        }
    }

    val beforeIndex = updatedState.currentTurnIndex
    val nextState = GameActionHandler.advanceTurn(updatedState)

    // スキップ通知
    val skippedPlayerIds = mutableListOf<UUID>()
    var checkIndex = (beforeIndex + 1) % updatedState.turnOrder.size
    while (checkIndex != nextState.currentTurnIndex) {
        val skippedPlayer = updatedState.players[updatedState.turnOrder[checkIndex]]
        if (skippedPlayer != null && skippedPlayer.isAlive) {
            skippedPlayerIds.add(updatedState.turnOrder[checkIndex])
        }
        checkIndex = (checkIndex + 1) % updatedState.turnOrder.size
    }
    skippedPlayerIds.forEach { skippedId ->
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerSkippedMessage(skippedId.toString()))
    }

    if (card.cardType in listOf(CardType.ROULETTE_1, CardType.ROULETTE_2, CardType.ROULETTE_3)) {
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyRouletteMessage(
            cardType = card.cardType.toString(),
            direction = params["direction"] as? String ?: "",
            steps = when (card.cardType) { CardType.ROULETTE_1 -> 1; CardType.ROULETTE_2 -> 2; else -> 3 },
            excludedPlayerIds = nextState.players.values.filter { !it.isAlive }.map { it.playerId.toString() }
        ))
        GameInitializer.sendBlackAppleUpdate(nextState, roomId, wsManager)
    }

    games[gameState.gameId] = nextState
    GameInitializer.sendGameStateSync(roomId, nextState, wsManager)

    val finalState = checkAndHandlePhaseTransition(nextState, roomId, wsManager, games, gamesByRoom, rooms)
    games[gameState.gameId] = finalState
}

suspend fun handleActionDiscardCard(
    gameState: GameState,
    playerId: UUID,
    cardId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    if (gameState.turnOrder[gameState.currentTurnIndex] != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.NOT_YOUR_TURN, "自分の手番ではありません"))
        return
    }

    val card = gameState.cards[cardId] ?: return

    val (updatedState, error) = GameActionHandler.discardCard(gameState, playerId, cardId)
    if (error != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.INVALID_PHASE, error))
        return
    }

    val beforeIndex = updatedState.currentTurnIndex
    val nextState = GameActionHandler.advanceTurn(updatedState)

    // スキップ通知
    val skippedPlayerIds = mutableListOf<UUID>()
    var checkIndex = (beforeIndex + 1) % updatedState.turnOrder.size
    while (checkIndex != nextState.currentTurnIndex) {
        val skippedPlayer = updatedState.players[updatedState.turnOrder[checkIndex]]
        if (skippedPlayer != null && skippedPlayer.isAlive) {
            skippedPlayerIds.add(updatedState.turnOrder[checkIndex])
        }
        checkIndex = (checkIndex + 1) % updatedState.turnOrder.size
    }
    skippedPlayerIds.forEach { skippedId ->
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerSkippedMessage(skippedId.toString()))
    }

    games[gameState.gameId] = nextState
    wsManager.broadcastToRoom(roomId, WsHelpers.notifyDiscardCardMessage(playerId.toString(), card.cardType.toString()))
    GameInitializer.sendGameStateSync(roomId, nextState, wsManager)

    val finalState = checkAndHandlePhaseTransition(nextState, roomId, wsManager, games, gamesByRoom, rooms)
    games[gameState.gameId] = finalState
}

suspend fun handleActionExchangeHand(
    gameState: GameState,
    playerId: UUID,
    targetPlayerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    if (gameState.turnOrder[gameState.currentTurnIndex] != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.NOT_YOUR_TURN, "自分の手番ではありません"))
        return
    }

    val (updatedState, error) = GameActionHandler.exchangeHand(gameState, playerId, targetPlayerId)
    if (error != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.INVALID_PHASE, error))
        return
    }

    val beforeIndex = updatedState.currentTurnIndex
    val nextState = GameActionHandler.advanceTurn(updatedState)

    // スキップ通知
    val skippedPlayerIds = mutableListOf<UUID>()
    var checkIndex = (beforeIndex + 1) % updatedState.turnOrder.size
    while (checkIndex != nextState.currentTurnIndex) {
        val skippedPlayer = updatedState.players[updatedState.turnOrder[checkIndex]]
        if (skippedPlayer != null && skippedPlayer.isAlive) {
            skippedPlayerIds.add(updatedState.turnOrder[checkIndex])
        }
        checkIndex = (checkIndex + 1) % updatedState.turnOrder.size
    }
    skippedPlayerIds.forEach { skippedId ->
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerSkippedMessage(skippedId.toString()))
    }

    games[gameState.gameId] = nextState
    wsManager.broadcastToRoom(roomId, WsHelpers.notifyExchangeHandMessage(playerId.toString(), targetPlayerId.toString()))
    GameInitializer.sendGameStateSync(roomId, nextState, wsManager)

    val finalState = checkAndHandlePhaseTransition(nextState, roomId, wsManager, games, gamesByRoom, rooms)
    games[gameState.gameId] = finalState
}

suspend fun handleActionExchangeApple(
    gameState: GameState,
    playerId: UUID,
    targetPlayerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    if (gameState.turnOrder[gameState.currentTurnIndex] != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.NOT_YOUR_TURN, "自分の手番ではありません"))
        return
    }

    val (updatedState, error) = GameActionHandler.exchangeApple(gameState, playerId, targetPlayerId)
    if (error != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.INVALID_PHASE, error))
        return
    }

    val beforeIndex = updatedState.currentTurnIndex
    val nextState = GameActionHandler.advanceTurn(updatedState)

    // スキップ通知
    val skippedPlayerIds = mutableListOf<UUID>()
    var checkIndex = (beforeIndex + 1) % updatedState.turnOrder.size
    while (checkIndex != nextState.currentTurnIndex) {
        val skippedPlayer = updatedState.players[updatedState.turnOrder[checkIndex]]
        if (skippedPlayer != null && skippedPlayer.isAlive) {
            skippedPlayerIds.add(updatedState.turnOrder[checkIndex])
        }
        checkIndex = (checkIndex + 1) % updatedState.turnOrder.size
    }
    skippedPlayerIds.forEach { skippedId ->
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerSkippedMessage(skippedId.toString()))
    }

    games[gameState.gameId] = nextState
    wsManager.broadcastToRoom(roomId, WsHelpers.notifyExchangeAppleMessage(playerId.toString(), targetPlayerId.toString()))
    GameInitializer.sendBlackAppleUpdate(nextState, roomId, wsManager)
    GameInitializer.sendGameStateSync(roomId, nextState, wsManager)

    val finalState = checkAndHandlePhaseTransition(nextState, roomId, wsManager, games, gamesByRoom, rooms)
    games[gameState.gameId] = finalState
}

suspend fun handleActionCheckOwnApple(
    gameState: GameState,
    playerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    val (updatedState, error) = GameActionHandler.checkOwnApple(gameState, playerId)
    if (error != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.INVALID_PHASE, error))
        return
    }

    val myApple = updatedState.apples.find { it.currentHolderPlayerId == playerId }
    if (myApple != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.yourAppleStatusMessage(myApple.appleId.toString(), myApple.isPoisoned))
    }

    val beforeIndex = updatedState.currentTurnIndex
    val nextState = GameActionHandler.advanceTurn(updatedState)

    // スキップ通知
    val skippedPlayerIds = mutableListOf<UUID>()
    var checkIndex = (beforeIndex + 1) % updatedState.turnOrder.size
    while (checkIndex != nextState.currentTurnIndex) {
        val skippedPlayer = updatedState.players[updatedState.turnOrder[checkIndex]]
        if (skippedPlayer != null && skippedPlayer.isAlive) {
            skippedPlayerIds.add(updatedState.turnOrder[checkIndex])
        }
        checkIndex = (checkIndex + 1) % updatedState.turnOrder.size
    }
    skippedPlayerIds.forEach { skippedId ->
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerSkippedMessage(skippedId.toString()))
    }

    games[gameState.gameId] = nextState
    GameInitializer.sendGameStateSync(roomId, nextState, wsManager)

    val finalState = checkAndHandlePhaseTransition(nextState, roomId, wsManager, games, gamesByRoom, rooms)
    games[gameState.gameId] = finalState
}

suspend fun handleActionUseAbility(
    gameState: GameState,
    playerId: UUID,
    params: Map<String, Any?>,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    if (gameState.turnOrder[gameState.currentTurnIndex] != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.NOT_YOUR_TURN, "自分の手番ではありません"))
        return
    }

    val (updatedState, error) = GameActionHandler.useAbility(gameState, playerId, params)
    if (error != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.ABILITY_ALREADY_USED, error))
        return
    }

    val player = updatedState.players[playerId]
    if (player != null) {
        when (player.role) {
            Role.GRAY -> wsManager.broadcastToRoom(roomId, WsHelpers.notifyGrayAbilityActivatedMessage(playerId.toString()))
            Role.LIGHT -> {
                val idA = params["targetPlayerIdA"] as? String ?: ""
                val idB = params["targetPlayerIdB"] as? String ?: ""
                wsManager.broadcastToRoom(roomId, WsHelpers.notifyLightAbilityActivatedMessage(playerId.toString(), idA, idB))
            }
            else -> {}
        }
    }

    val beforeIndex = updatedState.currentTurnIndex
    val nextState = GameActionHandler.advanceTurn(updatedState)

    // スキップ通知
    val skippedPlayerIds = mutableListOf<UUID>()
    var checkIndex = (beforeIndex + 1) % updatedState.turnOrder.size
    while (checkIndex != nextState.currentTurnIndex) {
        val skippedPlayer = updatedState.players[updatedState.turnOrder[checkIndex]]
        if (skippedPlayer != null && skippedPlayer.isAlive) {
            skippedPlayerIds.add(updatedState.turnOrder[checkIndex])
        }
        checkIndex = (checkIndex + 1) % updatedState.turnOrder.size
    }
    skippedPlayerIds.forEach { skippedId ->
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerSkippedMessage(skippedId.toString()))
    }

    games[gameState.gameId] = nextState
    GameInitializer.sendGameStateSync(roomId, nextState, wsManager)

    val finalState = checkAndHandlePhaseTransition(nextState, roomId, wsManager, games, gamesByRoom, rooms)
    games[gameState.gameId] = finalState
}

suspend fun handleResponsePreference(
    gameState: GameState,
    responderId: UUID,
    answer: Boolean,
    questionType: String,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    val (updatedState, error) = GameActionHandler.recordPreferenceAnswer(gameState, responderId, questionType, answer)
    if (error != null) return

    val beforeIndex = updatedState.currentTurnIndex
    val nextState = GameActionHandler.advanceTurn(updatedState)

    // スキップ通知
    val skippedPlayerIds = mutableListOf<UUID>()
    var checkIndex = (beforeIndex + 1) % updatedState.turnOrder.size
    while (checkIndex != nextState.currentTurnIndex) {
        val skippedPlayer = updatedState.players[updatedState.turnOrder[checkIndex]]
        if (skippedPlayer != null && skippedPlayer.isAlive) {
            skippedPlayerIds.add(updatedState.turnOrder[checkIndex])
        }
        checkIndex = (checkIndex + 1) % updatedState.turnOrder.size
    }
    skippedPlayerIds.forEach { skippedId ->
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerSkippedMessage(skippedId.toString()))
    }

    games[gameState.gameId] = nextState
    wsManager.broadcastToRoom(roomId, WsHelpers.notifyPreferenceAnsweredMessage(responderId.toString(), questionType, answer))
    GameInitializer.sendGameStateSync(roomId, nextState, wsManager)

    val finalState = checkAndHandlePhaseTransition(nextState, roomId, wsManager, games, gamesByRoom, rooms)
    games[gameState.gameId] = finalState
}

suspend fun handleResponseQueenExchange(
    gameState: GameState,
    queenPlayerId: UUID,
    targetPlayerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    val (updatedState, error) = GameActionHandler.queenSelectExchange(gameState, queenPlayerId, targetPlayerId)
    if (error == "GUARD_ACTIVATED") {
        wsManager.broadcastToRoom(roomId, WsHelpers.notifyGuardActivatedMessage(targetPlayerId.toString()))
        GameInitializer.sendGameStateSync(roomId, gameState, wsManager)
        return
    }

    if (error != null) {
        wsManager.sendToPlayer(queenPlayerId, WsHelpers.errorMessage(ErrorCodes.INVALID_PHASE, error))
        return
    }

    games[gameState.gameId] = updatedState
    wsManager.broadcastToRoom(roomId, WsHelpers.notifyExchangeAppleMessage(queenPlayerId.toString(), targetPlayerId.toString()))
    GameInitializer.sendBlackAppleUpdate(updatedState, roomId, wsManager)
    GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)

    // 交換完了後にENDING_REVEALへ遷移
    wsManager.broadcastToRoom(roomId, WsHelpers.phaseChangedMessage("ENDING_REVEAL", null))
    handleEndingReveal(updatedState, roomId, wsManager, games, gamesByRoom, rooms)
}

suspend fun handleRematchRequest(
    roomId: UUID,
    playerId: UUID,
    wsManager: WebSocketManager,
    rooms: MutableMap<UUID, Room>,
    roomSettings: MutableMap<UUID, RoomSettings>,
    players: MutableMap<UUID, PlayerRecord>,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>
) {
    val room = rooms[roomId] ?: return

    // ホストのみが再ゲーム要求可能
    if (room.hostPlayerId != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(
            ErrorCodes.NOT_HOST, "ホストのみが再ゲームを要求できます"
        ))
        return
    }

    // 前ゲームのGameStateを破棄
    val prevGameId = gamesByRoom[roomId]
    if (prevGameId != null) {
        games.remove(prevGameId)
        gamesByRoom.remove(roomId)
        GameTimeoutManager.clearAllTimers(prevGameId)
    }

    // 切断したままのプレイヤーを削除
    val roomPlayers = players.values.filter { it.roomId == roomId }
    val disconnectedPlayers = roomPlayers.filter { DisconnectionManager.isPlayerDisconnected(it.id) }
    disconnectedPlayers.forEach { player ->
        players.remove(player.id)
    }

    // 参加人数をチェック
    val settings = roomSettings[roomId] ?: return
    val remainingPlayers = players.values.filter { it.roomId == roomId }
    if (remainingPlayers.size < settings.roles.size) {
        wsManager.broadcastToRoom(roomId, WsHelpers.errorMessage(
            ErrorCodes.INVALID_PHASE,
            "足りないプレイヤー数が多いため、新しいルームを作成してください"
        ))
        return
    }

    // seat_orderを詰め直す
    remainingPlayers.sortedBy { it.seatOrder }.forEachIndexed { index, player ->
        players[player.id] = player.copy(seatOrder = index)
    }

    // ルームをWAITING状態に戻す
    rooms[roomId] = room.copy(status = RoomStatus.WAITING)

    // 全員に再ゲーム開始を通知
    wsManager.broadcastToRoom(roomId, WsHelpers.notifyRematchStartingMessage())
}

// ============================================================================
// フェイズ遷移・エンディング処理
// ============================================================================

suspend fun checkAndHandlePhaseTransition(
    gameState: GameState,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
): GameState {
    var currentState = gameState

    // 最後の手番フェイズ：手番開始時の呪いの指輪チェック
    if (currentState.phase == GamePhase.LAST_TURN) {
        val currentPlayerId = currentState.turnOrder[currentState.currentTurnIndex]
        val (stateAfterCursed, cursedEvents) = GamePhaseHandler.handleLastTurnStart(currentState, currentPlayerId)

        if (cursedEvents.isNotEmpty()) {
            // 呪いの指輪で死亡
            currentState = stateAfterCursed
            games[currentState.gameId] = currentState
            wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerDiedMessage(currentPlayerId.toString(), "CURSED_RING"))
            GameInitializer.sendGameStateSync(roomId, currentState, wsManager)

            // 死亡後に次のターンへ
            val nextState = GameActionHandler.advanceTurn(currentState)
            currentState = nextState
            games[currentState.gameId] = currentState

            // 再帰的にフェイズチェック
            return checkAndHandlePhaseTransition(currentState, roomId, wsManager, games, gamesByRoom, rooms)
        }

        // 最後の手番終了チェック
        if (GamePhaseHandler.checkLastTurnEnd(currentState)) {
            // エンディングフェイズへ移行
            currentState = GamePhaseHandler.transitionToEnding(currentState)
            games[currentState.gameId] = currentState
            wsManager.broadcastToRoom(roomId, WsHelpers.phaseChangedMessage("ENDING_QUEEN", null))

            // 女王の特権処理
            val (stateAfterQueen, queenEvents) = GamePhaseHandler.processQueenPrivilege(currentState)
            currentState = stateAfterQueen
            games[currentState.gameId] = currentState

            // 女王リンゴ公開通知
            queenEvents.forEach { event ->
                val parts = event.split(":")
                if (parts[0] == "APPLE_REVEALED") {
                    wsManager.broadcastToRoom(roomId, WsHelpers.notifyApplePubliclyRevealedMessage(
                        appleId = parts[1],
                        holderPlayerId = parts[2],
                        isPoisoned = parts[3].toBoolean()
                    ))
                }
            }

            if (currentState.phase == GamePhase.ENDING_REVEAL) {
                // 女王が死亡 or 通常リンゴ → 即座にENDING_REVEALへ
                wsManager.broadcastToRoom(roomId, WsHelpers.phaseChangedMessage("ENDING_REVEAL", null))
                handleEndingReveal(currentState, roomId, wsManager, games, gamesByRoom, rooms)
            } else {
                // 毒リンゴ → 女王に交換対象を要求
                val alivePlayerIds = currentState.players.values
                    .filter { it.isAlive && it.role != Role.QUEEN }
                    .map { it.playerId.toString() }
                wsManager.sendToPlayer(
                    currentState.players.values.find { it.role == Role.QUEEN }!!.playerId,
                    WsHelpers.requestQueenExchangeMessage(alivePlayerIds)
                )
            }
        } else {
            // 通常のターン変更通知
            wsManager.broadcastToRoom(roomId, WsHelpers.turnChangedMessage(
                currentState.turnOrder[currentState.currentTurnIndex].toString(), 180
            ))
        }
    } else {
        // ストーリーフェイズ：通常のターン変更通知
        wsManager.broadcastToRoom(roomId, WsHelpers.turnChangedMessage(
            currentState.turnOrder[currentState.currentTurnIndex].toString(), 180
        ))
    }

    return currentState
}

suspend fun handleEndingReveal(
    gameState: GameState,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>,
    rooms: MutableMap<UUID, Room>
) {
    val (finalState, events, winFaction) = GamePhaseHandler.processEnding(gameState)
    games[finalState.gameId] = finalState

    // リンゴ公開・死亡通知
    events.forEach { event ->
        val parts = event.split(":")
        when (parts[0]) {
            "APPLE_REVEALED" -> wsManager.broadcastToRoom(roomId, WsHelpers.notifyApplePubliclyRevealedMessage(
                appleId = parts[1], holderPlayerId = parts[2], isPoisoned = parts[3].toBoolean()
            ))
            "PLAYER_DIED" -> wsManager.broadcastToRoom(roomId, WsHelpers.notifyPlayerDiedMessage(
                playerId = parts[1], cause = parts[2]
            ))
        }
    }

    GameInitializer.sendGameStateSync(roomId, finalState, wsManager)
    GameResultHandler.finishGame(finalState, roomId, wsManager, games, gamesByRoom, rooms)
}

