package com.wiggyoats.squareoff.game

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.wiggyoats.squareoff.board.Pt
import com.wiggyoats.squareoff.board.Slot
import com.wiggyoats.squareoff.board.planRookCastle
import kotlin.math.abs
import kotlin.math.max

/** One physical job for the motor, or a note for the player. */
sealed interface Action {
    data class Slide(val from: String, val to: String) : Action
    data class Route(val from: String, val to: String) : Action
    data class Capture(val from: String, val to: String, val slot: Slot) : Action
    data class Park(val square: String, val slot: Slot) : Action
    data class Retrieve(val slot: Slot, val square: String) : Action
    data class Path(val points: List<Pt>) : Action
    data object Beep : Action
    data class Note(val text: String) : Action
}

/**
 * The motor jobs that play [m] on [b] (the position before the move). Captured
 * pieces go to their labelled slot in [grave].
 */
fun physicalActions(b: Board, m: Move, grave: Graveyard): List<Action> {
    val from = m.from.n
    val to = m.to.n
    val rank = m.from.rankIdx
    if (isCastling(b, m)) {
        val kingside = m.to.fileIdx == 6
        val rookFrom = squareAt(if (kingside) 7 else 0, rank).n
        val rookTo = squareAt(if (kingside) 5 else 3, rank).n
        // King first (its slide is clear), then the rook round it on the gridline.
        return listOf(Action.Slide(from, to), Action.Path(planRookCastle(rookFrom, rookTo)))
    }
    if (isEnPassant(b, m)) {
        val victim = squareAt(m.to.fileIdx, rank)
        return listOf(
            Action.Park(victim.n, grave.alloc(b.getPiece(victim))),
            Action.Slide(from, to),
        )
    }
    val out = mutableListOf<Action>()
    val victim = b.getPiece(m.to)
    out += if (victim != Piece.NONE) Action.Capture(from, to, grave.alloc(victim)) else Action.Slide(from, to)
    if (m.promotion != Piece.NONE) {
        out += Action.Note("Promotion: swap the pawn on $to for a ${pieceName(m.promotion.pieceType)} by hand.")
    }
    return out
}

private fun chebyshev(a: Square, b: Square) = max(abs(a.fileIdx - b.fileIdx), abs(a.rankIdx - b.rankIdx))

// h8 down to a1, the order the Python version (verified on the board) used.
private fun pieceMap(b: Board): Map<Square, Piece> =
    Square.values().filter { it != Square.NONE && b.getPiece(it) != Piece.NONE }.reversed()
        .associateWith { b.getPiece(it) }

/**
 * Motor jobs that turn the physical position [current] into [target], using
 * gridline routes so nothing gets knocked. Pieces already in place stay put;
 * others route to the nearest matching target; cycles are broken by parking one
 * piece and fetching it back; empty targets are filled from [grave]; anything
 * still missing becomes a "place by hand" note. Returns (actions, notes).
 */
fun planArrange(current: Board, target: Board, grave: Graveyard): Pair<List<Action>, List<String>> {
    val cur = pieceMap(current)
    val tgt = pieceMap(target)
    val actions = mutableListOf<Action>()
    val notes = mutableListOf<String>()

    val assignedTargets = mutableSetOf<Square>()
    val usedCur = mutableSetOf<Square>()
    for ((s, p) in cur) if (tgt[s] == p) {
        assignedTargets += s
        usedCur += s
    }

    val remainingCur = cur.keys.filter { it !in usedCur }.toMutableList()
    val assignment = linkedMapOf<Square, Square>() // target -> source
    val remainingTargets = tgt.keys.filter { it !in assignedTargets }
        .sortedByDescending { tgt.getValue(it).pieceType.ordinal }
    for (t in remainingTargets) {
        val best = remainingCur.filter { cur[it] == tgt[t] }.minByOrNull { chebyshev(it, t) } ?: continue
        assignment[t] = best
        remainingCur -= best
    }

    // Extra pieces with nowhere to go (a spare queen): park them first, which
    // also clears any target squares they sit on.
    val occ = cur.keys.toMutableSet()
    for (s in remainingCur) {
        val p = cur.getValue(s)
        actions += Action.Park(s.n, grave.alloc(p))
        occ -= s
        notes += "Removed extra ${describe(p)} from ${s.n} to the tray."
    }

    val pending = LinkedHashMap(assignment)
    val tempParked = mutableListOf<Pair<Slot, Square>>()
    while (pending.isNotEmpty()) {
        var moved = false
        for ((t, s) in pending.entries.toList()) {
            if (s == t) {
                pending.remove(t); moved = true
            } else if (t !in occ) {
                actions += Action.Route(s.n, t.n)
                occ -= s
                occ += t
                pending.remove(t)
                moved = true
            }
        }
        if (!moved) {
            val (t, s) = pending.entries.first()
            val slot = grave.alloc(cur.getValue(s))
            actions += Action.Park(s.n, slot)
            occ -= s
            tempParked += slot to t
            pending.remove(t)
        }
    }

    val filled = (assignedTargets + assignment.keys).toMutableSet()
    for ((slot, t) in tempParked) {
        actions += Action.Retrieve(slot, t.n)
        grave.free(slot)
        filled += t
    }
    for (t in tgt.keys.filter { it !in filled }) {
        val slot = grave.take(tgt.getValue(t))
        if (slot != null) {
            actions += Action.Retrieve(slot, t.n)
        } else {
            notes += "Place a ${describe(tgt.getValue(t))} on ${t.n} by hand."
        }
    }
    return actions to notes
}
