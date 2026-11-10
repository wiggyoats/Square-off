package com.wiggyoats.squareoff.board

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign

/**
 * Motion paths for the Square Off SWAP motor.
 *
 * The board moves a piece by following waypoints written to the MOVE
 * characteristic as `x0,y0:x1,y1:...|` where x = file (a=0..h=7) and
 * y = rank-1 (0..7). Whole numbers are square centres; .5 values sit on the
 * gridlines between squares, so a piece travelling on them never bumps another.
 * x < 0 or x > 7 is the off-board parking channel.
 */
data class Pt(val x: Double, val y: Double)

/** A parking slot off the board edge, e.g. (8, 4) = right bank, knight row. */
data class Slot(val x: Int, val y: Int)

private fun pt(x: Number, y: Number) = Pt(x.toDouble(), y.toDouble())

fun squareXY(square: String): Pair<Int, Int> {
    val s = square.trim().lowercase()
    require(s.length == 2 && s[0] in 'a'..'h' && s[1] in '1'..'8') { "bad square: $square" }
    return (s[0] - 'a') to (s[1] - '1')
}

fun squareName(x: Int, y: Int): String = "${'a' + x}${y + 1}"

/** How far past the centre a route's last leg goes, in squares. */
const val OVERSHOOT = 0.08

private fun fmt(v: Double): String {
    val r = round(v * 100) / 100
    return if (abs(r - round(r)) < 1e-9) round(r).toLong().toString() else r.toString()
}

/** Repeated waypoints are dropped; zero-length legs are no use to the motor. */
fun buildPath(points: List<Pt>): String =
    points.filterIndexed { i, p -> i == 0 || p != points[i - 1] }
        .joinToString(":") { "${fmt(it.x)},${fmt(it.y)}" } + "|"

/** Rotate 180 degrees, for playing with the board turned round. */
fun orient(points: List<Pt>, flipped: Boolean): List<Pt> =
    if (!flipped) points else points.map { Pt(7 - it.x, 7 - it.y) }

/** Ordinary move. Knights thread along the gridline between the squares. */
fun planMove(from: String, to: String): List<Pt> {
    val (fx, fy) = squareXY(from)
    val (tx, ty) = squareXY(to)
    val dx = tx - fx
    val dy = ty - fy
    if (setOf(abs(dx), abs(dy)) == setOf(1, 2)) {
        return if (abs(dy) == 2) {
            val midx = (fx + tx) / 2.0
            val s = sign(dy.toDouble())
            listOf(pt(fx, fy), pt(midx, fy + s * 0.5), pt(midx, ty - s * 0.5), pt(tx, ty))
        } else {
            val midy = (fy + ty) / 2.0
            val s = sign(dx.toDouble())
            listOf(pt(fx, fy), pt(fx + s * 0.5, midy), pt(tx - s * 0.5, midy), pt(tx, ty))
        }
    }
    return listOf(pt(fx, fy), pt(tx, ty))
}

/** The gridline beside a slot column, on the board side of it. */
private fun channelGridline(sx: Int): Double = if (sx > 0) sx - 0.5 else sx + 0.5

/**
 * Carry the piece on [square] out to [slot] without touching anything:
 * onto a half-rank gridline, out to the gap beside the slot's column, along that
 * gap to the slot's rank, then into the slot. Never goes below y=0 (the motor
 * grinds against the frame there).
 */
fun planPark(square: String, slot: Slot): List<Pt> {
    val (fx, fy) = squareXY(square)
    val gy = if (fy < 7) fy + 0.5 else fy - 0.5
    val gx = channelGridline(slot.x)
    return listOf(pt(fx, fy), pt(fx, gy), pt(gx, gy), pt(gx, slot.y), pt(slot.x, slot.y))
}

/** Exact reverse of [planPark]: bring a parked piece back onto [square]. */
fun planRetrieve(slot: Slot, square: String): List<Pt> {
    val (tx, ty) = squareXY(square)
    val gy = if (ty < 7) ty + 0.5 else ty - 0.5
    val gx = channelGridline(slot.x)
    return listOf(pt(slot.x, slot.y), pt(gx, slot.y), pt(gx, gy), pt(tx, gy), pt(tx, ty))
}

/** Collision-safe move along the gridlines, used when arranging a position. */
fun planRoute(from: String, to: String): List<Pt> {
    val (fx, fy) = squareXY(from)
    val (tx, ty) = squareXY(to)
    if (fx == tx && fy == ty) return listOf(pt(fx, fy))
    if (fx == tx) {
        val gx = if (fx < 7) fx + 0.5 else fx - 0.5
        return listOf(pt(fx, fy), pt(gx, fy), pt(gx, ty), pt(tx, ty))
    }
    if (fy == ty) {
        val gy = if (fy < 7) fy + 0.5 else fy - 0.5
        return listOf(pt(fx, fy), pt(fx, gy), pt(tx, gy), pt(tx, ty))
    }
    val sx = if (tx > fx) 0.5 else -0.5
    val sy = if (ty > fy) 0.5 else -0.5
    // The last leg is straight and ends a touch past the centre: pieces that
    // arrived diagonally sat well off-centre on hardware, and heavy ones (queens)
    // still lagged the magnet a little. The official app overshoots like this.
    return listOf(
        pt(fx, fy),
        pt(fx + sx, fy + sy),
        pt(tx - sx, fy + sy),
        pt(tx - sx, ty - sy),
        pt(tx, ty - sy),
        // Never below y=0: the motor grinds against the frame there.
        pt(tx, (ty + sy * OVERSHOOT * 2).coerceAtLeast(0.0)),
    )
}

/** Castling rook: along the gridline next to the back rank, past the king. */
fun planRookCastle(rookFrom: String, rookTo: String): List<Pt> {
    val (fx, fy) = squareXY(rookFrom)
    val (tx, ty) = squareXY(rookTo)
    val gy = if (fy < 7) fy + 0.5 else fy - 0.5
    return listOf(pt(fx, fy), pt(fx, gy), pt(tx, gy), pt(tx, ty))
}
