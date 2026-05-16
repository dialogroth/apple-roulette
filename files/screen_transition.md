# 画面遷移図：白雪姫のアップルルーレット

**バージョン：** 1.4  
**作成日：** 2026年5月10日  

---

## 画面一覧

| 画面名 | フェイズ | 説明 |
|-------|---------|------|
| スタート画面 | 準備 | ユーザー名入力・ルーム選択 |
| ルーム作成画面 | 準備 | ゲーム設定（毒リンゴ・役職・カード枚数） |
| ルーム参加画面 | 準備 | ルームコード入力 |
| ルーム待機画面 | 準備 | 参加者確認・ゲーム開始待ち |
| ゲームメイン画面 | ゲーム | ストーリーフェイズ / 最後の手番フェイズ |

> **補足：** ストーリーフェイズと最後の手番フェイズは同一画面。山札が0になると `PHASE_CHANGED { newPhase: "LAST_TURN" }` を受信し、フェイズバーのテキストが「ストーリーフェイズ」→「最後の手番フェイズ」に変わるのみで専用画面への遷移はない。
| エンディング画面 | ゲーム | 女王特権処理 / 全員リンゴ公開 |
| 勝敗画面 | 終了 | 勝利陣営・役職公開・再ゲーム選択 |

---

## 遷移一覧

| 遷移元 | 遷移先 | 種別 | トリガー |
|-------|-------|------|---------|
| スタート画面 | ルーム作成画面 | 通常 | 「部屋を作る」ボタン押下 |
| スタート画面 | ルーム参加画面 | 通常 | 「部屋に入る」ボタン押下 |
| ルーム作成画面 | ルーム待機画面 | 通常 | ルーム作成完了 |
| ルーム参加画面 | ルーム待機画面 | 通常 | ルームコード入力・参加成功 |
| ルーム待機画面 | ゲームメイン画面 | 通常 | ホストがゲーム開始ボタン押下 |
| ゲームメイン画面 | ゲームメイン画面（内部状態変化） | 内部 | 山札が0になると最後の手番フェイズへ移行（フェイズバーのテキスト変更のみ・画面遷移なし） |
| ゲームメイン画面 | エンディング画面 | 通常 | 最後の手番フェイズ終了 |
| ゲームメイン画面 | 勝敗画面 | **強制** | **白雪姫が1分間再接続しなかった場合（エンディング画面をスキップ）** |
| エンディング画面 | 勝敗画面 | 通常 | 勝敗判定完了 |
| 勝敗画面 | ルーム待機画面 | 戻り | 「もう一度遊ぶ」押下（ホストのみ） |
| 勝敗画面 | スタート画面 | 戻り | 「ルームを解散する」押下 |
| ルーム待機画面 | スタート画面 | 戻り | 「ルームを解散する」押下 |

## 補足：画面内の動的コンテンツ

画面遷移を伴わないが、受信イベントによってコンテンツが動的に切り替わる主要なケース。

| 画面 | イベント | 変化内容 |
|-----|---------|---------|
| ゲームメイン | `PHASE_CHANGED { newPhase: "LAST_TURN" }` | フェイズバー「ストーリーフェイズ」→「最後の手番フェイズ」 |
| ゲームメイン | `REQUEST_PREFERENCE` | 下部エリアが好み回答UIに切り替わる |
| ゲームメイン | `NOTIFY_PLAYER_DIED` | 該当プレイヤーカードが死亡表示（透過＋骸骨）に変わる |
| ゲームメイン | `NOTIFY_GRAY_ABILITY_ACTIVATED` | 該当プレイヤーの選択画面に「トレード不可」バッジが表示される |
| ゲームメイン | `NOTIFY_LIGHT_ABILITY_ACTIVATED` | ライブテキストに能力発動と交換内容を表示。プレイヤー選択画面は②手札の交換と同じUIで2名選択式（チェックボックス） |
| ゲームメイン | `GAME_RESULT { reason: "SNOW_WHITE_DISCONNECTED" }` | **エンディング画面を経由せず即座に勝敗画面（強制終了パターン）へ遷移** |
| 勝敗画面 | `NOTIFY_REMATCH_STARTING` | **ルーム待機画面へ遷移**（ホストの `REMATCH_REQUEST` 送信後に全員が受信） |

---

## 遷移図（SVG）

<svg width="100%" viewBox="0 0 680 750" role="img">
<title>白雪姫のアップルルーレット 画面遷移図</title>
<desc>スタート画面からゲーム終了まで、7つの画面とその遷移を示す図</desc>
<defs>
  <marker id="arr" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
    <path d="M2 1L8 5L2 9" fill="none" stroke="#555" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
  </marker>
</defs>

<!-- スタート画面 -->
<rect x="240" y="50" width="200" height="56" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="340" y="71" text-anchor="middle" dominant-baseline="central">スタート画面</text>
<text font-family="sans-serif" font-size="12" fill="#0F6E56" x="340" y="91" text-anchor="middle" dominant-baseline="central">ユーザー名入力</text>

<!-- ルーム作成画面 -->
<rect x="80" y="165" width="180" height="44" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="170" y="187" text-anchor="middle" dominant-baseline="central">ルーム作成画面</text>

<!-- ルーム参加画面 -->
<rect x="420" y="165" width="180" height="44" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="510" y="187" text-anchor="middle" dominant-baseline="central">ルーム参加画面</text>

