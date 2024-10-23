package com.heymeowcat.sounddrift

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class MediaProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var deviceAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val binder = LocalBinder()
    private var isMicEnabled = AtomicBoolean(false)
    private var isDeviceAudioEnabled = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var listenerJob: Job? = null
    private var connectedClientIP: String = ""

    private val CHANNEL_ID = "SoundDriftServiceChannel"
    private val NOTIFICATION_ID = 1
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    fun getConnectedClientIP(): String = connectedClientIP

    fun setMicEnabled(enabled: Boolean) {
        isMicEnabled.set(enabled)
        updateAudioRecording()
    }

    fun setDeviceAudioEnabled(enabled: Boolean) {
        isDeviceAudioEnabled.set(enabled)
        updateAudioRecording()
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startTcpServer()

        val stopFilter = IntentFilter("STOP_STREAMING")
        registerReceiver(stopReceiver, stopFilter, RECEIVER_NOT_EXPORTED)
    }

    private fun startTcpServer() {
        listenerJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(12345)
                while (isActive) {
                    try {
                        clientSocket = serverSocket?.accept()
                        clientSocket?.let { socket ->
                            connectedClientIP = socket.inetAddress.hostAddress ?: ""
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        delay(1000) // Wait before retrying
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != 0 && data != null) {
            startForegroundService()
            startMediaProjection(resultCode, data)
        } else {
            stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SoundDrift")
            .setContentText("Streaming audio...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        updateAudioRecording()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "SoundDrift Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun updateAudioRecording() {
        stopAudioRecording()
        if (isMicEnabled.get() || isDeviceAudioEnabled.get()) {
            if (isMicEnabled.get()) {
                startMicRecording()
            }
            if (isDeviceAudioEnabled.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startDeviceAudioCapture()
            }
            startAudioRecording()
        }
    }

    private fun startAudioRecording() {
        if (!isMicEnabled.get() && !isDeviceAudioEnabled.get()) return

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufferSize)
            val mixBuffer = ByteArray(bufferSize)

            try {
                while (isActive) {
                    var totalSize = 0

                    // Read from mic if enabled
                    if (isMicEnabled.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val micSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (micSize > 0) {
                            System.arraycopy(buffer, 0, mixBuffer, 0, micSize)
                            totalSize = micSize
                        }
                    }

                    // Read from device if enabled
                    if (isDeviceAudioEnabled.get() && deviceAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val deviceSize = deviceAudioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (deviceSize > 0) {
                            if (totalSize > 0) {
                                mixAudio(mixBuffer, buffer, deviceSize)
                            } else {
                                System.arraycopy(buffer, 0, mixBuffer, 0, deviceSize)
                                totalSize = deviceSize
                            }
                        }
                    }

                    // Stream the audio if we have data and client is connected
                    if (totalSize > 0 && clientSocket?.isConnected == true) {
                        try {
                            clientSocket?.getOutputStream()?.write(mixBuffer, 0, totalSize)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            clientSocket?.close()
                            clientSocket = null
                            connectedClientIP = ""
                        }
                    } else {
                        delay(10) // Prevent tight loop if no data
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun mixAudio(output: ByteArray, input: ByteArray, size: Int) {
        for (i in 0 until size step 2) {
            if (i + 1 >= size) break

            val sample1 = (output[i + 1].toInt() shl 8) or (output[i].toInt() and 0xFF)
            val sample2 = (input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)

            // Mix samples and prevent clipping
            val mixed = ((sample1.toLong() + sample2.toLong()) / 2).toInt().coerceIn(-32768, 32767)

            output[i] = (mixed and 0xFF).toByte()
            output[i + 1] = ((mixed shr 8) and 0xFF).toByte()
        }
    }

    private fun startMicRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            ).apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    startRecording()
                } else {
                    release()
                    audioRecord = null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            audioRecord?.release()
            audioRecord = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startDeviceAudioCapture() {
        if (mediaProjection == null) return

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            deviceAudioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build().apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        startRecording()
                    } else {
                        release()
                        deviceAudioRecord = null
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            deviceAudioRecord?.release()
            deviceAudioRecord = null
        }
    }

    private fun stopAudioRecording() {
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                it.release()
                audioRecord = null
            }
        }

        deviceAudioRecord?.let {
            try {
                if (it.state == AudioRecord.STATE_INITIALIZED) {
                    it.stop()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                it.release()
                deviceAudioRecord = null
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        stopAudioRecording()
        listenerJob?.cancel()
        serverSocket?.close()
        clientSocket?.close()
        unregisterReceiver(stopReceiver)
        super.onDestroy()
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopSelf()
        }
    }
}