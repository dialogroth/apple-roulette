package com.example

import java.util.*

object GameResultHandler {

    // ================================================================================
    // ゲーム終了時の処理
    // ================================================================================
    suspend fun finishGame(
        gameState: GameState,
        roomId: UUID,
        wsManager: WebSocketManager,
        games: MutableMap<UUID, GameState>,
        gamesByRoom: MutableMap<UUID, UUID>,
        rooms: MutableMap<UUID, Room>
    ) {
        // 勝利陣営を判定
        val winFaction = GameEngine.determineWinFaction(gameState)

        // ゲーム結果データを作成
        val resultPlayers = gameState.players.values.map { player ->
            val apple = gameState.apples.find { it.currentHolderPlayerId == player.playerId }
            mapOf(
                "playerId" to player.playerId.toString(),
                "userName" to player.userName,
                "role" to player.role.toString(),
                "faction" to player.faction.toString(),
                "isAlive" to player.isAlive,
                "isWinner" to (player.faction == winFaction && player.isAlive),
                "apple" to mapOf(
                    "appleId" to (apple?.appleId?.toString() ?: ""),
                    "isPoisoned" to (apple?.isPoisoned ?: false)
                )
            )
        }

        // ゲーム結果をブロードキャスト
        wsManager.broadcastToRoom(
            roomId,
            WsHelpers.gameResultMessage(
                winFaction.toString(),
                "NORMAL",
                resultPlayers
            )
        )

        // ルーム状態を更新
        rooms[gameState.roomId]?.let {
            rooms[gameState.roomId] = it.copy(status = RoomStatus.FINISHED)
        }

        // ゲーム状態を削除
        games.remove(gameState.gameId)
        gamesByRoom.remove(roomId)

        // タイマーをクリア
        GameTimeoutManager.clearAllTimers(gameState.gameId)
    }

    // ================================================================================
    // 白雪姫切断による強制終了
    // ================================================================================
    suspend fun forceGameEnd(
        gameState: GameState,
        roomId: UUID,
        wsManager: WebSocketManager,
        games: MutableMap<UUID, GameState>,
        gamesByRoom: MutableMap<UUID, UUID>,
        rooms: MutableMap<UUID, Room>
    ) {
        // 白雪姫が死亡したため女王陣営勝利
        val winFaction = Faction.QUEEN_FACTION

        // ゲーム結果データを作成
        val resultPlayers = gameState.players.values.map { player ->
            val apple = gameState.apples.find { it.currentHolderPlayerId == player.playerId }
            mapOf(
                "playerId" to player.playerId.toString(),
                "userName" to player.userName,
                "role" to player.role.toString(),
                "faction" to player.faction.toString(),
                "isAlive" to player.isAlive,
                "isWinner" to (player.faction == winFaction && player.isAlive),
                "apple" to mapOf(
                    "appleId" to (apple?.appleId?.toString() ?: ""),
                    "isPoisoned" to (apple?.isPoisoned ?: false)
                )
            )
        }

        // ゲーム結果をブロードキャスト（強制終了フラグ付き）
        wsManager.broadcastToRoom(
            roomId,
            WsHelpers.gameResultMessage(
                winFaction.toString(),
                "SNOW_WHITE_DISCONNECTED",
                resultPlayers
            )
        )

        // ルーム状態を更新
        rooms[gameState.roomId]?.let {
            rooms[gameState.roomId] = it.copy(status = RoomStatus.FINISHED)
        }

        // ゲーム状態を削除
        games.remove(gameState.gameId)
        gamesByRoom.remove(roomId)

        // タイマーをクリア
        GameTimeoutManager.clearAllTimers(gameState.gameId)
    }
}

