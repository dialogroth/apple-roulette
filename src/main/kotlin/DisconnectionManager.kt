package com.example

import java.util.*

data class DisconnectedPlayer(
    val playerId: UUID,
    val disconnectTime: Long = System.currentTimeMillis()
) {
    fun isExpired(durationMs: Long = 60000): Boolean {
        return System.currentTimeMillis() - disconnectTime > durationMs
    }
}

object DisconnectionManager {

    private val disconnectedPlayers = mutableMapOf<UUID, DisconnectedPlayer>()

    fun registerDisconnection(playerId: UUID) {
        disconnectedPlayers[playerId] = DisconnectedPlayer(playerId)
        // 1分後に自動削除するタイマー
        GameTimeoutManager.startDisconnectTimer(playerId) {
            handleDisconnectionTimeout(playerId)
        }
    }

    fun handleReconnection(playerId: UUID): Boolean {
        if (playerId in disconnectedPlayers) {
            val disconnected = disconnectedPlayers[playerId]!!
            if (!disconnected.isExpired()) {
                // 1分以内の再接続 -> 復帰可能
                disconnectedPlayers.remove(playerId)
                GameTimeoutManager.cancelDisconnectTimer(playerId)
                return true
            }
        }
        return false
    }

    private fun handleDisconnectionTimeout(playerId: UUID) {
        val disconnected = disconnectedPlayers[playerId]
        if (disconnected != null && disconnected.isExpired()) {
            disconnectedPlayers.remove(playerId)
            // プレイヤーは死亡扱いになる
        }
    }

    fun isPlayerDisconnected(playerId: UUID): Boolean {
        return playerId in disconnectedPlayers
    }

    fun clearDisconnection(playerId: UUID) {
        disconnectedPlayers.remove(playerId)
        GameTimeoutManager.cancelDisconnectTimer(playerId)
    }
}

