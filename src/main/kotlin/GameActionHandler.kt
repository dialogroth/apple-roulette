package com.example

import java.util.*

class GameActionHandler {

    companion object {

        // ================================================================================
        // ① 山札を引く
        // ================================================================================
        fun drawCard(gameState: GameState, currentPlayerId: UUID): Pair<GameState, String?> {
            if (gameState.deckOrder.isEmpty()) {
                return Pair(gameState, "山札が空です")
            }

            // 既に手札が2枚か確認（引いた後のチェック）
            val currentHand = GameEngine.getPlayerHand(gameState, currentPlayerId)
            if (currentHand.size > 1) {
                return Pair(gameState, "既に2枚の手札があります")
            }

            val (updatedState, drawnCard) = GameEngine.drawCard(gameState)

            return if (drawnCard != null) {
                Pair(updatedState, null)
            } else {
                Pair(gameState, "カードを引けません")
            }
        }

        // ================================================================================
        // カードを使用する
        // ================================================================================
        fun useCard(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val card = gameState.cards[cardId]
                ?: return Pair(gameState, "カードが見つかりません")

            if (card.location != CardLocation.HAND || card.holderPlayerId != currentPlayerId) {
                return Pair(gameState, "手札にそのカードがありません")
            }

            // フェイズチェック（最後の手番フェイズでは使用不可なカード）
            if (gameState.phase == GamePhase.LAST_TURN && !isLastTurnCard(card.cardType)) {
                if (card.cardType in listOf(CardType.ROPE, CardType.POISON_COMB,
                        CardType.CURSED_RING, CardType.KNIGHT, CardType.GUARD)) {
                    // 最後の手番フェイズで使用可能なカード
                } else if (card.cardType != CardType.POISON_COMB) {
                    // ストーリーフェイズのカアード
                }
            }

            return when (card.cardType) {
                CardType.APPLE_QUESTION -> useAppleQuestion(gameState, currentPlayerId, cardId, params)
                CardType.MUSHROOM_QUESTION -> usMushroomQuestion(gameState, currentPlayerId, cardId, params)
                CardType.ITADAKIMASU -> useItadakimasu(gameState, currentPlayerId, cardId, params)
                CardType.ROULETTE_1 -> useRoulette(gameState, currentPlayerId, cardId, params, 1)
                CardType.ROULETTE_2 -> useRoulette(gameState, currentPlayerId, cardId, params, 2)
                CardType.ROULETTE_3 -> useRoulette(gameState, currentPlayerId, cardId, params, 3)
                CardType.KNIFE -> useKnife(gameState, currentPlayerId, cardId, params)
                CardType.ROPE -> useRope(gameState, currentPlayerId, cardId, params)
                CardType.PRESENT_EXCHANGE -> usePresentExchange(gameState, currentPlayerId, cardId, params)
                CardType.POISON_COMB -> usePoisonComb(gameState, currentPlayerId, cardId, params)
                else -> Pair(gameState, "このカードは使用できません")
            }
        }

        private fun isLastTurnCard(cardType: CardType): Boolean {
            return cardType in listOf(
                CardType.APPLE_QUESTION, CardType.MUSHROOM_QUESTION, CardType.ITADAKIMASU,
                CardType.ROULETTE_1, CardType.ROULETTE_2, CardType.ROULETTE_3,
                CardType.KNIFE, CardType.ROPE, CardType.PRESENT_EXCHANGE, CardType.POISON_COMB
            )
        }

        private fun useAppleQuestion(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val targetPlayerId = (params["targetPlayerId"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "対象プレイヤーを指定してください")

            val targetPlayer = gameState.players[targetPlayerId]
                ?: return Pair(gameState, "対象プレイヤーが見つかりません")

            if (!targetPlayer.isAlive) {
                return Pair(gameState, "死亡したプレイヤーを対象に指定できません")
            }

            // カードを捨て山に移動
            var updatedState = GameEngine.discardCard(gameState, cardId)

            // プレイヤーの好み回答を記録（この場ではまだ回答を受け付けない）
            // 実際にはクライアント側で「リンゴは好き？」で回答を求めるUIを表示
            return Pair(updatedState, null)
        }

        private fun usMushroomQuestion(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val targetPlayerId = (params["targetPlayerId"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "対象プレイヤーを指定してください")

            val targetPlayer = gameState.players[targetPlayerId]
                ?: return Pair(gameState, "対象プレイヤーが見つかりません")

            if (!targetPlayer.isAlive) {
                return Pair(gameState, "死亡したプレイヤーを対象に指定できません")
            }

            var updatedState = GameEngine.discardCard(gameState, cardId)
            return Pair(updatedState, null)
        }

        private fun useItadakimasu(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val count = (params["count"] as? Number)?.toInt() ?: 1

            if (count < 1 || count > 3) {
                return Pair(gameState, "捨てる枚数は1〜3枚です")
            }

            if (count > gameState.deckOrder.size) {
                return Pair(gameState, "山札の枚数が不足しています")
            }

            // 指定枚数のカードを捨て山に移動
            var updatedState = gameState
            repeat(count) { i ->
                if (i < updatedState.deckOrder.size) {
                    val discardCardId = updatedState.deckOrder[i]
                    updatedState = GameEngine.discardCard(updatedState, discardCardId)
                }
            }

            // 使用したカード自体も捨て山に移動
            updatedState = GameEngine.discardCard(updatedState, cardId)

            // 山札が空になったか確認
            if (updatedState.deckOrder.isEmpty() && updatedState.phase == GamePhase.STORY) {
                // 最後の手番フェイズへ移行
                updatedState = updatedState.copy(
                    phase = GamePhase.LAST_TURN,
                    lastTurnStartPlayerIndex = updatedState.currentTurnIndex
                )
            }

            return Pair(updatedState, null)
        }

        private fun useRoulette(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>,
            steps: Int
        ): Pair<GameState, String?> {
            val direction = params["direction"] as? String
                ?: return Pair(gameState, "方向を指定してください（CLOCKWISE / COUNTER_CLOCKWISE）")

            var updatedState = gameState

            // リンゴをローテーション
            updatedState = when (direction) {
                "CLOCKWISE" -> GameEngine.rotateApplesClockwise(updatedState, steps)
                "COUNTER_CLOCKWISE" -> GameEngine.rotateApplesCounterClockwise(updatedState, steps)
                else -> return Pair(gameState, "不正な方向です")
            }

            // カードを捨て山に移動
            updatedState = GameEngine.discardCard(updatedState, cardId)

            return Pair(updatedState, null)
        }

        private fun useKnife(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val targetPlayerId = (params["targetPlayerId"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "対象プレイヤーを指定してください")

            val targetApple = gameState.apples.find { it.currentHolderPlayerId == targetPlayerId }
                ?: return Pair(gameState, "対象プレイヤーのリンゴが見つかりません")

            // リンゴを全体公開
            var updatedState = gameState
            val appleIndex = updatedState.apples.indexOfFirst { it.appleId == targetApple.appleId }
            if (appleIndex != -1) {
                updatedState = GameEngine.revealAppleToPublic(updatedState, targetApple.appleId)
            }

            // カードを捨て山に移動
            updatedState = GameEngine.discardCard(updatedState, cardId)

            return Pair(updatedState, null)
        }

        private fun useRope(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val targetPlayerId = (params["targetPlayerId"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "対象プレイヤーを指定してください")

            val targetPlayer = gameState.players[targetPlayerId]
                ?: return Pair(gameState, "対象プレイヤーが見つかりません")

            if (!targetPlayer.isAlive) {
                return Pair(gameState, "死亡したプレイヤーを対象に指定できません")
            }

            // 対象プレイヤーの次の手番をスキップ
            var updatedState = gameState
            updatedState = updatedState.copy(
                players = updatedState.players.toMutableMap().apply {
                    put(targetPlayerId, get(targetPlayerId)!!.copy(skipNextTurn = true))
                }
            )

            // カードを捨て山に移動
            updatedState = GameEngine.discardCard(updatedState, cardId)

            return Pair(updatedState, null)
        }

        private fun usePresentExchange(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val playerIdA = (params["targetPlayerIdA"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "交換対象のプレイヤーAを指定してください")
            val playerIdB = (params["targetPlayerIdB"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "交換対象のプレイヤーBを指定してください")

            // グレイの保護チェック
            val playerA = gameState.players[playerIdA] ?: return Pair(gameState, "プレイヤーが見つかりません")
            val playerB = gameState.players[playerIdB] ?: return Pair(gameState, "プレイヤーが見つかりません")

            if (playerA.isProtected || playerB.isProtected) {
                return Pair(gameState, "保護中のプレイヤーを対象に指定できません")
            }

            // 手札の交換
            var updatedState = GameEngine.exchangeHand(gameState, playerIdA, playerIdB)

            // カードを捨て山に移動
            updatedState = GameEngine.discardCard(updatedState, cardId)

            return Pair(updatedState, null)
        }

        private fun usePoisonComb(
            gameState: GameState,
            currentPlayerId: UUID,
            cardId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            // 毒の櫛は最後の手番フェイズのみ
            if (gameState.phase != GamePhase.LAST_TURN) {
                return Pair(gameState, "毒の櫛は最後の手番フェイズのみ使用できます")
            }

            val targetPlayerId = (params["targetPlayerId"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "対象プレイヤーを指定してください")

            val targetPlayer = gameState.players[targetPlayerId]
                ?: return Pair(gameState, "対象プレイヤーが見つかりません")

            if (!targetPlayer.isAlive) {
                return Pair(gameState, "既に死亡しているプレイヤーを対象に指定できません")
            }

            var updatedState = gameState

            // 騎士による無効化チェック（白雪姫のみ）
            if (targetPlayer.role == Role.SNOW_WHITE &&
                GameEngine.playerHasCard(updatedState, targetPlayerId, CardType.KNIGHT)) {
                // 無効化される
                return Pair(updatedState, "KNIGHT_BLOCKED")
            }

            // 対象プレイヤーを死亡させる
            updatedState = GameEngine.killPlayer(updatedState, targetPlayerId)

            // カードを捨て山に移動
            updatedState = GameEngine.discardCard(updatedState, cardId)

            return Pair(updatedState, null)
        }

        // ================================================================================
        // カードを廃棄する
        // ================================================================================
        fun discardCard(gameState: GameState, currentPlayerId: UUID, cardId: UUID): Pair<GameState, String?> {
            val card = gameState.cards[cardId]
                ?: return Pair(gameState, "カードが見つかりません")

            if (card.location != CardLocation.HAND || card.holderPlayerId != currentPlayerId) {
                return Pair(gameState, "手札にそのカードがありません")
            }

            val currentHand = GameEngine.getPlayerHand(gameState, currentPlayerId)
            if (gameState.phase == GamePhase.STORY && currentHand.size < 2) {
                return Pair(gameState, "山札を引いた後にカードを捨ててください")
            }

            // 呪いの指輪は廃棄不可
            if (card.cardType == CardType.CURSED_RING) {
                return Pair(gameState, "呪いの指輪は廃棄できません")
            }

            val updatedState = GameEngine.discardCard(gameState, cardId)
            return Pair(updatedState, null)
        }

        // ================================================================================
        // ② 手札の交換
        // ================================================================================
        fun exchangeHand(
            gameState: GameState,
            currentPlayerId: UUID,
            targetPlayerId: UUID
        ): Pair<GameState, String?> {
            if (targetPlayerId == currentPlayerId) {
                return Pair(gameState, "自分自身と交換できません")
            }

            val targetPlayer = gameState.players[targetPlayerId]
                ?: return Pair(gameState, "対象プレイヤーが見つかりません")

            if (!targetPlayer.isAlive) {
                return Pair(gameState, "死亡したプレイヤーを対象に指定できません")
            }

            // グレイの保護チェック
            if (targetPlayer.isProtected) {
                return Pair(gameState, "対象プレイヤーは保護中です")
            }

            val updatedState = GameEngine.exchangeHand(gameState, currentPlayerId, targetPlayerId)
            return Pair(updatedState, null)
        }

        // ================================================================================
        // ③ リンゴの交換
        // ================================================================================
        fun exchangeApple(
            gameState: GameState,
            currentPlayerId: UUID,
            targetPlayerId: UUID
        ): Pair<GameState, String?> {
            if (targetPlayerId == currentPlayerId) {
                return Pair(gameState, "自分自身と交換できません")
            }

            val targetPlayer = gameState.players[targetPlayerId]
                ?: return Pair(gameState, "対象プレイヤーが見つかりません")

            if (!targetPlayer.isAlive) {
                return Pair(gameState, "死亡したプレイヤーを対象に指定できません")
            }

            // グレイの保護チェック
            if (targetPlayer.isProtected) {
                return Pair(gameState, "対象プレイヤーは保護中です")
            }

            val updatedState = GameEngine.exchangeApples(gameState, currentPlayerId, targetPlayerId)
            return Pair(updatedState, null)
        }

        // ================================================================================
        // ④ 自分のリンゴの確認
        // ================================================================================
        fun checkOwnApple(gameState: GameState, currentPlayerId: UUID): Pair<GameState, String?> {
            val myApple = gameState.apples.find { it.currentHolderPlayerId == currentPlayerId }
                ?: return Pair(gameState, "リンゴが見つかりません")

            // 本人により「privatelyKnownBy」に追加（既に含まれている可能性）
            var updatedState = gameState
            val appleIndex = updatedState.apples.indexOfFirst { it.appleId == myApple.appleId }
            if (appleIndex != -1) {
                val updatedApple = updatedState.apples[appleIndex].copy(
                    privatelyKnownBy = updatedState.apples[appleIndex].privatelyKnownBy + currentPlayerId
                )
                val newApples = updatedState.apples.toMutableList()
                newApples[appleIndex] = updatedApple
                updatedState = updatedState.copy(apples = newApples)
            }

            return Pair(updatedState, null)
        }

        // ================================================================================
        // ⑤ キャラクター能力の発動
        // ================================================================================
        fun useAbility(
            gameState: GameState,
            currentPlayerId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val currentPlayer = gameState.players[currentPlayerId]
                ?: return Pair(gameState, "プレイヤーが見つかりません")

            return when (currentPlayer.role) {
                Role.GRAY -> useGrayAbility(gameState, currentPlayerId)
                Role.LIGHT -> useLightAbility(gameState, currentPlayerId, params)
                else -> Pair(gameState, "この役職には能力がありません")
            }
        }

        private fun useGrayAbility(gameState: GameState, currentPlayerId: UUID): Pair<GameState, String?> {
            val player = gameState.players[currentPlayerId]
                ?: return Pair(gameState, "プレイヤーが見つかりません")

            if (player.grayAbilityUsed) {
                return Pair(gameState, "グレイの能力は既に使用済みです")
            }

            // 保護状態を有効化
            var updatedState = gameState
            updatedState = updatedState.copy(
                players = updatedState.players.toMutableMap().apply {
                    put(currentPlayerId, player.copy(
                        grayAbilityUsed = true,
                        isProtected = true
                    ))
                }
            )

            return Pair(updatedState, null)
        }

        private fun useLightAbility(
            gameState: GameState,
            currentPlayerId: UUID,
            params: Map<String, Any?>
        ): Pair<GameState, String?> {
            val player = gameState.players[currentPlayerId]
                ?: return Pair(gameState, "プレイヤーが見つかりません")

            if (player.lightAbilityUsed) {
                return Pair(gameState, "ライトの能力は既に使用済みです")
            }

            val playerIdA = (params["targetPlayerIdA"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "交換対象のプレイヤーAを指定してください")
            val playerIdB = (params["targetPlayerIdB"] as? String)?.let { UUID.fromString(it) }
                ?: return Pair(gameState, "交換対象のプレイヤーBを指定してください")

            val playerA = gameState.players[playerIdA] ?: return Pair(gameState, "プレイヤーが見つかりません")
            val playerB = gameState.players[playerIdB] ?: return Pair(gameState, "プレイヤーが見つかりません")

            // グレイの保護チェック
            if (playerA.isProtected || playerB.isProtected) {
                return Pair(gameState, "保護中のプレイヤーを対象に指定できません")
            }

            // 役職を公開
            // リンゴを交換
            var updatedState = gameState
            updatedState = updatedState.copy(
                players = updatedState.players.toMutableMap().apply {
                    put(currentPlayerId, player.copy(
                        lightAbilityUsed = true,
                        isRoleRevealed = true
                    ))
                }
            )

            updatedState = GameEngine.exchangeApples(updatedState, playerIdA, playerIdB)

            return Pair(updatedState, null)
        }

        // ================================================================================
        // 好み回答を記録
        // ================================================================================
        fun recordPreferenceAnswer(
            gameState: GameState,
            responderId: UUID,
            questionType: String,
            answer: Boolean
        ): Pair<GameState, String?> {
            val player = gameState.players[responderId]
                ?: return Pair(gameState, "プレイヤーが見つかりません")

            var updatedState = gameState
            val updatedPlayer = when (questionType.uppercase()) {
                "APPLE" -> player.copy(applePreferenceAnswer = answer)
                "MUSHROOM" -> player.copy(mushroomPreferenceAnswer = answer)
                else -> return Pair(gameState, "不正な質問タイプです")
            }

            updatedState = updatedState.copy(
                players = updatedState.players.toMutableMap().apply {
                    put(responderId, updatedPlayer)
                }
            )

            return Pair(updatedState, null)
        }

        // ================================================================================
        // 女王の交換対象を選択
        // ================================================================================
        fun queenSelectExchange(
            gameState: GameState,
            queenPlayerId: UUID,
            targetPlayerId: UUID
        ): Pair<GameState, String?> {
            val queen = gameState.players[queenPlayerId]
                ?: return Pair(gameState, "女王が見つかりません")

            if (queen.role != Role.QUEEN) {
                return Pair(gameState, "女王のみが選択できます")
            }

            if (!queen.isAlive) {
                return Pair(gameState, "女王は既に死亡しています")
            }

            val target = gameState.players[targetPlayerId]
                ?: return Pair(gameState, "対象プレイヤーが見つかりません")

            if (!target.isAlive) {
                return Pair(gameState, "死亡したプレイヤーを対象に指定できません")
            }

            val queenApple = gameState.apples.find { it.currentHolderPlayerId == queenPlayerId }
                ?: return Pair(gameState, "女王のリンゴが見つかりません")

            // 毒リンゴでない場合は交換不可
            if (!queenApple.isPoisoned) {
                return Pair(gameState, "女王のリンゴが通常リンゴです")
            }

            // ガード保持チェック
            if (GameEngine.playerHasCard(gameState, targetPlayerId, CardType.GUARD)) {
                return Pair(gameState, "GUARD_ACTIVATED")
            }

            // リンゴを交換
            val updatedState = GameEngine.exchangeApples(gameState, queenPlayerId, targetPlayerId)

            return Pair(updatedState, null)
        }

        // ================================================================================
        // 手番を進める
        // ================================================================================
        fun advanceTurn(
            gameState: GameState
        ): GameState {
            var updatedState = gameState

            var nextIndex = (updatedState.currentTurnIndex + 1) % updatedState.turnOrder.size
            var loopCount = 0

            while (loopCount < updatedState.turnOrder.size) {
                val nextPlayerId = updatedState.turnOrder[nextIndex]
                val nextPlayer = updatedState.players[nextPlayerId]!!

                // 死亡プレイヤーはスキップ（通知不要）
                if (!nextPlayer.isAlive) {
                    nextIndex = (nextIndex + 1) % updatedState.turnOrder.size
                    loopCount++
                    continue
                }

                // ロープスキップ
                if (nextPlayer.skipNextTurn) {
                    // スキップフラグをクリア（isProtectedはここで触らない）
                    updatedState = updatedState.copy(
                        players = updatedState.players.toMutableMap().apply {
                            put(nextPlayerId, nextPlayer.copy(skipNextTurn = false))
                        }
                    )


                    nextIndex = (nextIndex + 1) % updatedState.turnOrder.size
                    loopCount++
                    continue
                }

                // 有効な次の手番プレイヤーが見つかった
                // グレイの保護リセット：グレイ本人の手番が来たときのみ解除
                if (nextPlayer.isProtected && nextPlayer.role == Role.GRAY) {
                    updatedState = updatedState.copy(
                        players = updatedState.players.toMutableMap().apply {
                            put(nextPlayerId, nextPlayer.copy(isProtected = false))
                        }
                    )
                }

                break
            }

            return updatedState.copy(currentTurnIndex = nextIndex)
        }
    }
}

