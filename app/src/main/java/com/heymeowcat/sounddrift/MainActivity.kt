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

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

public val Volume_up: ImageVector
    get() {
        if (_Volume_up != null) {
            return _Volume_up!!
        }
        _Volume_up = ImageVector.Builder(
            name = "Volume_up",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 1.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 1.0f,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(560f, 829f)
                verticalLineToRelative(-82f)
                quadToRelative(90f, -26f, 145f, -100f)
                reflectiveQuadToRelative(55f, -168f)
                reflectiveQuadToRelative(-55f, -168f)
                reflectiveQuadToRelative(-145f, -100f)
                verticalLineToRelative(-82f)
                quadToRelative(124f, 28f, 202f, 125.5f)
                reflectiveQuadTo(840f, 479f)
                reflectiveQuadToRelative(-78f, 224.5f)
                reflectiveQuadTo(560f, 829f)
                moveTo(120f, 600f)
                verticalLineToRelative(-240f)
                horizontalLineToRelative(160f)
                lineToRelative(200f, -200f)
                verticalLineToRelative(640f)
                lineTo(280f, 600f)
                close()
                moveToRelative(440f, 40f)
                verticalLineToRelative(-322f)
                quadToRelative(47f, 22f, 73.5f, 66f)
                reflectiveQuadToRelative(26.5f, 96f)
                quadToRelative(0f, 51f, -26.5f, 94.5f)
                reflectiveQuadTo(560f, 640f)
                moveTo(400f, 354f)
                lineToRelative(-86f, 86f)
                horizontalLineTo(200f)
                verticalLineToRelative(80f)
                horizontalLineToRelative(114f)
                lineToRelative(86f, 86f)
                close()
                moveTo(300f, 480f)
            }
        }.build()
        return _Volume_up!!
    }

private var _Volume_up: ImageVector? = null


public val Volume_mute: ImageVector
    get() {
        if (_Volume_mute != null) {
            return _Volume_mute!!
        }
        _Volume_mute = ImageVector.Builder(
            name = "Volume_mute",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 1.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 1.0f,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(280f, 600f)
                verticalLineToRelative(-240f)
                horizontalLineToRelative(160f)
                lineToRelative(200f, -200f)
                verticalLineToRelative(640f)
                lineTo(440f, 600f)
                close()
                moveToRelative(80f, -80f)
                horizontalLineToRelative(114f)
                lineToRelative(86f, 86f)
                verticalLineToRelative(-252f)
                lineToRelative(-86f, 86f)
                horizontalLineTo(360f)
                close()
                moveToRelative(100f, -40f)
            }
        }.build()
        return _Volume_mute!!
    }

private var _Volume_mute: ImageVector? = null


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

    private var _micVolume = mutableStateOf(1f)
    private var _deviceVolume = mutableStateOf(1f)
    val micVolume = _micVolume as State<Float>
    val deviceVolume = _deviceVolume as State<Float>

    fun setMicVolume(volume: Float) {
        _micVolume.value = volume
        mediaProjectionService?.setMicVolume(volume)
    }

    fun setDeviceVolume(volume: Float) {
        _deviceVolume.value = volume
        mediaProjectionService?.setDeviceVolume(volume)
    }

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

        Text(
            text = "Sound Drift",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            textAlign = TextAlign.Start
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh 
            )
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


                if (isMicEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Volume_mute,
                            contentDescription = "Microphone Volume",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = audioStreamer.micVolume.value,
                            onValueChange = { audioStreamer.setMicVolume(it) },
                            modifier = Modifier.weight(1f),
                            enabled = !isStreaming
                        )
                        Icon(
                            imageVector = Volume_up,
                            contentDescription = "Microphone Volume Max",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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


                if (isDeviceAudioEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Volume_mute,
                            contentDescription = "Device Volume Min",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = audioStreamer.deviceVolume.value,
                            onValueChange = { audioStreamer.setDeviceVolume(it) },
                            modifier = Modifier.weight(1f),
                            enabled = !isStreaming
                        )
                        Icon(
                            imageVector = Volume_up,
                            contentDescription = "Device Volume Max",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
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