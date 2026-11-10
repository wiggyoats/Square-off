package com.wiggyoats.squareoff.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

/** Squares from a FEN, indexed rank*8+file (a1 = 0); '.' when empty. */
fun fenSquares(fen: String): CharArray {
    val out = CharArray(64) { '.' }
    val rows = fen.substringBefore(' ').split('/')
    for ((i, row) in rows.withIndex()) {
        val rank = 7 - i
        var file = 0
        for (c in row) {
            if (c.isDigit()) file += c - '0' else if (file < 8) out[rank * 8 + file++] = c
        }
    }
    return out
}

// Solid glyphs for both colours (tinted below); U+FE0E stops the pawn turning into an emoji.
private fun glyph(c: Char): String = when (c.lowercaseChar()) {
    'k' -> "♚"; 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; 'n' -> "♞"; else -> "♟"
} + "︎"

private fun name(file: Int, rank: Int) = "${'a' + file}${rank + 1}"

/**
 * The position, drawn from the player's side. Tap a piece, then a square, to
 * move on screen (handy without the board, or to correct it).
 */
@Composable
fun BoardView(
    fen: String,
    whiteAtBottom: Boolean,
    lastMove: Pair<String, String>?,
    enabled: Boolean,
    onMove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val squares = fenSquares(fen)
    var selected by remember { mutableStateOf<String?>(null) }
    val measurer = rememberTextMeasurer()
    val whiteToMove = fen.split(' ').getOrNull(1) != "b"

    fun toFileRank(cell: Int, row: Int) =
        if (whiteAtBottom) cell to 7 - row else 7 - cell to row

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(fen, whiteAtBottom, enabled) {
                detectTapGestures { pos ->
                    if (!enabled) return@detectTapGestures
                    val cell = size.width / 8f
                    val (f, r) = toFileRank((pos.x / cell).toInt().coerceIn(0, 7), (pos.y / cell).toInt().coerceIn(0, 7))
                    val sq = name(f, r)
                    val piece = squares[r * 8 + f]
                    val own = piece != '.' && piece.isUpperCase() == whiteToMove
                    val from = selected
                    selected = when {
                        own -> if (from == sq) null else sq
                        from != null -> {
                            onMove(from + sq); null
                        }
                        else -> null
                    }
                }
            }
    ) {
        val cell = size.width / 8f
        val fontSize = (cell * 0.78f).toSp()
        val lastSquares = lastMove?.toList().orEmpty()
        for (row in 0 until 8) for (col in 0 until 8) {
            val (f, r) = toFileRank(col, row)
            val topLeft = Offset(col * cell, row * cell)
            val sz = Size(cell, cell)
            drawRect(if ((f + r) % 2 == 0) DarkSquare else LightSquare, topLeft, sz)
            val sq = name(f, r)
            if (sq in lastSquares) drawRect(Highlight, topLeft, sz)
            if (sq == selected) drawRect(Selected, topLeft, sz)

            val p = squares[r * 8 + f]
            if (p != '.') {
                val white = p.isUpperCase()
                val text = glyph(p)
                val fill = measurer.measure(
                    text, TextStyle(fontSize = fontSize, color = if (white) Color.White else Color(0xFF222222), drawStyle = Fill)
                )
                val pos = Offset(
                    topLeft.x + (cell - fill.size.width) / 2f,
                    topLeft.y + (cell - fill.size.height) / 2f,
                )
                drawText(fill, topLeft = pos)
                val outline = measurer.measure(
                    text,
                    TextStyle(
                        fontSize = fontSize,
                        color = if (white) Color(0xFF222222) else Color(0xFF888888),
                        drawStyle = Stroke(width = cell * 0.03f),
                    )
                )
                drawText(outline, topLeft = pos)
            }
            // Coordinates on the edge squares.
            val labelStyle = TextStyle(fontSize = (cell * 0.18f).toSp(), color = if ((f + r) % 2 == 0) LightSquare else DarkSquare)
            if (row == 7) drawText(measurer.measure("${'a' + f}", labelStyle), topLeft = Offset(topLeft.x + cell * 0.80f, topLeft.y + cell * 0.74f))
            if (col == 0) drawText(measurer.measure("${r + 1}", labelStyle), topLeft = Offset(topLeft.x + cell * 0.05f, topLeft.y + cell * 0.02f))
        }
    }
}
