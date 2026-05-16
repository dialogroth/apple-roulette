package com.example

import java.util.*
import kotlin.concurrent.timer

object GameTimeoutManager {

    private val turnTimers = mutableMapOf<UUID, Timer>()
    private val disconnectTimers = mutableMapOf<UUID, Timer>()
    private val preferenceTimers = mutableMapOf<Pair<UUID, UUID>, Timer>()  // (gameId, playerId) -> timer
    private val queenTimers = mutableMapOf<UUID, Timer>()

    // ================================================================================
    // 手番タイムアウト（3分/180秒）
    // ================================================================================
    fun startTurnTimer(
        gameId: UUID,
        callback: () -> Unit
    ) {
        // 既存タイマーをキャンセル
        turnTimers[gameId]?.cancel()

        val timer = timer(initialDelay = 180000, period = Long.MAX_VALUE) {
            cancel()
            callback()
            turnTimers.remove(gameId)
        }

        turnTimers[gameId] = timer
    }

    fun cancelTurnTimer(gameId: UUID) {
        turnTimers[gameId]?.cancel()
        turnTimers.remove(gameId)
    }

    // ================================================================================
    // 好み回答タイムアウト（1分/60秒）
    // ================================================================================
    fun startPreferenceTimer(
        gameId: UUID,
        playerId: UUID,
        callback: () -> Unit
    ) {
        val key = Pair(gameId, playerId)
        preferenceTimers[key]?.cancel()

        val timer = timer(initialDelay = 60000, period = Long.MAX_VALUE) {
            cancel()
            callback()
            preferenceTimers.remove(key)
        }

        preferenceTimers[key] = timer
    }

    fun cancelPreferenceTimer(gameId: UUID, playerId: UUID) {
        val key = Pair(gameId, playerId)
        preferenceTimers[key]?.cancel()
        preferenceTimers.remove(key)
    }

    // ================================================================================
    // 女王選択タイムアウト（3分/180秒）
    // ================================================================================
    fun startQueenExchangeTimer(
        gameId: UUID,
        callback: () -> Unit
    ) {
        queenTimers[gameId]?.cancel()

        val timer = timer(initialDelay = 180000, period = Long.MAX_VALUE) {
            cancel()
            callback()
            queenTimers.remove(gameId)
        }

        queenTimers[gameId] = timer
    }

    fun cancelQueenExchangeTimer(gameId: UUID) {
        queenTimers[gameId]?.cancel()
        queenTimers.remove(gameId)
    }

    // ================================================================================
    // プレイヤー切断タイムアウト（1分/60秒）
    // ================================================================================
    fun startDisconnectTimer(
        playerId: UUID,
        callback: () -> Unit
    ) {
        disconnectTimers[playerId]?.cancel()

        val timer = timer(initialDelay = 60000, period = Long.MAX_VALUE) {
            cancel()
            callback()
            disconnectTimers.remove(playerId)
        }

        disconnectTimers[playerId] = timer
    }

    fun cancelDisconnectTimer(playerId: UUID) {
        disconnectTimers[playerId]?.cancel()
        disconnectTimers.remove(playerId)
    }

    // ================================================================================
    // 全タイマーをクリア（ゲーム終了時）
    // ================================================================================
    fun clearAllTimers(gameId: UUID) {
        cancelTurnTimer(gameId)
        cancelQueenExchangeTimer(gameId)

        preferenceTimers.keys.filter { it.first == gameId }.forEach { key ->
            preferenceTimers[key]?.cancel()
            preferenceTimers.remove(key)
        }
    }
}

