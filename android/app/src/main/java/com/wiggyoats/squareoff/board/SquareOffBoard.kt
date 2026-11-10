package com.wiggyoats.squareoff.board

import android.util.Log
import com.wiggyoats.squareoff.ble.BleLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val TAG = "SquareOff"

/**
 * The Square Off SWAP protocol, reverse-engineered from the official app.
 *
 * Piece movement uses its own service: motion paths go to MOVE, session state
 * ("S:po", "R:ISG") to STATE, and the board reports "OK" (move finished) and
 * piece lifts/drops ("e2u", "e4d") on STATUS. OCC streams a 64-char 0/1
 * occupancy string, file-major (a1..a8, b1..b8, ...). The older Nordic UART
 * text channel (`<id>#<data>*`) still does the beep and error codes.
 */
object Uuids {
    val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val MOVE: UUID = UUID.fromString("f9664d70-93ff-4cfe-9bfe-b5866aa5bef2")
    val STATE: UUID = UUID.fromString("c7d64c44-42f0-11ec-81d3-0242ac130003")
    val CONFIG: UUID = UUID.fromString("c7d64c45-42f0-11ec-81d3-0242ac130003")
    val STATUS: UUID = UUID.fromString("4496994f-2600-4e7e-81d5-e0f7b67ebd48")
    val OCC: UUID = UUID.fromString("777ac5a4-6fa8-474b-841d-091bd57d28c4")
}

/** Error codes on the text channel ("0#26*") meaning the motor needs re-homing. */
val CALIBRATION_ERROR_CODES = setOf("26", "27", "28")

class BoardNotResponding : Exception(
    "The board didn't confirm a move. It may have gone idle or be low on battery. " +
        "Check the pieces, then try again."
)

class SquareOffBoard(private val link: BleLink) {
    /** Board turned round 180 degrees (you sit on the other side). */
    @Volatile var flipped = false

    /** "e2u" / "e4d" / "OK" and anything else on STATUS, in logical orientation. */
    var onStatus: ((String) -> Unit)? = null
    var onOccupancy: ((String) -> Unit)? = null
    var onCalibrationError: ((String) -> Unit)? = null

    private var moveAck: CompletableDeferred<Unit>? = null
    private var lastOccupancy: String? = null
    private val nusBuffer = StringBuilder()

    /** The slowest move seen on hardware took about 12.5 s. */
    var ackTimeoutMs = 30_000L
    var postMoveSettleMs = 400L

    suspend fun start() {
        link.onNotify = ::handleNotify
        link.enableNotify(Uuids.NUS_TX)
        if (!link.enableNotify(Uuids.STATUS) || !link.has(Uuids.MOVE)) {
            throw IllegalStateException("This doesn't look like a Square Off SWAP (no motion service).")
        }
        link.enableNotify(Uuids.OCC)
    }

    // --- incoming -----------------------------------------------------------

    private fun handleNotify(uuid: UUID, bytes: ByteArray) {
        val text = String(bytes, Charsets.US_ASCII)
        when (uuid) {
            Uuids.STATUS -> handleStatus(text.trim())
            Uuids.OCC -> handleOccupancy(text.trim())
            Uuids.NUS_TX -> handleNus(text)
        }
    }

    private fun handleStatus(raw: String) {
        if (raw.isEmpty()) return
        Log.i(TAG, "RX STATUS $raw")
        val text = if (raw.length >= 3 && raw.last() in "ud") flipSquare(raw.dropLast(1)) + raw.last() else raw
        if (text == "OK") moveAck?.complete(Unit)
        onStatus?.invoke(text)
    }

    private fun handleOccupancy(raw: String) {
        if (raw.length != 64) return
        val text = if (flipped) String(CharArray(64) { i -> raw[(7 - i / 8) * 8 + (7 - i % 8)] }) else raw
        if (text != lastOccupancy) {
            lastOccupancy = text
            onOccupancy?.invoke(text)
        }
    }

    private fun handleNus(chunk: String) {
        Log.i(TAG, "RX NUS $chunk")
        nusBuffer.append(chunk)
        while (true) {
            val end = nusBuffer.indexOf("*")
            if (end < 0) break
            val msg = nusBuffer.substring(0, end).trim()
            nusBuffer.delete(0, end + 1)
            val id = msg.substringBefore('#', "")
            val payload = msg.substringAfter('#', "")
            if (id == "0" && payload in CALIBRATION_ERROR_CODES) onCalibrationError?.invoke(payload)
        }
        if (nusBuffer.length > 256) nusBuffer.clear()
    }

    private fun flipSquare(name: String): String {
        if (!flipped || name.length != 2 || name[0] !in 'a'..'h' || name[1] !in '1'..'8') return name
        return "${'h' - (name[0] - 'a')}${'8' - (name[1] - '1')}"
    }

    // --- outgoing -----------------------------------------------------------

    private suspend fun state(text: String) {
        Log.i(TAG, "TX STATE $text")
        link.write(Uuids.STATE, text.toByteArray(Charsets.US_ASCII))
    }

    /** Text-channel command, e.g. 27#ck* (the check sound). */
    private suspend fun sendText(id: Int, data: String) {
        val payload = "$id#$data*".toByteArray(Charsets.US_ASCII)
        for (i in payload.indices step 20) {
            link.write(Uuids.NUS_RX, payload.copyOfRange(i, minOf(i + 20, payload.size)), withResponse = false)
        }
    }

    /** Wakes the board into the ready state; it ignores paths after idling. */
    suspend fun handshake() {
        state("S:po")
        link.write(Uuids.CONFIG, "0".toByteArray(Charsets.US_ASCII))
        state("R:ISG")
    }

    /**
     * Sends one path in a single write (splitting it makes the board drop the
     * link) and waits for the board's "OK", so the next path can't collide.
     */
    suspend fun sendPath(points: List<Pt>, settle: Boolean = true) {
        val path = buildPath(orient(points, flipped))
        Log.i(TAG, "TX MOVE $path")
        val ack = CompletableDeferred<Unit>()
        moveAck = ack
        link.write(Uuids.MOVE, path.toByteArray(Charsets.US_ASCII))
        if (settle) state("S:po")
        try {
            withTimeout(ackTimeoutMs) { ack.await() }
        } catch (e: TimeoutCancellationException) {
            throw BoardNotResponding()
        } finally {
            moveAck = null
        }
        delay(postMoveSettleMs)
    }

    suspend fun move(from: String, to: String) = sendPath(planMove(from, to))

    suspend fun route(from: String, to: String) = sendPath(planRoute(from, to))

    suspend fun park(square: String, slot: Slot) = sendPath(planPark(square, slot))

    suspend fun retrieve(slot: Slot, square: String) = sendPath(planRetrieve(slot, square))

    /** Park the victim first, then slide the attacker onto the empty square. */
    suspend fun capture(from: String, to: String, slot: Slot) {
        sendPath(planPark(to, slot), settle = false)
        sendPath(planMove(from, to))
    }

    suspend fun beep() = sendText(27, "ck")
}
