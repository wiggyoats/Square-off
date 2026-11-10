package com.wiggyoats.squareoff.game

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.wiggyoats.squareoff.board.Slot

/**
 * The two parking banks off the board edges, with slots labelled by piece type
 * (confirmed on hardware).
 *
 * Right bank x=8 (inner), x=9 (outer): pawns y=0..3, knight 4, bishop 5,
 * rook 6, queen 7 (two queen slots, one spare for a promotion). The left bank
 * x=-1, x=-2 is its 180-degree mirror (y -> 7-y). Black captures go right,
 * white captures go left.
 */
private val TYPE_RANKS = mapOf(
    PieceType.PAWN to listOf(0, 1, 2, 3),
    PieceType.KNIGHT to listOf(4),
    PieceType.BISHOP to listOf(5),
    PieceType.ROOK to listOf(6),
    PieceType.QUEEN to listOf(7),
)
private val RIGHT_COLS = listOf(8, 9)
private val LEFT_COLS = listOf(-1, -2)

private fun bankColumns(side: Side) = if (side == Side.BLACK) RIGHT_COLS else LEFT_COLS

fun slotsFor(piece: Piece): List<Slot> {
    val cols = bankColumns(piece.pieceSide)
    var ranks = TYPE_RANKS[piece.pieceType] ?: listOf(7)
    if (cols[0] < 0) ranks = ranks.map { 7 - it }
    return cols.flatMap { c -> ranks.map { r -> Slot(c, r) } }
}

class Graveyard {
    val occupied = linkedMapOf<Slot, Piece>()

    /**
     * A free slot labelled for this piece's type; if those are full (a third
     * knight after a promotion) any free slot on its bank, never doubling up.
     */
    fun alloc(piece: Piece): Slot {
        val typed = slotsFor(piece)
        val bank = bankColumns(piece.pieceSide).flatMap { c -> (0..7).map { Slot(c, it) } }
        val slot = (typed + bank).firstOrNull { it !in occupied } ?: typed.last()
        occupied[slot] = piece
        return slot
    }

    fun free(slot: Slot) {
        occupied.remove(slot)
    }

    /** Releases a slot holding this kind of piece, or returns null. */
    fun take(piece: Piece): Slot? {
        val slot = occupied.entries.firstOrNull { it.value == piece }?.key ?: return null
        occupied.remove(slot)
        return slot
    }

    val size get() = occupied.size
}
