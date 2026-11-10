package com.wiggyoats.squareoff

import com.github.bhlangonijr.chesslib.Board
import com.wiggyoats.squareoff.board.Slot
import com.wiggyoats.squareoff.board.buildPath
import com.wiggyoats.squareoff.board.planMove
import com.wiggyoats.squareoff.board.planPark
import com.wiggyoats.squareoff.board.planRetrieve
import com.wiggyoats.squareoff.board.planRoute
import com.wiggyoats.squareoff.game.Action
import com.wiggyoats.squareoff.game.Graveyard
import com.wiggyoats.squareoff.game.planArrange
import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the Kotlin planner against output from the Python version (python_fixtures.txt). */
class ParityTest {
    // Diagonal routes deliberately differ from Python: they end with a straight
    // leg ending a touch past the centre, because diagonal arrivals left
    // pieces off-centre on the board.
    private val ROUTE_CHANGES = mapOf(
        "c3f6" to "2,2:2.5,2.5:4.5,2.5:4.5,4.5:5,4.5:5,5.08|",
        "h8a1" to "7,7:6.5,6.5:0.5,6.5:0.5,0.5:0,0.5:0,0|",
    )

    private val lines = javaClass.classLoader!!.getResource("python_fixtures.txt")!!.readText().lines().filter { it.isNotBlank() }

    private fun desc(actions: List<Action>) = actions.joinToString(" ") {
        when (it) {
            is Action.Path -> "path:" + buildPath(it.points)
            is Action.Note -> "note"
            is Action.Capture -> "capture:${it.from}:${it.to}:${it.slot.x},${it.slot.y}"
            is Action.Park -> "park:${it.square}:${it.slot.x},${it.slot.y}"
            is Action.Retrieve -> "retrieve:${it.slot.x},${it.slot.y}:${it.square}"
            is Action.Route -> "route:${it.from}:${it.to}"
            is Action.Slide -> "move:${it.from}:${it.to}"
            Action.Beep -> "beep"
        }
    }

    @Test fun paths() {
        for (l in lines) {
            val p = l.split(" ")
            when (p[0]) {
                "move" -> assertEquals(l, p[3], buildPath(planMove(p[1], p[2])))
                "route" -> assertEquals(l, ROUTE_CHANGES[p[1] + p[2]] ?: p[3], buildPath(planRoute(p[1], p[2])))
                "park" -> assertEquals(l, p[4], buildPath(planPark(p[1], Slot(p[2].toInt(), p[3].toInt()))))
                "retrieve" -> assertEquals(l, p[4], buildPath(planRetrieve(Slot(p[2].toInt(), p[3].toInt()), p[1])))
            }
        }
    }

    @Test fun arrangeAndReset() {
        val arranges = lines.filter { it.startsWith("arrange ") }
        val resets = lines.filter { it.startsWith("reset ") }
        for ((a, r) in arranges.zip(resets)) {
            val (head, expected, notes) = a.split("|")
            val fen = head.removePrefix("arrange ")
            val grave = Graveyard()
            val target = Board().apply { loadFromFen(fen) }
            val (acts, n) = planArrange(Board(), target, grave)
            assertEquals(fen, expected, desc(acts))
            assertEquals(notes.toInt(), n.size)
            val (_, expectedReset, resetNotes) = r.split("|")
            val (acts2, n2) = planArrange(target, Board(), grave)
            assertEquals("reset $fen", expectedReset, desc(acts2))
            assertEquals(resetNotes.toInt(), n2.size)
        }
    }
}
