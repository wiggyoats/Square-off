package com.wiggyoats.squareoff.game

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import com.wiggyoats.squareoff.ble.BleLink
import com.wiggyoats.squareoff.ble.isSquareOff
import com.wiggyoats.squareoff.board.SquareOffBoard
import com.wiggyoats.squareoff.engine.Bot
import com.wiggyoats.squareoff.engine.Level
import com.wiggyoats.squareoff.online.ChessCom
import com.wiggyoats.squareoff.online.LichessGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SquareOffGame"

enum class Link { OFF, SEARCHING, CONNECTING, CONNECTED, RECALIBRATING }
enum class Mode { BOT, LICHESS, PUZZLE }

data class UiState(
    val link: Link = Link.OFF,
    val boardName: String? = null,
    val flipped: Boolean = false,
    val mode: Mode = Mode.BOT,
    val active: Boolean = false,
    val moving: Boolean = false,
    val thinking: Boolean = false,
    val fen: String = Board().fen,
    val humanWhite: Boolean = true,
    val yourTurn: Boolean = false,
    val lastMove: Pair<String, String>? = null,
    val inCheck: Boolean = false,
    val result: String? = null,
    val puzzleTitle: String? = null,
    val puzzleProgress: String? = null,
    val messages: List<String> = emptyList(),
    val calibrationWarning: Boolean = false,
    val level: Level = Level.EASY,
    val lichessLevel: Int = 4,
    val lichessToken: String = "",
)

