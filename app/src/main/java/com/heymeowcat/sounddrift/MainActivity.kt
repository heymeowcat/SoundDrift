package com.heymeowcat.sounddrift

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.heymeowcat.sounddrift.ui.theme.SoundDriftTheme
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.Socket

class MainActivity : ComponentActivity() {
    private lateinit var audioStreamer: AudioStreamer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioStreamer = AudioStreamer(this)

        enableEdgeToEdge()
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
    private lateinit var socket: Socket
    private var outputStream: OutputStream? = null
    private var recordingJob: Job? = null

    fun startProjection(resultCode: Int, data: Intent, serverIp: String) {
        // Start the foreground service
        val serviceIntent = Intent(activity, MediaProjectionService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
            putExtra("serverIp", serverIp)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(serviceIntent)
        } else {
            activity.startService(serviceIntent)
        }

        isStreaming = true
    }

    fun stopStreaming() {
        isStreaming = false

        // Stop the foreground service
        val stopIntent = Intent(activity, MediaProjectionService::class.java).apply {
            action = "STOP_STREAMING"
        }
        activity.stopService(stopIntent)

        // Stop audio recording in the streamer
        stopAudioRecording()
    }


    fun startAudioRecording(serverIp: String) {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = Socket(serverIp, 12345)
                outputStream = socket.getOutputStream()

                // Access and start recording from the MediaProjectionService
                val audioData = ByteArray(1024) // Adjust buffer size as needed
                while (isStreaming) {
                    // ... (Logic to get audio data from MediaProjectionService)
                    outputStream?.write(audioData)
                    outputStream?.flush()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopAudioRecording()
            }
        }
    }

    private fun stopAudioRecording() {
        outputStream?.close()
        if (::socket.isInitialized && !socket.isClosed) {
            socket.close()
        }
        recordingJob?.cancel()
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    audioStreamer: AudioStreamer
) {
    val context = LocalContext.current
    var isStreaming by remember { mutableStateOf(false) }
    var serverIp by remember { mutableStateOf("") }

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Audio permission granted
        } else {
            // Handle permission denial
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ComponentActivity.RESULT_OK) {
            result.data?.let { data ->
                audioStreamer.startProjection(result.resultCode, data, serverIp)
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
        OutlinedTextField(
            value = serverIp,
            onValueChange = { serverIp = it },
            label = { Text("Mac Server IP Address") },
            modifier = Modifier.fillMaxWidth()
        )

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
                "Streaming to: $serverIp",
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
