package com.heymeowcat.sounddrift

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

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
}

class AudioStreamer(private val activity: ComponentActivity) {
    private var isStreaming = false
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var mediaProjectionService: MediaProjectionService? = null
    private var _connectionStatus = mutableStateOf("")
    val connectionStatus = _connectionStatus as State<String>

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaProjectionService.LocalBinder
            mediaProjectionService = binder.getService()
            mediaProjectionService?.setAudioDataCallback(object : MediaProjectionService.AudioDataCallback {
                override fun onAudioDataAvailable(data: ByteArray, size: Int) {
                    try {
                        clientSocket?.getOutputStream()?.write(data, 0, size)
                        clientSocket?.getOutputStream()?.flush()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        stopStreaming()
                    }
                }
            })
            mediaProjectionService?.startAudioRecording()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaProjectionService = null
            _connectionStatus.value = ""
        }
    }

    fun startProjection(resultCode: Int, data: Intent) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Start server socket
                serverSocket = ServerSocket(12345)
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Waiting for client connection..."
                }

                // Wait for client connection
                clientSocket = serverSocket?.accept()

                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Client connected: ${clientSocket?.inetAddress?.hostAddress}"
                }

                // Start and bind to service
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

                isStreaming = true
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _connectionStatus.value = "Connection error: ${e.message}"
                    isStreaming = false
                }
            }
        }
    }

    fun stopStreaming() {
        isStreaming = false
        _connectionStatus.value = ""

        try {
            activity.unbindService(serviceConnection)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        activity.stopService(Intent(activity, MediaProjectionService::class.java))

        CoroutineScope(Dispatchers.IO).launch {
            try {
                clientSocket?.close()
                serverSocket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    audioStreamer: AudioStreamer
) {
    val context = LocalContext.current
    var isStreaming by remember { mutableStateOf(false) }
    val connectionStatus by audioStreamer.connectionStatus

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Audio permission granted
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ComponentActivity.RESULT_OK) {
            result.data?.let { data ->
                audioStreamer.startProjection(result.resultCode, data)
                isStreaming = true
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(
            onClick = {
                if (!isStreaming) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    audioStreamer.stopStreaming()
                    isStreaming = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
        }

        if (isStreaming) {
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
