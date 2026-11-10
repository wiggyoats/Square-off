package com.wiggyoats.squareoff.engine

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlin.random.Random

/** Bot strengths, easiest first. */
enum class Level(val label: String, val depth: Int, val timeMs: Long, val blunder: Double, val noise: Int) {
    BEGINNER("Beginner", 1, 300, 0.35, 150),
    EASY("Easy", 2, 500, 0.15, 80),
    INTERMEDIATE("Intermediate", 3, 800, 0.05, 30),
    ADVANCED("Advanced", 4, 1500, 0.0, 10),
    HARD("Hard", 6, 2500, 0.0, 0),
}

private val VALUE = intArrayOf(100, 320, 330, 500, 900, 0) // indexed by PieceType.ordinal

// Piece-square tables from White's side, a8 first (simplified-eval tables).
private val PST = arrayOf(
    intArrayOf( // pawn
        0, 0, 0, 0, 0, 0, 0, 0, 50, 50, 50, 50, 50, 50, 50, 50, 10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5, 0, 0, 0, 20, 20, 0, 0, 0, 5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5, 0, 0, 0, 0, 0, 0, 0, 0,
    ),
    intArrayOf( // knight
        -50, -40, -30, -30, -30, -30, -40, -50, -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30, -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30, -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40, -50, -40, -30, -30, -30, -30, -40, -50,
    ),
    intArrayOf( // bishop
        -20, -10, -10, -10, -10, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10, -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10, -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10, -20, -10, -10, -10, -10, -10, -10, -20,
    ),
    intArrayOf( // rook
        0, 0, 0, 0, 0, 0, 0, 0, 5, 10, 10, 10, 10, 10, 10, 5, -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5, -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5, 0, 0, 0, 5, 5, 0, 0, 0,
    ),
    intArrayOf( // queen
        -20, -10, -10, -5, -5, -10, -10, -20, -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10, -5, 0, 5, 5, 5, 5, 0, -5, 0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10, -10, 0, 5, 0, 0, 0, 0, -10, -20, -10, -10, -5, -5, -10, -10, -20,
    ),
    intArrayOf( // king (middlegame)
        -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30, -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20, -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20, 20, 30, 10, 0, 0, 10, 30, 20,
    ),
)

private const val MATE = 100_000

/**
 * A small alpha-beta engine (iterative deepening, quiescence on captures).
 * Lower levels search shallower, add noise to scores and sometimes play a
 * random move, so they feel human rather than just slow.
 */
class Bot(val level: Level, private val random: Random = Random.Default) {
    private var deadline = 0L
    private var stopped = false
    // The first depth always finishes, so a slow (cold) phone still gets a real move.
    private var canStop = false

    fun selectMove(position: Board): Move? {
        val b = position.clone()
        val legal = b.legalMoves()
        if (legal.isEmpty()) return null
        if (random.nextDouble() < level.blunder) return legal.random(random)

        deadline = System.currentTimeMillis() + level.timeMs
        stopped = false
        var best: Move = legal.first()
        var order = order(b, legal)
        for (depth in 1..level.depth) {
            canStop = depth > 1
            var bestHere: Move? = null
            var bestScore = -MATE * 2
            for (m in order) {
                b.doMove(m)
                // Full window at the root: the noise needs exact scores, not bounds.
                val score = -search(b, depth - 1, -MATE * 2, MATE * 2, 1) + noise()
                b.undoMove()
                if (stopped) break
                if (score > bestScore) {
                    bestScore = score
                    bestHere = m
                }
            }
            if (stopped) break
            if (bestHere != null) {
                best = bestHere
                order = listOf(bestHere) + order.filter { it != bestHere }
            }
            if (bestScore > MATE / 2) break
        }
        return best
    }

    private fun noise() = if (level.noise == 0) 0 else random.nextInt(-level.noise, level.noise + 1)

    private fun timeUp(): Boolean {
        if (canStop && System.currentTimeMillis() > deadline) stopped = true
        return stopped
    }

    private fun search(b: Board, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        if (timeUp()) return 0
        if (b.isRepetition(2) || b.halfMoveCounter >= 100) return 0
        if (depth <= 0) return quiesce(b, alphaIn, beta, 0)
        val legal = b.legalMoves()
        if (legal.isEmpty()) return if (b.isKingAttacked) -MATE + ply else 0
        var alpha = alphaIn
        for (m in order(b, legal)) {
            b.doMove(m)
            val score = -search(b, depth - 1, -beta, -alpha, ply + 1)
            b.undoMove()
            if (stopped) return 0
            if (score >= beta) return score
            if (score > alpha) alpha = score
        }
        return alpha
    }

    private fun quiesce(b: Board, alphaIn: Int, beta: Int, qply: Int): Int {
        val stand = evaluate(b)
        if (stand >= beta || qply > 6) return stand
        var alpha = maxOf(alphaIn, stand)
        val captures = b.legalMoves().filter { b.getPiece(it.to) != Piece.NONE || it.promotion != Piece.NONE }
        for (m in order(b, captures)) {
            b.doMove(m)
            val score = -quiesce(b, -beta, -alpha, qply + 1)
            b.undoMove()
            if (score >= beta) return score
            if (score > alpha) alpha = score
        }
        return alpha
    }

    /** Captures first, most valuable victim / least valuable attacker. */
    private fun order(b: Board, moves: List<Move>): List<Move> = moves.sortedByDescending { m ->
        val victim = b.getPiece(m.to)
        var s = 0
        if (victim != Piece.NONE) s += 10 * VALUE[victim.pieceType.ordinal] - VALUE[b.getPiece(m.from).pieceType.ordinal] / 10
        if (m.promotion != Piece.NONE) s += 8000
        s
    }

    /** Material plus piece-square bonuses, from the side to move's view. */
    private fun evaluate(b: Board): Int {
        var score = 0
        for (s in Square.values()) {
            if (s == Square.NONE) continue
            val p = b.getPiece(s)
            if (p == Piece.NONE) continue
            val t = p.pieceType.ordinal
            val idx = s.ordinal
            val file = idx % 8
            val rank = idx / 8
            val v = if (p.pieceSide == Side.WHITE) {
                VALUE[t] + PST[t][(7 - rank) * 8 + file]
            } else {
                -(VALUE[t] + PST[t][rank * 8 + file])
            }
            score += v
        }
        return if (b.sideToMove == Side.WHITE) score else -score
    }
}
