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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MediaProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var deviceAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var connectionMonitorJob: Job? = null
    private val binder = LocalBinder()
    private var isMicEnabled = AtomicBoolean(false)
    private var isDeviceAudioEnabled = AtomicBoolean(false)
    private var rtpSocket: DatagramSocket? = null
    private var micVolume = 1f
    private var deviceVolume = 1f
    private var connectedClientIP: String = ""
    private var lastPacketTime = AtomicLong(0L)
    private val CONNECTION_TIMEOUT = 5000L

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

    fun setMicVolume(volume: Float) {
        micVolume = volume
    }

    fun setDeviceVolume(volume: Float) {
        deviceVolume = volume
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startUdpServer()
    }

    private fun startUdpServer() {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                rtpSocket = DatagramSocket(55556)
                println("UDP Server started on port 55556")
                startConnectionMonitoring()

                while (isActive) {
                    val receiveBuffer = ByteArray(1024)
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

                    println("Waiting for client connection...")
                    connectedClientIP = ""
                    stopAudioRecording()

                    rtpSocket?.receive(receivePacket)

                    connectedClientIP = receivePacket.address.hostAddress ?: ""
                    println("Client connected from: $connectedClientIP")

                    lastPacketTime.set(System.currentTimeMillis())
                    updateAudioRecording()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startConnectionMonitoring() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                if (connectedClientIP.isNotEmpty()) {
                    val timeSinceLastPacket = System.currentTimeMillis() - lastPacketTime.get()
                    if (timeSinceLastPacket > CONNECTION_TIMEOUT) {
                        println("Client connection timed out")
                        connectedClientIP = ""
                        stopAudioRecording()
                    }
                }
                delay(1000)
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
            startAudioStreaming()
        }
    }

    private fun startAudioStreaming() {
        if (!isMicEnabled.get() && !isDeviceAudioEnabled.get()) return

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufferSize)
            val mixBuffer = ByteArray(bufferSize)
            var packetCount = 0L

            try {
                while (isActive && connectedClientIP.isNotEmpty()) {
                    var totalSize = 0

                    if (isMicEnabled.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val micSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (micSize > 0) {
                            applyVolume(buffer, micSize, micVolume)
                            System.arraycopy(buffer, 0, mixBuffer, 0, micSize)
                            totalSize = micSize
                        }
                    }

                    if (isDeviceAudioEnabled.get() && deviceAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val deviceSize = deviceAudioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (deviceSize > 0) {
                            applyVolume(buffer, deviceSize, deviceVolume)
                            if (totalSize > 0) {
                                mixAudio(mixBuffer, buffer, deviceSize)
                            } else {
                                System.arraycopy(buffer, 0, mixBuffer, 0, deviceSize)
                                totalSize = deviceSize
                            }
                        }
                    }

                    if (totalSize > 0) {
                        try {
                            val packet = DatagramPacket(
                                mixBuffer,
                                totalSize,
                                InetAddress.getByName(connectedClientIP),
                                55555
                            )
                            rtpSocket?.send(packet)
                            lastPacketTime.set(System.currentTimeMillis())

                            packetCount++
                            if (packetCount % 100 == 0L) {
                                println("Sent packet #$packetCount, size: $totalSize bytes")
                            }
                        } catch (e: Exception) {
                            println("Error sending audio packet: ${e.message}")
                            connectedClientIP = ""
                            break
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                connectedClientIP = ""
            }
        }
    }

    private fun applyVolume(buffer: ByteArray, size: Int, volume: Float) {
        for (i in 0 until size step 2) {
            if (i + 1 >= size) break

            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            val adjustedSample = (sample * volume).toInt().coerceIn(-32768, 32767)

            buffer[i] = (adjustedSample and 0xFF).toByte()
            buffer[i + 1] = ((adjustedSample shr 8) and 0xFF).toByte()
        }
    }

    private fun mixAudio(output: ByteArray, input: ByteArray, size: Int) {
        for (i in 0 until size step 2) {
            if (i + 1 >= size) break

            val sample1 = (output[i + 1].toInt() shl 8) or (output[i].toInt() and 0xFF)
            val sample2 = (input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)

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
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        deviceAudioRecord?.apply {
            stop()
            release()
        }
        deviceAudioRecord = null

        recordingJob?.cancel()
        recordingJob = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        connectionMonitorJob?.cancel()
        stopAudioRecording()
        mediaProjection?.stop()
        mediaProjection = null
        rtpSocket?.close()
        super.onDestroy()
    }
}