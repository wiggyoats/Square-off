package com.wiggyoats.squareoff.online

import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Blocking HTTP clients; call them from Dispatchers.IO.

private const val USER_AGENT = "SquareOff-Android/2.0 (Square Off board controller)"

private fun open(url: String, token: String? = null): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", USER_AGENT)
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }

private fun HttpURLConnection.body(): String =
    (if (responseCode < 400) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""

data class Puzzle(val title: String, val url: String, val fen: String, val pgn: String)

/** chess.com's public, read-only puzzle endpoints. */
object ChessCom {
    fun daily(): Puzzle = fetch("https://api.chess.com/pub/puzzle")
    fun random(): Puzzle = fetch("https://api.chess.com/pub/puzzle/random")

    private fun fetch(url: String): Puzzle {
        val c = open(url)
        val text = c.body()
        if (c.responseCode != 200) throw Exception("chess.com said HTTP ${c.responseCode}")
        val j = JSONObject(text)
        return Puzzle(j.optString("title"), j.optString("url"), j.getString("fen"), j.optString("pgn"))
    }
}

class LichessException(message: String) : Exception(message)

/**
 * One game against the Lichess AI (Stockfish levels 1-8) over the Board API.
 * Needs a personal token with the board:play scope.
 */
class LichessGame(token: String, level: Int) : Closeable {
    private val token = token.trim()
    val level = level.coerceIn(1, 8)
    var username: String? = null
        private set
    private var gameId: String? = null
    private var stream: HttpURLConnection? = null
    private var lines: BufferedReader? = null
    private var movesSeen = emptyList<String>()

    init {
        if (this.token.isEmpty()) throw LichessException("A Lichess API token is needed.")
    }

    private fun nextEvent(): JSONObject? {
        val r = lines ?: return null
        while (true) {
            val line = r.readLine() ?: return null
            if (line.isBlank()) continue // keep-alive
            return runCatching { JSONObject(line) }.getOrNull() ?: continue
        }
    }

    private fun movesOf(e: JSONObject): List<String>? {
        val s = when (e.optString("type")) {
            "gameFull" -> e.optJSONObject("state")?.optString("moves")
            "gameState" -> e.optString("moves")
            else -> null
        } ?: return null
        return s.split(" ").filter { it.isNotEmpty() }
    }

    private fun statusOf(e: JSONObject): String? = when (e.optString("type")) {
        "gameFull" -> e.optJSONObject("state")?.optString("status")
        else -> e.optString("status").ifEmpty { null }
    }

    /** Starts the game; returns the AI's first move if it plays White. */
    fun start(humanWhite: Boolean): String? {
        val acct = open("https://lichess.org/api/account", token)
        val acctBody = acct.body()
        when (acct.responseCode) {
            200 -> username = JSONObject(acctBody).optString("username")
            401 -> throw LichessException("Lichess rejected the token (it needs the board:play scope).")
            else -> throw LichessException("Lichess account check failed: HTTP ${acct.responseCode}")
        }

        val ch = open("https://lichess.org/api/challenge/ai", token).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val form = "level=$level&color=" + URLEncoder.encode(if (humanWhite) "white" else "black", "UTF-8")
        ch.outputStream.use { it.write(form.toByteArray()) }
        val chBody = ch.body()
        if (ch.responseCode !in 200..201) throw LichessException("Couldn't start the AI game: HTTP ${ch.responseCode} $chBody")
        gameId = JSONObject(chBody).getString("id")

        val s = open("https://lichess.org/api/board/game/stream/$gameId", token).apply { readTimeout = 0 }
        if (s.responseCode != 200) throw LichessException("Couldn't open the game stream: HTTP ${s.responseCode}")
        stream = s
        lines = s.inputStream.bufferedReader()

        val first = nextEvent() ?: throw LichessException("The game stream closed straight away.")
        movesSeen = movesOf(first) ?: emptyList()
        if (!humanWhite && movesSeen.isEmpty()) {
            while (true) {
                val e = nextEvent() ?: return null
                val mv = movesOf(e) ?: continue
                if (mv.isNotEmpty()) {
                    movesSeen = mv
                    return mv.last()
                }
            }
        }
        return movesSeen.lastOrNull()
    }

    /** Sends our move; returns the AI's reply, or null if the game ended. */
    fun reply(uci: String): String? {
        val id = gameId ?: throw LichessException("No Lichess game running.")
        val c = open("https://lichess.org/api/board/game/$id/move/$uci", token).apply {
            requestMethod = "POST"
            doOutput = true
        }
        c.outputStream.use { }
        val body = c.body()
        if (c.responseCode != 200) throw LichessException("Lichess rejected $uci: $body")
        while (true) {
            val e = nextEvent() ?: return null
            val moves = movesOf(e)
            if (moves != null && moves.size > movesSeen.size) {
                movesSeen = moves
                if (moves.last() != uci) return moves.last()
            }
            val status = statusOf(e)
            if (status != null && status !in setOf("started", "created")) return null
        }
    }

    override fun close() {
        runCatching { lines?.close() }
        runCatching { stream?.disconnect() }
        lines = null
        stream = null
    }
}
