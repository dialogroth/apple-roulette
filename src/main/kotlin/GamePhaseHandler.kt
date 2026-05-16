package com.example

import java.util.*

class GamePhaseHandler {

    companion object {

        // ================================================================================
        // 最後の手番フェイズへの移行を確認
        // ================================================================================
        fun checkAndTransitionToLastTurn(gameState: GameState): GameState {
            if (gameState.deckOrder.isEmpty() && gameState.phase == GamePhase.STORY) {
                val strayIndex = gameState.currentTurnIndex
                return gameState.copy(
                    phase = GamePhase.LAST_TURN,
                    lastTurnStartPlayerIndex = strayIndex
                )
            }
            return gameState
        }

        // ================================================================================
        // 最後の手番フェイズでの手番開始時の処理
        // ================================================================================
        fun handleLastTurnStart(gameState: GameState, currentPlayerId: UUID): Pair<GameState, List<String>> {
            val currentPlayer = gameState.players[currentPlayerId]
                ?: return Pair(gameState, emptyList())

            val events = mutableListOf<String>()
            var updatedState = gameState

            // 呪いの指輪を持っているか確認
            if (GameEngine.playerHasCard(updatedState, currentPlayerId, CardType.CURSED_RING)) {
                // 即座に死亡
                updatedState = GameEngine.killPlayer(updatedState, currentPlayerId)
                events.add("PLAYER_DIED:$currentPlayerId:CURSED_RING")
                return Pair(updatedState, events)
            }

            return Pair(updatedState, events)
        }

        // ================================================================================
        // 最後の手番フェイズの終了判定
        // ================================================================================
        fun checkLastTurnEnd(gameState: GameState): Boolean {
            if (gameState.phase != GamePhase.LAST_TURN) return false
            if (gameState.lastTurnStartPlayerIndex == null) return false

            // 1周したら終了
            return gameState.currentTurnIndex == gameState.lastTurnStartPlayerIndex &&
                    gameState.currentTurnIndex != (gameState.lastTurnStartPlayerIndex + 1) % gameState.turnOrder.size
        }

        // ================================================================================
        // エンディングフェイズへの移行
        // ================================================================================
        fun transitionToEnding(gameState: GameState): GameState {
            if (gameState.phase == GamePhase.LAST_TURN) {
                return gameState.copy(phase = GamePhase.ENDING_QUEEN)
            }
            return gameState
        }

        // ================================================================================
        // エンディングフェイズ：女王の特権処理
        // ================================================================================
        fun processQueenPrivilege(gameState: GameState): Pair<GameState, List<String>> {
            val events = mutableListOf<String>()
            var updatedState = gameState

            val queen = updatedState.players.values.find { it.role == Role.QUEEN }
                ?: return Pair(updatedState.copy(phase = GamePhase.ENDING_REVEAL), events)

            // 女王が死亡している場合はスキップ
            if (!queen.isAlive) {
                updatedState = updatedState.copy(phase = GamePhase.ENDING_REVEAL)
                return Pair(updatedState, events)
            }

            val queenApple = updatedState.apples.find { it.currentHolderPlayerId == queen.playerId }
                ?: return Pair(updatedState.copy(phase = GamePhase.ENDING_REVEAL), events)

            // 女王のリンゴを全体公開
            val appleIndex = updatedState.apples.indexOfFirst { it.appleId == queenApple.appleId }
            if (appleIndex != -1) {
                updatedState = GameEngine.revealAppleToPublic(updatedState, queenApple.appleId)
                events.add("APPLE_REVEALED:${queenApple.appleId}:${queen.playerId}:${queenApple.isPoisoned}")
            }

            // 通常リンゴなら交換不可（ENDING_REVEALへ移行）
            if (!queenApple.isPoisoned) {
                updatedState = updatedState.copy(phase = GamePhase.ENDING_REVEAL)
                return Pair(updatedState, events)
            }

            // 毒リンゴなら交換対象をサーバーが要求する
            updatedState = updatedState.copy(queenSpecialDone = false)  // フラグはまだ立てない
            return Pair(updatedState, events)
        }

        // ================================================================================
        // エンディングフェイズ：全員のリンゴ公開と勝敗判定
        // ================================================================================
        fun processEnding(gameState: GameState): Triple<GameState, List<String>, Faction> {
            val events = mutableListOf<String>()
            var updatedState = gameState

            // 未公開のリンゴをすべて公開
            updatedState.apples.forEach { apple ->
                if (!apple.isPubliclyRevealed) {
                    updatedState = GameEngine.revealAppleToPublic(updatedState, apple.appleId)
                    events.add("APPLE_REVEALED:${apple.appleId}:${apple.currentHolderPlayerId}:${apple.isPoisoned}")
                }
            }

            // 毒リンゴを持つプレイヤーを死亡させる
            updatedState.apples.forEach { apple ->
                if (apple.isPoisoned) {
                    val holder = updatedState.players[apple.currentHolderPlayerId]
                    if (holder != null && holder.isAlive) {
                        updatedState = GameEngine.killPlayer(updatedState, apple.currentHolderPlayerId)
                        events.add("PLAYER_DIED:${apple.currentHolderPlayerId}:POISON_APPLE")
                    }
                }
            }

            // 勝敗判定
            val winFaction = GameEngine.determineWinFaction(updatedState)

            updatedState = updatedState.copy(phase = GamePhase.FINISHED)

            return Triple(updatedState, events, winFaction)
        }
    }
}


