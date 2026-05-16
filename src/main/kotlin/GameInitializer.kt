package com.example

import java.util.*
import kotlin.random.Random

object GameInitializer {

    suspend fun startGame(
        roomId: UUID,
        hostPlayerId: UUID,
        wsManager: WebSocketManager,
        players: Map<UUID, PlayerRecord>,
        roomSettings: RoomSettings
    ): GameState? {
        // ルームのプレイヤー一覧を取得（座席順でソート）
        val roomPlayers = players.values
            .filter { it.roomId == roomId }
            .sortedBy { it.seatOrder }

        if (roomPlayers.size != roomSettings.roles.size) {
            // 役職数と参加人数が一致していない
            wsManager.broadcastToRoom(
                roomId,
                WsHelpers.errorMessage(
                    ErrorCodes.INVALID_PHASE,
                    "参加人数と役職数が一致していません"
                )
            )
            return null
        }

        val gameId = UUID.randomUUID()
        val playerIds = roomPlayers.map { it.id }
        val cardSettings = getDefaultCardSettings()

        // ゲーム状態を作成
        val gameState = GameEngine.createGame(
            gameId = gameId,
            roomId = roomId,
            playerIds = playerIds,
            roles = roomSettings.roles,
            cardSettings = cardSettings,
            poisonAppleCount = roomSettings.poisonAppleCount
        )

        // プレイヤー情報の整備（ユーザー名を反映）
        val playerMapWithNames = gameState.players.mapValues { (playerId, gamePlayer) ->
            val roomPlayer = roomPlayers.find { it.id == playerId }
            gamePlayer.copy(userName = roomPlayer?.userName ?: "Unknown")
        }

        val updatedGameState = gameState.copy(players = playerMapWithNames)

        // ゲーム開始通知
        val playerSummaries = updatedGameState.turnOrder.map { playerId ->
            val player = updatedGameState.players[playerId]!!
            PlayerSummary(
                playerId = playerId.toString(),
                userName = player.userName,
                seatOrder = player.seatOrder,
                isAlive = player.isAlive,
                isConnected = player.isConnected,
                isRoleRevealed = player.isRoleRevealed,
                role = null, // 役職は非公開（本人へのメッセージで個別送信）
                isProtected = player.isProtected,
                skipNextTurn = player.skipNextTurn,
                applePreferenceAnswer = player.applePreferenceAnswer,
                mushroomPreferenceAnswer = player.mushroomPreferenceAnswer
            )
        }

        val firstTurnPlayerId = updatedGameState.turnOrder[updatedGameState.currentTurnIndex]

        wsManager.broadcastToRoom(
            roomId,
            WsHelpers.gameStartedMessage(
                gameId.toString(),
                playerSummaries,
                firstTurnPlayerId.toString()
            )
        )

        // 各プレイヤーへ個別に初期情報を送信
        updatedGameState.players.forEach { (playerId, gamePlayer) ->
            val myApple = updatedGameState.apples.find { it.currentHolderPlayerId == playerId }!!
            val myHand = GameEngine.getPlayerHand(updatedGameState, playerId)

            val payload = mutableMapOf<String, Any?>(
                "role" to gamePlayer.role.toString(),
                "faction" to gamePlayer.faction.toString(),
                "myApple" to mapOf(
                    "appleId" to myApple.appleId.toString(),
                    "isPoisoned" to myApple.isPoisoned
                ),
                "myHand" to myHand.map { mapOf(
                    "cardId" to it.cardId.toString(),
                    "cardType" to it.cardType.toString()
                ) }
            )

            // グリーンには白雪姫情報を含める
            if (gamePlayer.role == Role.GREEN) {
                val snowWhitePlayer = updatedGameState.players.values.find { it.role == Role.SNOW_WHITE }
                if (snowWhitePlayer != null) {
                    payload["snowWhitePlayerId"] = snowWhitePlayer.playerId.toString()
                }
            }

            // ブラックには毒リンゴ情報を含める
            if (gamePlayer.role == Role.BLACK) {
                val poisonAppleHolders = updatedGameState.apples
                    .filter { it.isPoisoned }
                    .map { it.currentHolderPlayerId.toString() }
                payload["poisonAppleHolderIds"] = poisonAppleHolders
            }

            val message = WsMessage(
                type = ServerEvents.YOUR_INITIAL_INFO,
                payload = payload
            )
            wsManager.sendToPlayer(playerId, message)
        }

        // ゲーム状態同期
        sendGameStateSync(roomId, updatedGameState, wsManager)

        // 最初の手番を通知
        wsManager.broadcastToRoom(
            roomId,
            WsHelpers.turnChangedMessage(firstTurnPlayerId.toString(), 180)
        )

        return updatedGameState
    }

