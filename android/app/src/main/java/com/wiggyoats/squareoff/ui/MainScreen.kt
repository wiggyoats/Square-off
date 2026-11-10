package com.wiggyoats.squareoff.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wiggyoats.squareoff.engine.Level
import com.wiggyoats.squareoff.game.GameViewModel
import com.wiggyoats.squareoff.game.Link
import com.wiggyoats.squareoff.game.Mode
import com.wiggyoats.squareoff.game.UiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: GameViewModel, onConnect: () -> Unit) {
    val s by vm.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Square Off") }) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(s, onConnect, vm)
            if (s.calibrationWarning && s.link == Link.CONNECTED) CalibrationCard(vm)
            BoardView(
                fen = s.fen,
                whiteAtBottom = s.humanWhite,
                lastMove = s.lastMove,
                enabled = s.yourTurn && !s.moving && !s.thinking,
                onMove = { vm.submit(it) },
            )
            StatusLine(s)
            GamePanel(s, vm)
            OutlinedButton(
                onClick = { vm.resetBoard() },
                enabled = !s.moving && !s.thinking,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset board to the start") }
            MessageLog(s.messages)
        }
    }
}

@Composable
private fun ConnectionCard(s: UiState, onConnect: () -> Unit, vm: GameViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = when (s.link) {
                    Link.OFF -> "Board not connected"
                    Link.SEARCHING -> "Looking for the board…"
                    Link.CONNECTING -> "Connecting…"
                    Link.CONNECTED -> "Connected: ${s.boardName ?: "Square Off"}"
                    Link.RECALIBRATING -> "Recalibrating: switch the board off and on"
                }
                if (s.link == Link.SEARCHING || s.link == Link.CONNECTING || s.link == Link.RECALIBRATING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(label, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (s.link) {
                    Link.OFF -> Button(onClick = onConnect) { Text("Connect") }
                    Link.CONNECTED -> {
                        OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
                        OutlinedButton(onClick = { vm.recalibrate() }, enabled = !s.moving) { Text("Recalibrate") }
                    }
                    Link.RECALIBRATING -> OutlinedButton(onClick = { vm.cancelRecalibrate() }) { Text("Cancel") }
                    else -> OutlinedButton(onClick = { vm.disconnect() }) { Text("Stop") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Board turned round", Modifier.weight(1f))
                Switch(checked = s.flipped, onCheckedChange = { vm.setFlipped(it) }, enabled = !s.moving)
            }
        }
    }
}

@Composable
private fun CalibrationCard(vm: GameViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The board says its motor is out of line. Recalibrate it: switch it off and on again.")
            Button(onClick = { vm.recalibrate() }) { Text("Recalibrate") }
        }
    }
}

@Composable
private fun StatusLine(s: UiState) {
    val text = when {
        s.moving -> "Board is moving pieces…"
        s.thinking -> if (s.mode == Mode.PUZZLE) "Fetching puzzle…" else "Opponent is thinking…"
        s.result != null -> "Game over: ${s.result}"
        s.yourTurn && s.inCheck -> "Check! Your move."
        s.yourTurn -> if (s.mode == Mode.PUZZLE) "Your move: find the best one." else "Your move."
        s.active -> "Opponent to move."
        else -> "Start a game or a puzzle below."
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.moving || s.thinking) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
        s.puzzleProgress?.let {
            Spacer(Modifier.weight(1f))
            Text("Step $it", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GamePanel(s: UiState, vm: GameViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var playWhite by rememberSaveable { mutableStateOf(true) }
    val idle = !s.moving && !s.thinking
    Card(Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = tab) {
            listOf("Play bot", "Lichess", "Puzzles").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tab != 2) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true, false).forEachIndexed { i, white ->
                        SegmentedButton(
                            selected = playWhite == white,
                            onClick = { playWhite = white },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(if (white) "I play white" else "I play black") }
                    }
                }
            }
            when (tab) {
                0 -> {
                    Text("Bot strength", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Level.entries.take(3).forEach { LevelChip(it, s.level, vm) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Level.entries.drop(3).forEach { LevelChip(it, s.level, vm) }
                    }
                    Button(onClick = { vm.newBotGame(playWhite) }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                        Text("Start new game")
                    }
                }
                1 -> LichessControls(s, vm, playWhite, idle)
                else -> {
                    Text("Puzzles from chess.com. Reset the board first so all the pieces are in the starting position.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.loadPuzzle(false) }, enabled = idle) { Text("Daily puzzle") }
                        Button(onClick = { vm.loadPuzzle(true) }, enabled = idle) { Text("Random puzzles") }
                    }
                    s.puzzleTitle?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(level: Level, current: Level, vm: GameViewModel) {
    FilterChip(selected = level == current, onClick = { vm.setLevel(level) }, label = { Text(level.label) })
}

@Composable
private fun LichessControls(s: UiState, vm: GameViewModel, playWhite: Boolean, idle: Boolean) {
    var token by remember(s.lichessToken) { mutableStateOf(s.lichessToken) }
    var level by remember(s.lichessLevel) { mutableStateOf(s.lichessLevel.toFloat()) }
    val uri = LocalUriHandler.current
    Text("Play the Lichess computer online. You need a free Lichess account and a token with the \"Play games with the board API\" permission.")
    TextButton(onClick = {
        uri.openUri("https://lichess.org/account/oauth/token/create?scopes[]=board:play&description=Square+Off+board")
    }) { Text("Make a token on lichess.org") }
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("Lichess token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Computer level ${level.roundToInt()} of 8", style = MaterialTheme.typography.labelLarge)
    Slider(value = level, onValueChange = { level = it }, valueRange = 1f..8f, steps = 6)
    Button(
        onClick = {
            vm.setLichess(token, level.roundToInt())
            vm.newLichessGame(playWhite)
        },
        enabled = idle && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Start Lichess game") }
}

@Composable
private fun MessageLog(messages: List<String>) {
    if (messages.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            messages.asReversed().take(14).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
