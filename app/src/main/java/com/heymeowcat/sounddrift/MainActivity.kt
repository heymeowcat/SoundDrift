package com.heymeowcat.sounddrift

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.heymeowcat.sounddrift.ui.theme.SoundDriftTheme
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private lateinit var audioStreamer: AudioStreamer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioStreamer = AudioStreamer(this)

        setContent {
            SoundDriftTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        audioStreamer = audioStreamer
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioStreamer.stopStreaming()
    }
}

class AudioStreamer(private val activity: ComponentActivity) {
    private var mediaProjectionService: MediaProjectionService? = null
    private var _connectionStatus = mutableStateOf("")
    val connectionStatus = _connectionStatus as State<String>
    private var updateJob: Job? = null

    private var _isMicEnabled = mutableStateOf(false)
    private var _isDeviceAudioEnabled = mutableStateOf(false)
    private var _isStreaming = mutableStateOf(false)
    val isMicEnabled = _isMicEnabled as State<Boolean>
    val isDeviceAudioEnabled = _isDeviceAudioEnabled as State<Boolean>
    val isStreaming = _isStreaming as State<Boolean>

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaProjectionService.LocalBinder
            mediaProjectionService = binder.getService()

            // Configure the service with the current settings
            mediaProjectionService?.apply {
                setMicEnabled(_isMicEnabled.value)
                setDeviceAudioEnabled(_isDeviceAudioEnabled.value)
            }

            _isStreaming.value = true
            startConnectionStatusUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaProjectionService = null
            updateJob?.cancel()
            _connectionStatus.value = ""
            _isStreaming.value = false
        }
    }

    private fun startConnectionStatusUpdates() {
        updateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val clientIP = mediaProjectionService?.getConnectedClientIP() ?: ""
                _connectionStatus.value = if (clientIP.isNotEmpty()) {
                    "Streaming active ($clientIP connected)"
                } else {
                    "Streaming active (waiting for connection...)"
                }
                delay(1000) // Update every second
            }
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        _isMicEnabled.value = enabled
        mediaProjectionService?.setMicEnabled(enabled)
    }

    fun setDeviceAudioEnabled(enabled: Boolean) {
        _isDeviceAudioEnabled.value = enabled
        mediaProjectionService?.setDeviceAudioEnabled(enabled)
    }

    fun startProjection(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(activity, MediaProjectionService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }

        activity.startForegroundService(serviceIntent)
        activity.bindService(
            Intent(activity, MediaProjectionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun stopStreaming() {
        if (_isStreaming.value) {
            try {
                activity.unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            activity.stopService(Intent(activity, MediaProjectionService::class.java))
            updateJob?.cancel()
            _isStreaming.value = false
            _connectionStatus.value = ""
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    audioStreamer: AudioStreamer
) {
    val context = LocalContext.current
    val connectionStatus by audioStreamer.connectionStatus
    val isMicEnabled by audioStreamer.isMicEnabled
    val isDeviceAudioEnabled by audioStreamer.isDeviceAudioEnabled
    val isStreaming by audioStreamer.isStreaming

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ComponentActivity.RESULT_OK) {
            result.data?.let { data ->
                audioStreamer.startProjection(result.resultCode, data)
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted && (isMicEnabled || isDeviceAudioEnabled)) {
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Microphone")
                    Switch(
                        checked = isMicEnabled,
                        onCheckedChange = { audioStreamer.setMicEnabled(it) },
                        enabled = !isStreaming
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Audio")
                    Switch(
                        checked = isDeviceAudioEnabled,
                        onCheckedChange = { audioStreamer.setDeviceAudioEnabled(it) },
                        enabled = !isStreaming
                    )
                }

                Button(
                    onClick = {
                        if (!isStreaming) {
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            audioStreamer.stopStreaming()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isMicEnabled || isDeviceAudioEnabled
                ) {
                    Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
                }
            }
        }

        if (connectionStatus.isNotEmpty()) {
            Text(
                connectionStatus,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    SoundDriftTheme {
        MainScreen(audioStreamer = AudioStreamer(ComponentActivity()))
    }
}