    suspend fun sendGameStateSync(
        roomId: UUID,
        gameState: GameState,
        wsManager: WebSocketManager
    ) {
        // すべてのプレイヤーに共通のゲーム状態を送信
        val playerSummaries = gameState.players.values.map { player ->
            PlayerSummary(
                playerId = player.playerId.toString(),
                userName = player.userName,
                seatOrder = player.seatOrder,
                isAlive = player.isAlive,
                isConnected = player.isConnected,
                isRoleRevealed = player.isRoleRevealed,
                role = if (player.isRoleRevealed) player.role.toString() else null,
                isProtected = player.isProtected,
                skipNextTurn = player.skipNextTurn,
                applePreferenceAnswer = player.applePreferenceAnswer,
                mushroomPreferenceAnswer = player.mushroomPreferenceAnswer
            )
        }

        val discardPile = gameState.discardOrder.mapNotNull { cardId ->
            val card = gameState.cards[cardId]
            if (card != null) {
                mapOf(
                    "cardId" to card.cardId.toString(),
                    "cardType" to card.cardType.toString()
                )
            } else null
        }

        val currentTurnPlayerId = gameState.turnOrder.getOrNull(gameState.currentTurnIndex)?.toString() ?: ""

        // 各プレイヤーに対して権限に応じた情報をフィルタリング
        gameState.players.forEach { (playerId, _) ->
            val appleSummaries = gameState.apples.map { apple ->
                AppleSummary(
                    appleId = apple.appleId.toString(),
                    currentHolderPlayerId = apple.currentHolderPlayerId.toString(),
                    isPoisoned = when {
                        apple.isPubliclyRevealed -> apple.isPoisoned
                        apple.privatelyKnownBy.contains(playerId) -> apple.isPoisoned
                        else -> null
                    },
                    isPubliclyRevealed = apple.isPubliclyRevealed
                )
            }

            val myHand = GameEngine.getPlayerHand(gameState, playerId)
                .map { mapOf(
                    "cardId" to it.cardId.toString(),
                    "cardType" to it.cardType.toString()
                ) }

            val message = WsHelpers.gameStateSyncMessage(
                phase = gameState.phase.toString(),
                currentTurnPlayerId = currentTurnPlayerId,
                deckRemainingCount = gameState.deckOrder.size,
                discardPile = discardPile,
                players = playerSummaries,
                apples = appleSummaries,
                myHand = myHand
            )

            wsManager.sendToPlayer(playerId, message)
        }
    }

    suspend fun sendBlackAppleUpdate(
        gameState: GameState,
        roomId: UUID,
        wsManager: WebSocketManager
    ) {
        val blackPlayer = gameState.players.values.find { it.role == Role.BLACK }
            ?: return

        val poisonApples = gameState.apples
            .filter { it.isPoisoned }
            .map { mapOf(
                "appleId" to it.appleId.toString(),
                "currentHolderPlayerId" to it.currentHolderPlayerId.toString()
            ) }

        wsManager.sendToPlayer(blackPlayer.playerId, WsHelpers.blackAppleUpdateMessage(poisonApples))
    }
}
