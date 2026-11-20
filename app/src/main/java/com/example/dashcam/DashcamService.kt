package com.example.dashcam

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashcamService : LifecycleService() {

    private val binder = LocalBinder()
    
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var segmentJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // State exposed to UI
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private var isIntendedToKeepRecording = false
    private var sessionStartTime = 0L
    private var timerJob: Job? = null
    private var lastNotificationTime = 0L
    
    private var isCameraReady = false
    private var pendingStart = false
    
    private val _storageUsedMB = MutableStateFlow(0L)
    val storageUsedMB: StateFlow<Long> = _storageUsedMB.asStateFlow()
    inner class LocalBinder : Binder() {
        fun getService(): DashcamService = this@DashcamService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            "START" -> startRecordingSequence()
            "STOP" -> stopRecordingSequence()
        }
        
        // Handle being killed
        return START_STICKY
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            // Set Quality to SD (480p) for max battery/storage savings
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            
            val vCapBuilder = VideoCapture.Builder(recorder)
                .setTargetRotation(android.view.Surface.ROTATION_90) // Force horizontal landscape FOV even when mounted vertically
            androidx.camera.camera2.interop.Camera2Interop.Extender(vCapBuilder)
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    android.util.Range(20, 24) // Smooth motion with low CPU/battery consumption
                )
            val vCap = vCapBuilder.build()
            videoCapture = vCap

            // Create timestamp overlay effect on recorded video frames
            val overlayEffect = androidx.camera.effects.OverlayEffect(
                androidx.camera.effects.OverlayEffect.VIDEO_CAPTURE,
                0,
                android.os.Handler(android.os.Looper.getMainLooper())
            ) { error ->
                Log.e("DashcamService", "OverlayEffect error", error)
            }

            overlayEffect.setOnDrawListener { frame ->
                try {
                    val canvas = frame.overlayCanvas
                    val height = canvas.height

                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.YELLOW
                        textSize = (height * 0.045f).coerceAtLeast(24f)
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setShadowLayer(6f, 3f, 3f, android.graphics.Color.BLACK)
                    }

                    val timeStamp = SimpleDateFormat("MMM dd, yyyy  HH:mm:ss", Locale.US).format(Date())
                    
                    val x = 30f
                    val y = height - 40f
                    canvas.drawText(timeStamp, x, y, paint)
                } catch (e: Exception) {
                    Log.e("DashcamService", "Overlay draw error", e)
                }
                true
            }

            val useCaseGroup = androidx.camera.core.UseCaseGroup.Builder()
                .addUseCase(vCap)
                .addEffect(overlayEffect)
                .build()

            val cameraSelector = getWidestBackCameraSelector(cameraProvider)

            try {
                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    useCaseGroup
                )

                // Force minimum zoom ratio (0.5x Ultra-Wide lens on multi-camera devices)
                val minZoom = boundCamera.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
                if (minZoom < 1.0f) {
                    boundCamera.cameraControl.setZoomRatio(minZoom)
                }
                boundCamera.cameraInfo.zoomState.observe(this) { state ->
                    if (state != null && state.minZoomRatio < 1.0f) {
                        boundCamera.cameraControl.setZoomRatio(state.minZoomRatio)
                    }
                }

                isCameraReady = true
                if (pendingStart) {
                    pendingStart = false
                    startRecordingSequence()
                }
            } catch (exc: Exception) {
                Log.e("DashcamService", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getWidestBackCameraSelector(cameraProvider: ProcessCameraProvider): CameraSelector {
        val cameraInfos = cameraProvider.availableCameraInfos
        var bestCameraInfo: androidx.camera.core.CameraInfo? = null
        var minFocalLength = Float.MAX_VALUE

        for (info in cameraInfos) {
            try {
                val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
                val lensFacing = camera2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    val focalLengths = camera2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    if (focalLengths != null && focalLengths.isNotEmpty()) {
                        val minFocal = focalLengths.minOrNull() ?: Float.MAX_VALUE
                        if (minFocal < minFocalLength) {
                            minFocalLength = minFocal
                            bestCameraInfo = info
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DashcamService", "Error checking focal length for camera info", e)
            }
        }

        return bestCameraInfo?.cameraSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
    }

    @SuppressLint("MissingPermission")
    private fun startNextSegment() {
        if (!isIntendedToKeepRecording) return

        // 1. Check storage limits before starting a file
        StorageManager.cleanup(this)

        // 2. Prep MediaStore output
        val name = "Dashcam_" + SimpleDateFormat("MMM-dd-yyyy_HH-mm-ss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Dashcam")
        }

        val outputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        // 3. Start recording
        val recording = videoCapture?.output?.prepareRecording(this, outputOptions)
        
        // Note: Audio is disabled by default per constraints, can add .withAudioEnabled() if requested
        
        activeRecording = recording?.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                activeRecording = null
                // If we didn't manually stop the whole sequence, start the next segment immediately
                if (isIntendedToKeepRecording) {
                    startNextSegment()
                }
            }
        }

        // 4. Schedule 5-minute segment rotation
        segmentJob?.cancel()
        if (Constants.SEGMENT_DURATION_MS > 0) {
            segmentJob = lifecycleScope.launch {
                delay(Constants.SEGMENT_DURATION_MS)
                activeRecording?.stop()
            }
        }
    }

    private fun startRecordingSequence() {
        if (!isCameraReady) {
            pendingStart = true
            return
        }
        if (_isRecording.value) return

        startForeground(1, createNotification())
        acquireWakeLock()
        
        isIntendedToKeepRecording = true
        _isRecording.value = true
        
        sessionStartTime = System.currentTimeMillis()
        lastNotificationTime = sessionStartTime
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            // Initial fetch
            _storageUsedMB.value = StorageManager.getFolderSizeMB(this@DashcamService)
            
            while (true) {
                val now = System.currentTimeMillis()
                _recordingDuration.value = now - sessionStartTime
                
                // Fire a notification every 30 minutes (30 * 60 * 1000 ms)
                if (now - lastNotificationTime >= 1800000L) {
                    lastNotificationTime = now
                    sendUpdateNotification()
                }
                
                // Update storage every 10 seconds (roughly)
                if (now / 1000 % 10 == 0L) {
                    _storageUsedMB.value = StorageManager.getFolderSizeMB(this@DashcamService)
                }
                
                delay(1000)
            }
        }

        startNextSegment()
    }

    private fun stopRecordingSequence() {
        isIntendedToKeepRecording = false
        _isRecording.value = false
        
        timerJob?.cancel()
        _recordingDuration.value = 0L
        
        segmentJob?.cancel()
        activeRecording?.stop()
        
        releaseWakeLock()
        
        // Final cleanup
        StorageManager.cleanup(this)
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "dashcam_channel",
                "Dashcam Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            
            val updateChannel = NotificationChannel(
                "dashcam_update_channel",
                "Dashcam Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(updateChannel)
        }
    }

    private fun sendUpdateNotification() {
        val stopIntent = Intent(this, DashcamService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "dashcam_update_channel")
            .setContentTitle("Dashcam 30m Status")
            .setContentText("Still recording in background. Storage used: ${_storageUsedMB.value} MB")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_media_pause, "STOP RECORDING", stopPendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2, notification)
    }

    private fun createNotification(): android.app.Notification {
        val stopIntent = Intent(this, DashcamService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "dashcam_channel")
            .setContentTitle("Dashcam Recording Active")
            .setContentText("Tap to open app, or tap STOP RECORDING below.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "STOP RECORDING", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Dashcam::RecordingWakeLock")
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingSequence()
    }
}