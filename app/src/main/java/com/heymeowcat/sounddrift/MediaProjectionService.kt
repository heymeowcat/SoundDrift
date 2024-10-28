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
import org.json.JSONException
import org.json.JSONObject
import java.io.ObjectOutputStream
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToLong

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
    private var tcpServer: ServerSocket? = null
    private var tcpWriter: PrintWriter? = null
    private var micVolume = 1f
    private var deviceVolume = 1f
    private var connectedClientIP: String = ""
    private var connectedClientDeviceName: String = ""
    private var lastPacketTime = AtomicLong(0L)
    private val CONNECTION_TIMEOUT = 5000L

    private val latencyMeasurements = ConcurrentLinkedQueue<Long>()
    private var maxLatency = 0L

    private val CHANNEL_ID = "SoundDriftServiceChannel"
    private val NOTIFICATION_ID = 1
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        AudioFormat.ENCODING_PCM_16BIT
    )

    fun getConnectedClientIP(): String = connectedClientIP
    fun getConnectedClientDeviceName(): String = connectedClientDeviceName



    fun setMicEnabled(enabled: Boolean) {
        isMicEnabled.set(enabled)
        updateAudioRecording()
        sendMetadata()
    }

    fun setDeviceAudioEnabled(enabled: Boolean) {
        isDeviceAudioEnabled.set(enabled)
        updateAudioRecording()
        sendMetadata()
    }

    fun setMicVolume(volume: Float) {
        micVolume = volume
        sendMetadata()
    }

    fun setDeviceVolume(volume: Float) {
        deviceVolume = volume
        sendMetadata()
    }

    private fun sendMetadata() {
        try {
            val metadata = JSONObject().apply {
                put("deviceName", Build.MODEL)
                put("averageLatency", getAverageLatency())
                put("maxLatency", maxLatency)
                put("bufferMs", (bufferSize * 1000 / (sampleRate * 4))) // 4 bytes per sample in stereo
                put("micVolume", micVolume)
                put("deviceVolume", deviceVolume)
                put("isMicEnabled", isMicEnabled.get())
                put("isDeviceAudioEnabled", isDeviceAudioEnabled.get())
            }
            tcpWriter?.println(metadata.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getAverageLatency(): Long {
        return if (latencyMeasurements.isNotEmpty()) {
            latencyMeasurements.average().roundToLong()
        } else 0L
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startServers()
    }

    private fun startServers() {
        // Start TCP Server
        CoroutineScope(Dispatchers.IO).launch {
            try {
                tcpServer = ServerSocket(55557)
                println("TCP Server started on port 55557")

                while (isActive) {
                    val client = tcpServer?.accept() ?: continue
                    tcpWriter = PrintWriter(client.getOutputStream(), true) // auto-flush
                    println("TCP Client connected from: ${client.inetAddress.hostAddress}")
                    sendMetadata()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Start UDP Server
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

                    // Receive the initial connection packet
                    rtpSocket?.receive(receivePacket)
                    connectedClientIP = receivePacket.address.hostAddress ?: ""
                    println("UDP Client connected from: $connectedClientIP")

                    // Now receive and process device info
                    rtpSocket?.receive(receivePacket) // Receive device info
                    val receivedData = receivePacket.data.copyOf(receivePacket.length)
                    val receivedText = String(receivedData, StandardCharsets.UTF_8)

                    if (receivedText.startsWith("{") && receivedText.endsWith("}")) {
                        try {
                            val deviceInfo = JSONObject(receivedText)

                            connectedClientDeviceName = deviceInfo.getString("deviceName")
                            println("Device Name: $connectedClientDeviceName")

                        } catch (e: JSONException) {
                            println("Error parsing device info JSON: ${e.message}")
                        }
                    } else {
                        println("Invalid device info format received.")
                    }

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
            .setSmallIcon(R.drawable.ic_stat_name)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(ContextCompat.getColor(this, R.color.BlueGrey))
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
        sendMetadata()
    }

    private fun startAudioStreaming() {
        if (!isMicEnabled.get() && !isDeviceAudioEnabled.get()) return

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufferSize)
            val mixBuffer = ByteArray(bufferSize)
            var packetCount = 0L

            try {
                while (isActive && connectedClientIP.isNotEmpty()) {
                    val startTime = System.nanoTime()
                    var totalSize = 0

                    if (isMicEnabled.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val micSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (micSize > 0) {
                            if (isDeviceAudioEnabled.get()) {
                                // If both sources are enabled, copy to mix buffer for later mixing
                                System.arraycopy(buffer, 0, mixBuffer, 0, micSize)
                                totalSize = micSize
                            } else {
                                // If only mic is enabled, apply volume directly
                                applyStereoVolume(buffer, buffer, micSize, micVolume)
                                System.arraycopy(buffer, 0, mixBuffer, 0, micSize)
                                totalSize = micSize
                            }
                        }
                    }

                    if (isDeviceAudioEnabled.get() && deviceAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val deviceSize = deviceAudioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (deviceSize > 0) {
                            if (totalSize > 0) {
                                // Both sources enabled, mix them
                                mixStereoAudio(mixBuffer, buffer, deviceSize)
                            } else {
                                // Only device audio enabled, apply volume directly
                                applyStereoVolume(buffer, mixBuffer, deviceSize, deviceVolume)
                                totalSize = deviceSize
                            }
                        }
                    }

                    if (totalSize > 0) {
                        println("startAudioStreaming() - connectedClientIP: $connectedClientIP") // Add this line
                        try {
                            val packet = DatagramPacket(
                                mixBuffer,
                                totalSize,
                                InetAddress.getByName(connectedClientIP),
                                55555
                            )
                            rtpSocket?.send(packet)

                            // Calculate and update latency
                            val endTime = System.nanoTime()
                            val latency = (endTime - startTime) / 1_000_000 // Convert to ms
                            updateLatencyMetrics(latency)

                            lastPacketTime.set(System.currentTimeMillis())
                            packetCount++

                            if (packetCount % 100 == 0L) {
                                println("Sent packet #$packetCount, size: $totalSize bytes")
                                sendMetadata() // Update client with latest metrics periodically
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

    private fun applyStereoVolume(input: ByteArray, output: ByteArray, size: Int, volume: Float) {
        for (i in 0 until size step 4) {
            if (i + 3 >= size) break

            // Left channel
            val leftSample = (input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)
            val scaledLeft = (leftSample.toFloat() / 32768.0f * volume * 32768.0f).toInt().coerceIn(-32768, 32767)

            // Right channel
            val rightSample = (input[i + 3].toInt() shl 8) or (input[i + 2].toInt() and 0xFF)
            val scaledRight = (rightSample.toFloat() / 32768.0f * volume * 32768.0f).toInt().coerceIn(-32768, 32767)

            // Write scaled samples
            output[i] = (scaledLeft and 0xFF).toByte()
            output[i + 1] = ((scaledLeft shr 8) and 0xFF).toByte()
            output[i + 2] = (scaledRight and 0xFF).toByte()
            output[i + 3] = ((scaledRight shr 8) and 0xFF).toByte()
        }
    }

    private fun updateLatencyMetrics(latency: Long) {
        latencyMeasurements.offer(latency)
        if (latencyMeasurements.size > 100) {
            latencyMeasurements.poll()
        }
        maxLatency = maxOf(maxLatency, latency)
    }

    private fun mixStereoAudio(output: ByteArray, input: ByteArray, size: Int) {
        for (i in 0 until size step 4) {
            if (i + 3 >= size) break

            // Left channel
            val leftSample1 = (output[i + 1].toInt() shl 8) or (output[i].toInt() and 0xFF)
            val leftSample2 = (input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)

            // Convert to float for better precision in mixing
            val leftFloat1 = leftSample1.toFloat() / 32768.0f * micVolume
            val leftFloat2 = leftSample2.toFloat() / 32768.0f * deviceVolume
            val mixedLeft = ((leftFloat1 + leftFloat2) * 32768.0f).toInt().coerceIn(-32768, 32767)

            // Right channel
            val rightSample1 = (output[i + 3].toInt() shl 8) or (output[i + 2].toInt() and 0xFF)
            val rightSample2 = (input[i + 3].toInt() shl 8) or (input[i + 2].toInt() and 0xFF)

            val rightFloat1 = rightSample1.toFloat() / 32768.0f * micVolume
            val rightFloat2 = rightSample2.toFloat() / 32768.0f * deviceVolume
            val mixedRight = ((rightFloat1 + rightFloat2) * 32768.0f).toInt().coerceIn(-32768, 32767)

            // Write mixed samples
            output[i] = (mixedLeft and 0xFF).toByte()
            output[i + 1] = ((mixedLeft shr 8) and 0xFF).toByte()
            output[i + 2] = (mixedRight and 0xFF).toByte()
            output[i + 3] = ((mixedRight shr 8) and 0xFF).toByte()
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
                channelConfig,
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
                .setChannelMask(channelConfig)
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
        tcpWriter?.close()
        tcpServer?.close()
        tcpServer = null
        latencyMeasurements.clear()
        super.onDestroy()
    }
}