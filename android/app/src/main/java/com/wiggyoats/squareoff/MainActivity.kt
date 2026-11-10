package com.wiggyoats.squareoff

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.wiggyoats.squareoff.game.GameViewModel
import com.wiggyoats.squareoff.ui.AppTheme
import com.wiggyoats.squareoff.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()
    private val hasPermissions = mutableStateOf(false)

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            hasPermissions.value = granted.values.all { it }
            if (hasPermissions.value) connectWhenReady()
        }

    private val askBluetoothOn =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (vm.bluetoothOn) vm.connect()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Board moves take a while; don't let the screen sleep mid-game.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hasPermissions.value = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        setContent {
            AppTheme {
                MainScreen(vm = vm, onConnect = ::connectWhenReady)
            }
        }
    }

    private fun connectWhenReady() {
        when {
            !hasPermissions.value -> askPermissions.launch(permissions)
            !vm.bluetoothOn -> askBluetoothOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> vm.connect()
        }
    }
}
