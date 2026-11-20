package com.example.dashcam

object Constants {
    // Camera settings (Note: CameraX strictly manages bitrate/fps internally based on Quality.HD. 
    // These constants represent your targets, but standard CameraX will output standard HD bitrates).
    const val VIDEO_WIDTH = 1920
    const val VIDEO_HEIGHT = 1080
    const val FPS = 30
    const val BITRATE = 2700000

    const val RECORD_AUDIO = false

    // 5-minute video clips (seamless continuous recording)
    const val SEGMENT_DURATION_MS = 5L * 60L * 1000L

    // Storage limits
    const val MAX_FOLDER_SIZE_GB = 4L
    const val MAX_FOLDER_SIZE_BYTES = MAX_FOLDER_SIZE_GB * 1024L * 1024L * 1024L
    const val RETENTION_DAYS = 7
    const val EMERGENCY_RETENTION_DAYS = 30
}