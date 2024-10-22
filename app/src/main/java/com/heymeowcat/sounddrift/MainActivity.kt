package com.heymeowcat.sounddrift

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import com.heymeowcat.sounddrift.ui.theme.SoundDriftTheme

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

class AudioStreamer(private val activity: Activity) {
    private val mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var isStreaming = false

    fun startProjection(resultCode: Int, data: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        isStreaming = true
        // Start streaming implementation
    }

    fun stopStreaming() {
        isStreaming = false
        mediaProjection?.stop()
        // Stop streaming implementation
    }

    fun isStreaming() = isStreaming
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

    val launcher = rememberLauncherForActivityResult(
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
        OutlinedTextField(
            value = serverIp,
            onValueChange = { serverIp = it },
            label = { Text("Mac Server IP Address") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (!isStreaming) {
                    launcher.launch(mediaProjectionManager.createScreenCaptureIntent())
                } else {
                    audioStreamer.stopStreaming()
                    isStreaming = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
        }

        // Status indicator
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