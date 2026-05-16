package com.example

import java.util.*
import kotlin.random.Random

class GameEngine {

    companion object {
        private val games = mutableMapOf<UUID, GameState>()

        fun createGame(
            gameId: UUID,
            roomId: UUID,
            playerIds: List<UUID>,
            roles: List<Role>,
            cardSettings: Map<CardType, Int>,
            poisonAppleCount: Int
        ): GameState {
            // プレイヤーマップを作成
            val playerMap = playerIds.mapIndexed { index, playerId ->
                playerId to GamePlayer(
                    playerId = playerId,
                    userName = "", // TODO: DBから取得
                    seatOrder = index,
                    role = roles[index],
                    faction = roles[index].toFaction(),
                    isAlive = true,
                    isConnected = true
                )
            }.toMap()

            // リンゴを作成・シャッフル
            val apples = createAndShuffleApples(playerIds, poisonAppleCount)

            // カードを作成・シャッフル
            val (cardMap, deckOrder, discardOrder) = createAndShuffleCards(playerIds, cardSettings)

            // 最初の手番プレイヤーをランダムに決定
            val initialTurnIndex = Random.nextInt(playerIds.size)
            val turnOrder = (0 until playerIds.size).map { i ->
                playerIds[(initialTurnIndex + i) % playerIds.size]
            }

            val gameState = GameState(
                gameId = gameId,
                roomId = roomId,
                phase = GamePhase.STORY,
                turnOrder = turnOrder,
                currentTurnIndex = 0,
                lastTurnStartPlayerIndex = null,
                players = playerMap,
                apples = apples,
                cards = cardMap,
                deckOrder = deckOrder,
                discardOrder = discardOrder,
                queenSpecialDone = false
            )

            games[gameId] = gameState
            return gameState
        }

        fun getGame(gameId: UUID): GameState? = games[gameId]

        fun updateGame(gameId: UUID, state: GameState) {
            games[gameId] = state
        }

        fun removeGame(gameId: UUID) {
            games.remove(gameId)
        }

        // ============================================================================
        // ゲーム初期化ヘルパーメソッド
        // ============================================================================

        private fun createAndShuffleApples(
            playerIds: List<UUID>,
            poisonAppleCount: Int
        ): List<Apple> {
            val apples = mutableListOf<Apple>()

            // 毒リンゴを作成
            repeat(poisonAppleCount) {
                apples.add(Apple(
                    appleId = UUID.randomUUID(),
                    isPoisoned = true,
                    currentHolderPlayerId = UUID.randomUUID(), // 仮のID
                    isPubliclyRevealed = false,
                    privatelyKnownBy = emptySet()
                ))
            }

            // 通常リンゴを作成
            repeat(playerIds.size - poisonAppleCount) {
                apples.add(Apple(
                    appleId = UUID.randomUUID(),
                    isPoisoned = false,
                    currentHolderPlayerId = UUID.randomUUID(), // 仮のID
                    isPubliclyRevealed = false,
                    privatelyKnownBy = emptySet()
                ))
            }

            // シャッフルして各プレイヤーに配布
            apples.shuffle()
            return apples.mapIndexed { index, apple ->
                apple.copy(
                    currentHolderPlayerId = playerIds[index],
                    privatelyKnownBy = setOf(playerIds[index]) // 本人のみ知っている
                )
            }
        }

        private fun createAndShuffleCards(
            playerIds: List<UUID>,
            cardSettings: Map<CardType, Int>
        ): Triple<Map<UUID, GameCard>, List<UUID>, List<UUID>> {
            val cards = mutableMapOf<UUID, GameCard>()
            val deckOrder = mutableListOf<UUID>()

            // カードを生成
            cardSettings.forEach { (cardType, count) ->
                repeat(count) {
                    val cardId = UUID.randomUUID()
                    cards[cardId] = GameCard(
                        cardId = cardId,
                        cardType = cardType,
                        location = CardLocation.DECK,
                        holderPlayerId = null
                    )
                    deckOrder.add(cardId)
                }
            }

            // シャッフル
            deckOrder.shuffle()

            // 各プレイヤーに1枚ずつ配布
            val dealtCardIds = mutableSetOf<UUID>()
            playerIds.forEachIndexed { index, playerId ->
                if (index < deckOrder.size) {
                    val cardId = deckOrder[index]
                    cards[cardId] = cards[cardId]!!.copy(
                        location = CardLocation.HAND,
                        holderPlayerId = playerId
                    )
                    dealtCardIds.add(cardId)
                }
            }

            // 山札から配布済みカードを削除
            val remainingDeck = deckOrder.filter { it !in dealtCardIds }

            return Triple(cards, remainingDeck, emptyList())
        }

        // ============================================================================
        // ゲーム進行ヘルパーメソッド
        // ============================================================================

        fun getCurrentPlayer(gameState: GameState): GamePlayer {
            val currentPlayerId = gameState.turnOrder[gameState.currentTurnIndex]
            return gameState.players[currentPlayerId] ?: throw RuntimeException("Player not found")
        }

        fun getNextTurnState(gameState: GameState): GameState {
            val nextIndex = (gameState.currentTurnIndex + 1) % gameState.turnOrder.size
            return gameState.copy(currentTurnIndex = nextIndex)
        }

        fun drawCard(gameState: GameState, fromDeck: Boolean = true): Pair<GameState, GameCard?> {
            if (gameState.deckOrder.isEmpty()) {
                return Pair(gameState, null)
            }

            val cardId = gameState.deckOrder.first()
            val card = gameState.cards[cardId] ?: return Pair(gameState, null)
            val currentPlayerId = gameState.turnOrder[gameState.currentTurnIndex]

            val updatedCard = card.copy(
                location = CardLocation.HAND,
                holderPlayerId = currentPlayerId
            )

            val updatedCards = gameState.cards.toMutableMap()
            updatedCards[cardId] = updatedCard

            val updatedDeckOrder = gameState.deckOrder.drop(1)

            val newPhase = if (updatedDeckOrder.isEmpty()) GamePhase.LAST_TURN else gameState.phase
            val newLastTurnStartIndex = if (newPhase == GamePhase.LAST_TURN && gameState.lastTurnStartPlayerIndex == null) {
                gameState.currentTurnIndex
            } else {
                gameState.lastTurnStartPlayerIndex
            }

            val updatedState = gameState.copy(
                cards = updatedCards,
                deckOrder = updatedDeckOrder,
                phase = newPhase,
                lastTurnStartPlayerIndex = newLastTurnStartIndex
            )

            return Pair(updatedState, updatedCard)
        }

        fun playerHasCard(gameState: GameState, playerId: UUID, cardType: CardType): Boolean {
            return gameState.cards.values.any { card ->
                card.location == CardLocation.HAND &&
                card.holderPlayerId == playerId &&
                card.cardType == cardType
            }
        }

        fun getPlayerHand(gameState: GameState, playerId: UUID): List<GameCard> {
            return gameState.cards.values.filter { card ->
                card.location == CardLocation.HAND && card.holderPlayerId == playerId
            }
        }

        fun discardCard(gameState: GameState, cardId: UUID): GameState {
            val card = gameState.cards[cardId] ?: return gameState

            val updatedCard = card.copy(
                location = CardLocation.DISCARD,
                holderPlayerId = null
            )

            val updatedCards = gameState.cards.toMutableMap()
            updatedCards[cardId] = updatedCard

            val updatedDiscardOrder = gameState.discardOrder + cardId

            return gameState.copy(
                cards = updatedCards,
                discardOrder = updatedDiscardOrder
            )
        }

        fun revealAppleToPublic(gameState: GameState, appleId: UUID): GameState {
            val appleIndex = gameState.apples.indexOfFirst { it.appleId == appleId }
            if (appleIndex == -1) return gameState

            val apple = gameState.apples[appleIndex]
            val updatedApple = apple.copy(isPubliclyRevealed = true)

            val updatedApples = gameState.apples.toMutableList()
            updatedApples[appleIndex] = updatedApple

            return gameState.copy(apples = updatedApples)
        }

        fun exchangeApples(gameState: GameState, playerAId: UUID, playerBId: UUID): GameState {
            val appleA = gameState.apples.find { it.currentHolderPlayerId == playerAId }
                ?: return gameState
            val appleB = gameState.apples.find { it.currentHolderPlayerId == playerBId }
                ?: return gameState

            val updatedApples = gameState.apples.map { apple ->
                when (apple.appleId) {
                    appleA.appleId -> apple.copy(currentHolderPlayerId = playerBId)
                    appleB.appleId -> apple.copy(currentHolderPlayerId = playerAId)
                    else -> apple
                }
            }

            return gameState.copy(apples = updatedApples)
        }

        fun exchangeHand(gameState: GameState, playerAId: UUID, playerBId: UUID): GameState {
            val handA = getPlayerHand(gameState, playerAId)
            val handB = getPlayerHand(gameState, playerBId)

            if (handA.isEmpty() || handB.isEmpty()) return gameState

            val cardAId = handA.first().cardId
            val cardBId = handB.first().cardId

            val updatedCards = gameState.cards.toMutableMap()
            updatedCards[cardAId] = updatedCards[cardAId]!!.copy(holderPlayerId = playerBId)
            updatedCards[cardBId] = updatedCards[cardBId]!!.copy(holderPlayerId = playerAId)

            return gameState.copy(cards = updatedCards)
        }

        // アップルルーレットのリンゴ交換
        fun rotateApplesClockwise(gameState: GameState, steps: Int): GameState {
            // 生存し、死亡していないプレイヤーのみを対象
            val aliveSeatOrders = gameState.players.values
                .filter { it.isAlive }
                .sortedBy { it.seatOrder }
                .map { it.seatOrder }

            if (aliveSeatOrders.isEmpty()) return gameState

            val updatedApples = gameState.apples.map { apple ->
                val currentHolderPlayer = gameState.players[apple.currentHolderPlayerId]
                if (currentHolderPlayer == null || !currentHolderPlayer.isAlive) {
                    return@map apple
                }

                val currentIndex = aliveSeatOrders.indexOf(currentHolderPlayer.seatOrder)
                if (currentIndex == -1) return@map apple

                val newIndex = (currentIndex + steps) % aliveSeatOrders.size
                val newSeatOrder = aliveSeatOrders[newIndex]
                val newHolderId = gameState.players.values
                    .find { it.seatOrder == newSeatOrder }
                    ?.playerId

                if (newHolderId != null) {
                    apple.copy(currentHolderPlayerId = newHolderId)
                } else {
                    apple
                }
            }

            return gameState.copy(apples = updatedApples)
        }

        fun rotateApplesCounterClockwise(gameState: GameState, steps: Int): GameState {
            val aliveSeatOrders = gameState.players.values
                .filter { it.isAlive }
                .sortedBy { it.seatOrder }
                .map { it.seatOrder }

            if (aliveSeatOrders.isEmpty()) return gameState

            val updatedApples = gameState.apples.map { apple ->
                val currentHolderPlayer = gameState.players[apple.currentHolderPlayerId]
                if (currentHolderPlayer == null || !currentHolderPlayer.isAlive) {
                    return@map apple
                }

                val currentIndex = aliveSeatOrders.indexOf(currentHolderPlayer.seatOrder)
                if (currentIndex == -1) return@map apple

                val newIndex = (currentIndex - steps).mod(aliveSeatOrders.size)
                val newSeatOrder = aliveSeatOrders[newIndex]
                val newHolderId = gameState.players.values
                    .find { it.seatOrder == newSeatOrder }
                    ?.playerId

                if (newHolderId != null) {
                    apple.copy(currentHolderPlayerId = newHolderId)
                } else {
                    apple
                }
            }

            return gameState.copy(apples = updatedApples)
        }

        fun killPlayer(gameState: GameState, playerId: UUID): GameState {
            val player = gameState.players[playerId] ?: return gameState
            val updatedPlayer = player.copy(isAlive = false)

            val updatedPlayers = gameState.players.toMutableMap()
            updatedPlayers[playerId] = updatedPlayer

            return gameState.copy(players = updatedPlayers)
        }

        fun determineWinFaction(gameState: GameState): Faction {
            val snowWhitePlayer = gameState.players.values.find { it.role == Role.SNOW_WHITE }
            val rosePlayer = gameState.players.values.find { it.role == Role.ROSE }

            return when {
                snowWhitePlayer == null || !snowWhitePlayer.isAlive -> Faction.QUEEN_FACTION
                rosePlayer == null || !rosePlayer.isAlive -> Faction.SNOW_WHITE_FACTION
                else -> Faction.THIRD_FACTION
            }
        }
    }
}

