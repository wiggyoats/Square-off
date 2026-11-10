package com.wiggyoats.squareoff

import com.github.bhlangonijr.chesslib.Board
import com.wiggyoats.squareoff.engine.Bot
import com.wiggyoats.squareoff.engine.Level
import com.wiggyoats.squareoff.game.Action
import com.wiggyoats.squareoff.game.Graveyard
import com.wiggyoats.squareoff.game.parseMove
import com.wiggyoats.squareoff.game.parseSolution
import com.wiggyoats.squareoff.game.physicalActions
import com.wiggyoats.squareoff.game.san
import com.wiggyoats.squareoff.game.uci
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    @Test fun routesEndStraightWithoutRepeats() {
        // d1-c2 used to be 3,0:2.5,0.5:2.5,0.5:2.5,0.5:2,1| and landed off-centre.
        assertEquals("3,0:2.5,0.5:2,0.5:2,1.08|", com.wiggyoats.squareoff.board.buildPath(com.wiggyoats.squareoff.board.planRoute("d1", "c2")))
    }

    @Test fun puzzlePgnsParse() {
        val daily = parseSolution(
            "6k1/pb2rqp1/3p3p/2p3r1/2P5/3N1B2/P1Q2PPP/1R2R2K b - - 0 21",
            "[White \"x\"]\n[FEN \"6k1/pb2rqp1/3p3p/2p3r1/2P5/3N1B2/P1Q2PPP/1R2R2K b - - 0 21\"]\n\n21... Qxf3 22. gxf3 Bxf3# 0-1",
        )
        assertEquals(listOf("f7f3", "g2f3", "b7f3"), daily.map { it.uci() })
        val random = parseSolution(
            "r1b1r3/1p2b1Qp/p2pkn2/5N2/2q1PP2/2N5/PPP3PP/2KR3R w - - 0 1",
            "[Date \"????.??.??\"]\r\n[Result \"*\"]\r\n\r\n1. Rxd6+ Bxd6 2. Nd4+ Qxd4 3. f5+ Ke5 4. Qg3#\r\n*",
        )
        assertEquals(7, random.size)
    }

    @Test fun sanAndUci() {
        val b = Board()
        assertEquals("Nf3", san(b, parseMove(b, "g1f3")!!))
        assertEquals("e2e4", parseMove(b, "e4")!!.uci())
        b.loadFromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        assertEquals("O-O", san(b, parseMove(b, "e1g1")!!))
        assertEquals("e1c1", parseMove(b, "O-O-O")!!.uci())
        b.loadFromFen("4k3/P7/8/8/8/8/8/4K3 w - - 0 1")
        assertEquals("a7a8q", parseMove(b, "a7a8")!!.uci())
        assertEquals("a8=Q+", san(b, parseMove(b, "a7a8q")!!))
        b.loadFromFen("4k3/8/8/1N3N2/8/8/8/4K3 w - - 0 1")
        assertEquals("Nbd4", san(b, parseMove(b, "b5d4")!!))
    }

    @Test fun specialMovesBecomeMotorJobs() {
        val b = Board()
        b.loadFromFen("r3k2r/8/8/8/8/8/8/R3K2R b KQkq - 0 1")
        val castle = physicalActions(b, parseMove(b, "e8g8")!!, Graveyard())
        assertEquals(Action.Slide("e8", "g8"), castle[0])
        assertTrue(castle[1] is Action.Path)
        b.loadFromFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
        val ep = physicalActions(b, parseMove(b, "e5d6")!!, Graveyard())
        assertTrue(ep[0] is Action.Park && (ep[0] as Action.Park).square == "d5")
        b.loadFromFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1")
        val cap = physicalActions(b, parseMove(b, "e4d5")!!, Graveyard())
        assertTrue(cap[0] is Action.Capture)
    }

    @Test fun botFindsMateInOneAndAlwaysMoves() {
        val b = Board()
        b.loadFromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1")
        assertEquals("a1a8", Bot(Level.ADVANCED).selectMove(b)!!.uci())
        for (level in Level.entries) assertNotNull(Bot(level).selectMove(Board()))
    }
}
