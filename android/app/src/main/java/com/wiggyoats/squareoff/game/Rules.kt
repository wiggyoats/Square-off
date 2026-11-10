package com.wiggyoats.squareoff.game

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlin.math.abs

// Small helpers over chesslib: square names, move classification, SAN.

fun sq(name: String): Square = Square.valueOf(name.uppercase())

val Square.n: String get() = value().lowercase()
val Square.fileIdx: Int get() = ordinal % 8
val Square.rankIdx: Int get() = ordinal / 8

fun squareAt(file: Int, rank: Int): Square = Square.values()[rank * 8 + file]

fun Move.uci(): String =
    from.n + to.n + if (promotion != Piece.NONE) letter(promotion.pieceType).lowercase() else ""

fun isCastling(b: Board, m: Move): Boolean =
    b.getPiece(m.from).pieceType == PieceType.KING && abs(m.from.fileIdx - m.to.fileIdx) == 2

fun isEnPassant(b: Board, m: Move): Boolean =
    b.getPiece(m.from).pieceType == PieceType.PAWN && m.from.fileIdx != m.to.fileIdx &&
        b.getPiece(m.to) == Piece.NONE

fun isCapture(b: Board, m: Move): Boolean = b.getPiece(m.to) != Piece.NONE || isEnPassant(b, m)

/** The piece a move takes (en passant included), or null. */
fun capturedPiece(b: Board, m: Move): Piece? = when {
    isEnPassant(b, m) -> Piece.make(b.sideToMove.flip(), PieceType.PAWN)
    b.getPiece(m.to) != Piece.NONE -> b.getPiece(m.to)
    else -> null
}

fun pieceName(t: PieceType): String = t.name.lowercase()
fun sideName(s: Side): String = s.name.lowercase()
fun describe(p: Piece): String = "${sideName(p.pieceSide)} ${pieceName(p.pieceType)}"

fun legalMoves(b: Board): List<Move> = b.legalMoves()

private fun letter(t: PieceType) = when (t) {
    PieceType.KNIGHT -> "N"; PieceType.BISHOP -> "B"; PieceType.ROOK -> "R"
    PieceType.QUEEN -> "Q"; PieceType.KING -> "K"; else -> ""
}

fun san(b: Board, m: Move): String {
    val piece = b.getPiece(m.from)
    val base = if (isCastling(b, m)) {
        if (m.to.fileIdx == 6) "O-O" else "O-O-O"
    } else {
        val sb = StringBuilder()
        val capture = isCapture(b, m)
        if (piece.pieceType == PieceType.PAWN) {
            if (capture) sb.append('a' + m.from.fileIdx).append('x')
        } else {
            sb.append(letter(piece.pieceType))
            val rivals = legalMoves(b).filter {
                it.to == m.to && it.from != m.from && b.getPiece(it.from) == piece
            }
            if (rivals.isNotEmpty()) {
                when {
                    rivals.none { it.from.fileIdx == m.from.fileIdx } -> sb.append('a' + m.from.fileIdx)
                    rivals.none { it.from.rankIdx == m.from.rankIdx } -> sb.append('1' + m.from.rankIdx)
                    else -> sb.append(m.from.n)
                }
            }
            if (capture) sb.append('x')
        }
        sb.append(m.to.n)
        if (m.promotion != Piece.NONE) sb.append('=').append(letter(m.promotion.pieceType))
        sb.toString()
    }
    b.doMove(m)
    val suffix = when {
        b.isMated -> "#"
        b.isKingAttacked -> "+"
        else -> ""
    }
    b.undoMove()
    return base + suffix
}

/** Accepts UCI ("e2e4", "e7e8q") or SAN ("Nf3", "exd5", "O-O"). */
fun parseMove(b: Board, text: String): Move? {
    val t = text.trim()
    val legal = legalMoves(b)
    val lower = t.lowercase()
    legal.firstOrNull { it.uci() == lower }?.let { return it }
    if (lower.length == 4) legal.firstOrNull { it.uci() == lower + "q" }?.let { return it }
    val clean = t.replace(Regex("[+#!?]"), "").replace("0", "O")
    legal.firstOrNull { san(b, it).replace(Regex("[+#]"), "") == clean }?.let { return it }
    return null
}

/** The moves of a puzzle's PGN, played from its FEN. Empty if it can't be read. */
fun parseSolution(fen: String, pgn: String): List<Move> {
    val body = pgn.lines().filterNot { it.trim().startsWith("[") }.joinToString(" ")
        .replace(Regex("\\{[^}]*\\}"), " ")
        .replace(Regex("\\([^)]*\\)"), " ")
    val tokens = body.split(Regex("\\s+"))
        .map { it.replace(Regex("^\\d+\\.+"), "") }
        .filter { it.isNotEmpty() && it !in setOf("*", "1-0", "0-1", "1/2-1/2") && !it.startsWith("$") }
    val b = Board().apply { loadFromFen(fen) }
    val out = mutableListOf<Move>()
    for (tok in tokens) {
        val m = parseMove(b, tok) ?: return emptyList()
        out += m
        b.doMove(m)
    }
    return out
}

fun isGameOver(b: Board): Boolean =
    b.isMated || b.isStaleMate || b.isInsufficientMaterial || b.isRepetition(3) || b.halfMoveCounter >= 100 ||
        legalMoves(b).isEmpty()

fun resultText(b: Board): String = when {
    b.isMated -> "${sideName(b.sideToMove.flip())} wins by checkmate"
    b.isStaleMate || (legalMoves(b).isEmpty() && !b.isKingAttacked) -> "draw by stalemate"
    b.isInsufficientMaterial -> "draw, not enough material"
    b.isRepetition(3) -> "draw by repetition"
    b.halfMoveCounter >= 100 -> "draw by the fifty-move rule"
    else -> "in progress"
}
