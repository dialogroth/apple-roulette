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
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
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
                            // JSONメッセージを解析
                            val jsonMap = json.decodeFromString<Map<String, Any?>>(messageText)
                            val type = jsonMap["type"] as? String ?: continue
                            @Suppress("UNCHECKED_CAST")
                            val payload = (jsonMap["payload"] as? Map<String, Any?>) ?: emptyMap()

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
            handleActionDrawCard(gameState, playerId, roomId, wsManager, games, gamesByRoom)
        }
        ClientEvents.ACTION_USE_CARD -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val cardId = (message.payload["cardId"] as? String)?.let { UUID.fromString(it) } ?: return
            @Suppress("UNCHECKED_CAST")
            val params = (message.payload["params"] as? Map<String, Any?>) ?: emptyMap()
            handleActionUseCard(gameState, playerId, cardId, params, roomId, wsManager, games, gamesByRoom)
        }
        ClientEvents.ACTION_DISCARD_CARD -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val cardId = (message.payload["cardId"] as? String)?.let { UUID.fromString(it) } ?: return
            handleActionDiscardCard(gameState, playerId, cardId, roomId, wsManager, games, gamesByRoom)
        }
        ClientEvents.ACTION_EXCHANGE_HAND -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val targetPlayerId = (message.payload["targetPlayerId"] as? String)?.let { UUID.fromString(it) } ?: return
            handleActionExchangeHand(gameState, playerId, targetPlayerId, roomId, wsManager, games, gamesByRoom)
        }
        ClientEvents.ACTION_EXCHANGE_APPLE -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val targetPlayerId = (message.payload["targetPlayerId"] as? String)?.let { UUID.fromString(it) } ?: return
            handleActionExchangeApple(gameState, playerId, targetPlayerId, roomId, wsManager, games, gamesByRoom)
        }
        ClientEvents.ACTION_CHECK_OWN_APPLE -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            handleActionCheckOwnApple(gameState, playerId, roomId, wsManager, games, gamesByRoom)
        }
        ClientEvents.ACTION_USE_ABILITY -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            @Suppress("UNCHECKED_CAST")
            val params = (message.payload["params"] as? Map<String, Any?>) ?: emptyMap()
            handleActionUseAbility(gameState, playerId, params, roomId, wsManager, games, gamesByRoom)
        }
        ClientEvents.RESPONSE_PREFERENCE -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val answer = message.payload["answer"] as? Boolean ?: return
            handleResponsePreference(gameState, playerId, answer, roomId, wsManager, games)
        }
        ClientEvents.RESPONSE_QUEEN_EXCHANGE -> {
            val gameId = gamesByRoom[roomId] ?: return
            val gameState = games[gameId] ?: return
            val targetPlayerId = (message.payload["targetPlayerId"] as? String)?.let { UUID.fromString(it) } ?: return
            handleResponseQueenExchange(gameState, playerId, targetPlayerId, roomId, wsManager, games, gamesByRoom)
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
    gamesByRoom: MutableMap<UUID, UUID>
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
    gamesByRoom: MutableMap<UUID, UUID>
) {
    if (gameState.turnOrder[gameState.currentTurnIndex] != playerId) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.NOT_YOUR_TURN, "自分の手番ではありません"))
        return
    }

    val card = gameState.cards[cardId] ?: return

    val (updatedState, error) = GameActionHandler.useCard(gameState, playerId, cardId, params)
    if (error != null) {
        wsManager.sendToPlayer(playerId, WsHelpers.errorMessage(ErrorCodes.INVALID_PHASE, error))
        return
    }

    games[gameState.gameId] = updatedState

    wsManager.broadcastToRoom(roomId, WsHelpers.notifyUseCardMessage(playerId.toString(), card.cardType.toString(), params))

    // アップルルーレット使用時はブラックへの更新を送信
    if (card.cardType in listOf(CardType.ROULETTE_1, CardType.ROULETTE_2, CardType.ROULETTE_3)) {
        GameInitializer.sendBlackAppleUpdate(updatedState, roomId, wsManager)
    }

    GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)
}

suspend fun handleActionDiscardCard(
    gameState: GameState,
    playerId: UUID,
    cardId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>
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

    games[gameState.gameId] = updatedState

    wsManager.broadcastToRoom(roomId, WsHelpers.notifyDiscardCardMessage(playerId.toString(), card.cardType.toString()))
    GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)
}

suspend fun handleActionExchangeHand(
    gameState: GameState,
    playerId: UUID,
    targetPlayerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>
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

    games[gameState.gameId] = updatedState

    wsManager.broadcastToRoom(roomId, WsHelpers.notifyExchangeHandMessage(playerId.toString(), targetPlayerId.toString()))
    GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)
}

suspend fun handleActionExchangeApple(
    gameState: GameState,
    playerId: UUID,
    targetPlayerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>
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

    games[gameState.gameId] = updatedState

    wsManager.broadcastToRoom(roomId, WsHelpers.notifyExchangeAppleMessage(playerId.toString(), targetPlayerId.toString()))
    GameInitializer.sendBlackAppleUpdate(updatedState, roomId, wsManager)
    GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)
}

suspend fun handleActionCheckOwnApple(
    gameState: GameState,
    playerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>
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

    games[gameState.gameId] = updatedState
}

suspend fun handleActionUseAbility(
    gameState: GameState,
    playerId: UUID,
    params: Map<String, Any?>,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>
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

    games[gameState.gameId] = updatedState
    GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)
}

suspend fun handleResponsePreference(
    gameState: GameState,
    responderId: UUID,
    answer: Boolean,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>
) {
    val (updatedState, error) = GameActionHandler.recordPreferenceAnswer(gameState, responderId, "APPLE", answer)
    if (error != null) return

    games[gameState.gameId] = updatedState

    wsManager.broadcastToRoom(roomId, WsHelpers.notifyPreferenceAnsweredMessage(responderId.toString(), "APPLE", answer))
    GameInitializer.sendGameStateSync(roomId, updatedState, wsManager)
}

suspend fun handleResponseQueenExchange(
    gameState: GameState,
    queenPlayerId: UUID,
    targetPlayerId: UUID,
    roomId: UUID,
    wsManager: WebSocketManager,
    games: MutableMap<UUID, GameState>,
    gamesByRoom: MutableMap<UUID, UUID>
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
    remainingPlayers.forEachIndexed { index, player ->
        player.copy(seatOrder = index)
    }

    // ルームをWAITING状態に戻す
    rooms[roomId] = room.copy(status = RoomStatus.WAITING)

    // 全員に再ゲーム開始を通知
    wsManager.broadcastToRoom(roomId, WsHelpers.notifyRematchStartingMessage())
}
