package com.heymeowcat.sounddrift

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MediaProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val binder = LocalBinder()

    private val CHANNEL_ID = "SoundDriftServiceChannel"
    private val NOTIFICATION_ID = 1
    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    interface AudioDataCallback {
        fun onAudioDataAvailable(data: ByteArray, size: Int)
    }

    private var audioDataCallback: AudioDataCallback? = null

    fun setAudioDataCallback(callback: AudioDataCallback) {
        this.audioDataCallback = callback
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val stopFilter = IntentFilter("STOP_STREAMING")
        registerReceiver(stopReceiver, stopFilter, RECEIVER_NOT_EXPORTED)
    }

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
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
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

    fun startAudioRecording() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                audioRecord?.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        audioDataCallback?.onAudioDataAvailable(buffer, read)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopAudioRecording()
            }
        }
    }

    fun stopAudioRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaProjection?.stop()
        unregisterReceiver(stopReceiver)
        stopAudioRecording()
    }
}