<!-- ルーム待機画面 -->
<rect x="240" y="275" width="200" height="56" rx="8" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#085041" x="340" y="296" text-anchor="middle" dominant-baseline="central">ルーム待機画面</text>
<text font-family="sans-serif" font-size="12" fill="#0F6E56" x="340" y="316" text-anchor="middle" dominant-baseline="central">ゲーム開始待ち</text>

<!-- ゲームメイン画面 -->
<rect x="230" y="400" width="220" height="56" rx="8" fill="#EEEDFE" stroke="#534AB7" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#26215C" x="340" y="421" text-anchor="middle" dominant-baseline="central">ゲームメイン画面</text>
<text font-family="sans-serif" font-size="12" fill="#534AB7" x="340" y="441" text-anchor="middle" dominant-baseline="central">ストーリー / 最後の手番</text>

<!-- エンディング画面 -->
<rect x="230" y="525" width="220" height="56" rx="8" fill="#EEEDFE" stroke="#534AB7" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#26215C" x="340" y="546" text-anchor="middle" dominant-baseline="central">エンディング画面</text>
<text font-family="sans-serif" font-size="12" fill="#534AB7" x="340" y="566" text-anchor="middle" dominant-baseline="central">女王特権 / リンゴ公開</text>

<!-- 勝敗画面 -->
<rect x="240" y="650" width="200" height="56" rx="8" fill="#FAEEDA" stroke="#BA7517" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="14" font-weight="500" fill="#412402" x="340" y="671" text-anchor="middle" dominant-baseline="central">勝敗画面</text>
<text font-family="sans-serif" font-size="12" fill="#854F0B" x="340" y="691" text-anchor="middle" dominant-baseline="central">役職公開 / 勝利陣営</text>

<!-- 前向き遷移 -->
<path d="M 280 106 L 170 106 L 170 165" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="224" y="120" text-anchor="middle">部屋を作る</text>
<path d="M 400 106 L 510 106 L 510 165" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="457" y="120" text-anchor="middle">部屋に入る</text>
<path d="M 170 209 L 170 303 L 240 303" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<path d="M 510 209 L 510 303 L 440 303" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<path d="M 340 331 L 340 400" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="356" y="368" text-anchor="start">ゲーム開始</text>
<path d="M 340 456 L 340 525" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>
<path d="M 340 581 L 340 650" fill="none" stroke="#555" stroke-width="1" opacity="0.6" marker-end="url(#arr)"/>

<!-- 強制終了遷移（ゲームメイン→勝敗 エンディングスキップ） -->
<defs>
  <marker id="arr-red" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
    <path d="M2 1L8 5L2 9" fill="none" stroke="#E24B4A" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
  </marker>
</defs>
<path d="M 450 456 L 565 456 L 565 678 L 440 678" fill="none" stroke="#E24B4A" stroke-width="1" stroke-dasharray="5 3" opacity="0.7" marker-end="url(#arr-red)"/>
<text font-family="sans-serif" font-size="10" fill="#E24B4A" x="569" y="540" text-anchor="start" opacity="0.9">強制終了</text>
<text font-family="sans-serif" font-size="10" fill="#E24B4A" x="569" y="554" text-anchor="start" opacity="0.9">（白雪姫</text>
<text font-family="sans-serif" font-size="10" fill="#E24B4A" x="569" y="568" text-anchor="start" opacity="0.9">1分未復帰）</text>

<!-- 戻り遷移（破線） -->
<path d="M 240 678 L 22 678 L 22 315 L 240 315" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="5 3" opacity="0.55" marker-end="url(#arr)"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="132" y="700" text-anchor="middle">もう一度遊ぶ</text>
<path d="M 440 678 L 610 678 L 610 78 L 440 78" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="5 3" opacity="0.55" marker-end="url(#arr)"/>
<path d="M 440 303 L 610 303" fill="none" stroke="#555" stroke-width="1" stroke-dasharray="5 3" opacity="0.55" marker-end="url(#arr)"/>
<circle cx="610" cy="303" r="3" fill="#555" opacity="0.5"/>
<circle cx="610" cy="678" r="3" fill="#555" opacity="0.5"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="445" y="700" text-anchor="start">ルームを解散</text>
<text font-family="sans-serif" font-size="12" fill="#555" x="445" y="295" text-anchor="start">ルームを解散</text>

<!-- 凡例 -->
<line x1="44" y1="728" x2="80" y2="728" stroke="#555" stroke-width="1" opacity="0.7"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="86" y="732">通常遷移</text>
<line x1="164" y1="728" x2="200" y2="728" stroke="#555" stroke-width="1" stroke-dasharray="5 3" opacity="0.7"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="206" y="732">戻り遷移</text>
<line x1="294" y1="728" x2="330" y2="728" stroke="#E24B4A" stroke-width="1" stroke-dasharray="5 3" opacity="0.7"/>
<text font-family="sans-serif" font-size="12" fill="#E24B4A" x="336" y="732">強制終了</text>
<rect x="410" y="720" width="12" height="12" rx="2" fill="#E1F5EE" stroke="#0F6E56" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="428" y="732">準備フェイズ</text>
<rect x="516" y="720" width="12" height="12" rx="2" fill="#EEEDFE" stroke="#534AB7" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="534" y="732">ゲームフェイズ</text>
<rect x="632" y="720" width="12" height="12" rx="2" fill="#FAEEDA" stroke="#BA7517" stroke-width="0.5"/>
<text font-family="sans-serif" font-size="12" fill="#555" x="650" y="732">終了フェイズ</text>
</svg>