/**
 * Owns the board connection and the game. Every game change runs under [lock],
 * so a bot reply, a board event and a button press never interleave.
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("squareoff", Context.MODE_PRIVATE)
    private val link = BleLink(app)
    private val hw = SquareOffBoard(link)
    private val lock = Mutex()

    private var board = Board()
    private var grave = Graveyard()
    private val lifted = mutableListOf<String>()
    private var bot: Bot? = null
    private var lichess: LichessGame? = null
    private var solution = emptyList<Move>()
    private var solutionIdx = 0
    private var randomPuzzles = false
    private var address: String? = prefs.getString("address", null)
    private var recalJob: Job? = null

    private val _ui = MutableStateFlow(
        UiState(
            flipped = prefs.getBoolean("flipped", false),
            level = runCatching { Level.valueOf(prefs.getString("level", "EASY")!!) }.getOrDefault(Level.EASY),
            lichessLevel = prefs.getInt("lichessLevel", 4),
            lichessToken = prefs.getString("lichessToken", "") ?: "",
        )
    )
    val ui: StateFlow<UiState> = _ui

    init {
        hw.flipped = _ui.value.flipped
        hw.onStatus = { text -> viewModelScope.launch { onBoardStatus(text) } }
        hw.onCalibrationError = { _ui.update { it.copy(calibrationWarning = true) } }
        viewModelScope.launch {
            link.connected.collect { up ->
                if (!up && _ui.value.link == Link.CONNECTED) {
                    _ui.update { it.copy(link = Link.OFF) }
                    say("Board disconnected.")
                }
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun say(msg: String) {
        Log.i(TAG, msg)
        _ui.update { it.copy(messages = (it.messages + msg).takeLast(30)) }
    }

    private val human get() = if (_ui.value.humanWhite) Side.WHITE else Side.BLACK

    private fun publish() {
        val over = isGameOver(board)
        _ui.update {
            it.copy(
                fen = board.fen,
                yourTurn = it.active && board.sideToMove == human,
                inCheck = board.isKingAttacked,
                result = if (over) resultText(board) else null,
                puzzleProgress = if (it.mode == Mode.PUZZLE && solution.isNotEmpty()) "$solutionIdx/${solution.size}" else null,
            )
        }
    }

    private fun closeOpponents() {
        bot = null
        lichess?.let { l -> viewModelScope.launch(Dispatchers.IO) { l.close() } }
        lichess = null
    }

    // --- connection ---------------------------------------------------------

    val bluetoothOn get() = link.bluetoothOn

    fun connect() {
        if (_ui.value.link != Link.OFF) return
        viewModelScope.launch {
            try {
                val known = address
                if (known != null) {
                    _ui.update { it.copy(link = Link.CONNECTING) }
                    if (runCatching { open(known) }.isSuccess) return@launch
                }
                _ui.update { it.copy(link = Link.SEARCHING) }
                say("Looking for the board…")
                val found = withTimeoutOrNull(12_000) { link.scan().first { isSquareOff(it.name) } }
                if (found == null) {
                    _ui.update { it.copy(link = Link.OFF) }
                    say("No Square Off board found. Is it switched on and close by?")
                    return@launch
                }
                _ui.update { it.copy(link = Link.CONNECTING, boardName = found.name) }
                open(found.address)
                prefs.edit().putString("boardName", found.name).apply()
            } catch (e: Exception) {
                _ui.update { it.copy(link = Link.OFF) }
                say("Couldn't connect: ${e.message}")
            }
        }
    }

    private suspend fun open(addr: String) {
        link.connect(addr)
        hw.start()
        runCatching { hw.handshake() }.onFailure { say("Handshake warning: ${it.message}") }
        address = addr
        prefs.edit().putString("address", addr).apply()
        _ui.update {
            it.copy(link = Link.CONNECTED, boardName = it.boardName ?: prefs.getString("boardName", "Square Off"))
        }
        say("Connected to ${_ui.value.boardName}.")
    }

    fun disconnect() {
        recalJob?.cancel()
        link.disconnect()
        _ui.update { it.copy(link = Link.OFF) }
    }

    /**
     * The SWAP only re-homes its motor while powering up and has no remote
     * command for it, so: wait for the player to switch it off (the link drops),
     * then keep reconnecting until it's back on.
     */
    fun recalibrate() {
        val addr = address ?: return
        if (_ui.value.link != Link.CONNECTED) return
        _ui.update { it.copy(link = Link.RECALIBRATING) }
        say("Switch the board off, then on again. It homes while starting up.")
        recalJob = viewModelScope.launch {
            link.connected.first { !it }
            say("Board is off. Waiting for it to come back…")
            delay(3000)
            while (isActive) {
                try {
                    open(addr)
                    _ui.update { it.copy(calibrationWarning = false) }
                    say("Board back and recalibrated.")
                    return@launch
                } catch (e: Exception) {
                    Log.i(TAG, "not back yet: ${e.message}")
                    delay(3000)
                }
            }
        }
    }

    fun cancelRecalibrate() {
        recalJob?.cancel()
        _ui.update { it.copy(link = if (link.connected.value) Link.CONNECTED else Link.OFF) }
    }

    fun setFlipped(value: Boolean) {
        hw.flipped = value
        prefs.edit().putBoolean("flipped", value).apply()
        _ui.update { it.copy(flipped = value) }
    }

    fun setLevel(level: Level) {
        prefs.edit().putString("level", level.name).apply()
        _ui.update { it.copy(level = level) }
    }

    fun setLichess(token: String, level: Int) {
        prefs.edit().putString("lichessToken", token.trim()).putInt("lichessLevel", level).apply()
        _ui.update { it.copy(lichessToken = token.trim(), lichessLevel = level) }
    }

    // --- motor --------------------------------------------------------------

    /** Runs motor jobs (or just collects notes when no board is connected). */
    private suspend fun execute(actions: List<Action>): List<String> {
        val notes = actions.filterIsInstance<Action.Note>().map { it.text }
        if (_ui.value.link != Link.CONNECTED || actions.all { it is Action.Note }) return notes
        _ui.update { it.copy(moving = true) }
        try {
            hw.handshake() // it ignores paths after going idle
            for (a in actions) when (a) {
                is Action.Slide -> hw.move(a.from, a.to)
                is Action.Route -> hw.route(a.from, a.to)
                is Action.Capture -> hw.capture(a.from, a.to, a.slot)
                is Action.Park -> hw.park(a.square, a.slot)
                is Action.Retrieve -> hw.retrieve(a.slot, a.square)
                is Action.Path -> hw.sendPath(a.points)
                Action.Beep -> hw.beep()
                is Action.Note -> {}
            }
        } finally {
            _ui.update { it.copy(moving = false) }
        }
        return notes
    }

    /** Like [execute] but reports failure instead of throwing. */
    private suspend fun tryExecute(actions: List<Action>): Pair<Boolean, List<String>> = try {
        true to execute(actions)
    } catch (e: Exception) {
        say("Board problem: ${e.message}")
        false to emptyList()
    }

    // --- games --------------------------------------------------------------

    private fun resetLogical(mode: Mode, humanWhite: Boolean, fen: String? = null) {
        closeOpponents()
        board = Board().apply { if (fen != null) loadFromFen(fen) }
        grave = Graveyard()
        lifted.clear()
        solution = emptyList()
        solutionIdx = 0
        _ui.update {
            it.copy(mode = mode, humanWhite = humanWhite, active = false, lastMove = null, messages = emptyList(),
                puzzleTitle = null)
        }
    }

    fun newBotGame(humanWhite: Boolean) = viewModelScope.launch {
        lock.withLock {
            resetLogical(Mode.BOT, humanWhite)
            val level = _ui.value.level
            bot = Bot(level)
            _ui.update { it.copy(active = true) }
            say("New game: you're ${if (humanWhite) "white" else "black"}, bot ${level.label}.")
            publish()
            if (board.sideToMove != human) opponentReply(null)
            publish()
        }
    }

    fun newLichessGame(humanWhite: Boolean) = viewModelScope.launch {
        lock.withLock {
            resetLogical(Mode.LICHESS, humanWhite)
            val st = _ui.value
            _ui.update { it.copy(thinking = true) }
            try {
                val game = LichessGame(st.lichessToken, st.lichessLevel)
                lichess = game
                val first = withContext(Dispatchers.IO) { game.start(humanWhite) }
                _ui.update { it.copy(active = true) }
                say("Lichess game vs AI level ${game.level}, you're ${if (humanWhite) "white" else "black"} (as ${game.username}).")
                if (first != null) parseMove(board, first)?.let { applyOpponentMove(it) }
            } catch (e: Exception) {
                say("Lichess: ${e.message}")
            } finally {
                _ui.update { it.copy(thinking = false) }
            }
            publish()
        }
    }

    /** A move from the screen. */
    fun submit(text: String) = viewModelScope.launch { lock.withLock { submitLocked(text) } }

    private suspend fun submitLocked(text: String) {
        val st = _ui.value
        if (!st.active) return say("No game running. Start one first.")
        val m = parseMove(board, text) ?: return say("Illegal move: $text")
        if (st.mode == Mode.PUZZLE) puzzleMove(m) else gameMove(m)
        publish()
    }

    private suspend fun gameMove(m: Move) {
        if (board.sideToMove != human) return say("It's not your turn.")
        say("You: ${san(board, m)}")
        recordHandCapture(m)
        board.doMove(m)
        lifted.clear()
        _ui.update { it.copy(lastMove = m.from.n to m.to.n) }
        if (isGameOver(board)) return finish()
        opponentReply(m.uci())
    }

    private fun finish() {
        _ui.update { it.copy(active = false) }
        say("Game over: ${resultText(board)}.")
    }

    /** You took a piece by hand: assume it went in its labelled slot. */
    private fun recordHandCapture(m: Move) {
        val victim = capturedPiece(board, m) ?: return
        grave.alloc(victim)
    }

    private suspend fun opponentReply(lastUci: String?) {
        _ui.update { it.copy(thinking = true) }
        val move: Move? = try {
            when (_ui.value.mode) {
                Mode.LICHESS -> {
                    val g = lichess ?: return
                    val uci = withContext(Dispatchers.IO) { g.reply(lastUci ?: return@withContext null) }
                    uci?.let { parseMove(board, it) }
                }
                else -> {
                    val b = bot ?: return
                    val snapshot = board.clone()
                    withContext(Dispatchers.Default) { b.selectMove(snapshot) }
                }
            }
        } catch (e: Exception) {
            say("Opponent error: ${e.message}")
            null
        } finally {
            _ui.update { it.copy(thinking = false) }
        }
        if (move == null) return finish()
        applyOpponentMove(move)
    }

    private suspend fun applyOpponentMove(m: Move) {
        val actions = physicalActions(board, m, grave)
        val text = san(board, m)
        val (_, notes) = tryExecute(actions)
        board.doMove(m)
        _ui.update { it.copy(lastMove = m.from.n to m.to.n) }
        say("Opponent: $text")
        notes.forEach(::say)
        publish()
        if (isGameOver(board)) finish()
    }

    // --- puzzles ------------------------------------------------------------

    fun loadPuzzle(random: Boolean) = viewModelScope.launch { lock.withLock { loadPuzzleLocked(random) } }

    private suspend fun loadPuzzleLocked(random: Boolean) {
        _ui.update { it.copy(thinking = true) }
        val p = try {
            withContext(Dispatchers.IO) { if (random) ChessCom.random() else ChessCom.daily() }
        } catch (e: Exception) {
            say("Couldn't fetch the puzzle: ${e.message}")
            return
        } finally {
            _ui.update { it.copy(thinking = false) }
        }
        val physical = board.clone()
        val physicalGrave = grave
        val sol = parseSolution(p.fen, p.pgn)
        val target = Board().apply { loadFromFen(p.fen) }
        resetLogical(Mode.PUZZLE, target.sideToMove == Side.WHITE, p.fen)
        grave = physicalGrave
        solution = sol
        randomPuzzles = random
        _ui.update { it.copy(active = true, puzzleTitle = p.title) }
        say("${if (random) "Random" else "Daily"} puzzle: ${p.title}. ${sideName(board.sideToMove).replaceFirstChar { it.uppercase() }} to move.")
        publish()

        val (actions, notes) = planArrange(physical, target, grave)
        val (ok, execNotes) = tryExecute(actions)
        when {
            _ui.value.link != Link.CONNECTED -> say("Not connected, so this is on screen only.")
            !ok -> say("Setup stopped part-way. The board may not match the puzzle.")
            else -> say("Position set up. Make the best move.")
        }
        (notes + execNotes).forEach(::say)
        if (sol.isEmpty()) say("No solution came with this puzzle, so moves won't be checked.")
    }

    private suspend fun puzzleMove(m: Move) {
        val expected = solution.getOrNull(solutionIdx)
        if (expected != null && m != expected) {
            say("✗ ${san(board, m)} isn't it. Try again.")
            if (isCapture(board, m)) say("Put the captured piece back on ${m.to.n} by hand.")
            tryExecute(listOf(Action.Beep, Action.Route(m.to.n, m.from.n)))
            lifted.clear()
            return
        }
        say("✓ You: ${san(board, m)}")
        recordHandCapture(m)
        board.doMove(m)
        solutionIdx++
        lifted.clear()
        _ui.update { it.copy(lastMove = m.from.n to m.to.n) }
        publish()
        if (isGameOver(board) || solutionIdx >= solution.size) return puzzleSolved()
        applyOpponentMove(solution[solutionIdx])
        solutionIdx++
        if (isGameOver(board) || solutionIdx >= solution.size) puzzleSolved()
    }

    private suspend fun puzzleSolved() {
        say("Puzzle solved! 🎉")
        _ui.update { it.copy(active = false) }
        resetLocked()
        if (randomPuzzles) {
            say("Loading another random puzzle…")
            loadPuzzleLocked(true)
        }
    }

    // --- reset --------------------------------------------------------------

    fun resetBoard() = viewModelScope.launch { lock.withLock { resetLocked() } }

    private suspend fun resetLocked() {
        val (actions, notes) = planArrange(board, Board(), grave)
        val (ok, execNotes) = tryExecute(actions)
        val keep = _ui.value.messages
        resetLogical(Mode.BOT, _ui.value.humanWhite)
        _ui.update { it.copy(messages = keep) }
        say(if (ok) "Board reset to the starting position." else "Reset stopped part-way. Finish setting up by hand.")
        (notes + execNotes).forEach(::say)
        publish()
    }

    // --- moves made on the physical board ------------------------------------

    /**
     * The firmware reports your hand moves as lifts/drops ("e2u", "e4d"); the
     * motor's own moves only report "OK". A drop on a square that a lifted piece
     * can legally reach is your move.
     */
    private suspend fun onBoardStatus(text: String) {
        if (text.length < 3 || text.last() !in "ud") return
        val st = _ui.value
        if (!st.active || st.moving || st.thinking || board.sideToMove != human) return
        val square = text.dropLast(1)
        if (text.last() == 'u') {
            if (square !in lifted) lifted += square
            return
        }
        val candidates = legalMoves(board).filter { it.to.n == square && it.from.n in lifted }
        if (candidates.isEmpty()) {
            lifted -= square
            return
        }
        val m = candidates.firstOrNull { it.promotion == Piece.WHITE_QUEEN || it.promotion == Piece.BLACK_QUEEN }
            ?: candidates.first()
        lifted.clear()
        lock.withLock { submitLocked(m.uci()) }
    }

    override fun onCleared() {
        recalJob?.cancel()
        closeOpponents()
        link.disconnect()
    }
}
