package com.example.dashcam

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StorageManager {
    private const val TAG = "StorageManager"
    private const val FOLDER_NAME = "Dashcam"

    fun cleanup(context: Context) {
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
            )

            // Look for normal videos in Movies/Dashcam (Exclude protected EMERGENCY files)
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE 'EMERGENCY_%'"
            val selectionArgs = arrayOf("%${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME%")
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} ASC"

            val videos = mutableListOf<VideoItem>()
            var totalSizeBytes = 0L

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val size = cursor.getLong(sizeCol)
                    val dateAdded = cursor.getLong(dateCol) // in seconds
                    videos.add(VideoItem(id, size, dateAdded))
                    totalSizeBytes += size
                }
            }

            // 1. Delete videos older than RETENTION_DAYS
            val cutoffDate = (System.currentTimeMillis() / 1000L) - (Constants.RETENTION_DAYS * 24 * 60 * 60)
            val iterator = videos.iterator()
            
            while (iterator.hasNext()) {
                val video = iterator.next()
                if (video.dateAdded < cutoffDate) {
                    deleteVideo(context, video.id)
                    totalSizeBytes -= video.size
                    iterator.remove()
                    Log.d(TAG, "Deleted old video: ${video.id}")
                }
            }

            // 2. Enforce MAX_FOLDER_SIZE (Keep deleting oldest until under limit)
            // Note: the list is already sorted DATE_ADDED ASC
            val sizeIterator = videos.iterator()
            while (sizeIterator.hasNext() && totalSizeBytes > Constants.MAX_FOLDER_SIZE_BYTES) {
                val video = sizeIterator.next()
                deleteVideo(context, video.id)
                totalSizeBytes -= video.size
                sizeIterator.remove()
                Log.d(TAG, "Deleted video for space: ${video.id}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during storage cleanup", e)
        }
    }

    fun getLatestVideoUri(context: Context): android.net.Uri? {
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val id = cursor.getLong(idCol)
                return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }

    fun getFolderSizeMB(context: Context): Long {
        var totalSizeBytes = 0L
        
        // Method 1: Direct File directory check
        try {
            val folder = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                FOLDER_NAME
            )
            if (folder.exists() && folder.isDirectory) {
                val files = folder.listFiles()
                if (files != null && files.isNotEmpty()) {
                    val directSize = files.sumOf { it.length() }
                    if (directSize > 0) {
                        return directSize / (1024 * 1024)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking direct folder size", e)
        }

        // Method 2: MediaStore fallback
        val projection = arrayOf(MediaStore.Video.Media.SIZE)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME%")

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
                totalSizeBytes += cursor.getLong(sizeCol)
            }
        }
        return totalSizeBytes / (1024 * 1024)
    }

    fun getAllVideos(context: Context): List<DashcamVideoFile> {
        val list = mutableListOf<DashcamVideoFile>()
        
        // 1. Try direct files
        try {
            val folder = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                FOLDER_NAME
            )
            if (folder.exists() && folder.isDirectory) {
                val files = folder.listFiles()
                if (files != null) {
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                    for (file in files.sortedByDescending { it.lastModified() }) {
                        if (file.name.endsWith(".mp4") || file.name.endsWith(".m4v")) {
                            val sizeMB = String.format(Locale.US, "%.1f MB", file.length() / (1024.0 * 1024.0))
                            val dateStr = dateFormat.format(Date(file.lastModified()))
                            list.add(DashcamVideoFile(
                                id = null,
                                name = file.name,
                                sizeMB = sizeMB,
                                formattedDate = dateStr,
                                uri = null,
                                file = file
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching direct files", e)
        }

        // 2. MediaStore query if direct files returned empty
        if (list.isEmpty()) {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME%")
            val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Video_$id.mp4"
                    val size = cursor.getLong(sizeCol)
                    val dateSec = cursor.getLong(dateCol)
                    val sizeMB = String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024.0))
                    val dateStr = dateFormat.format(Date(dateSec * 1000L))
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    list.add(DashcamVideoFile(
                        id = id,
                        name = name,
                        sizeMB = sizeMB,
                        formattedDate = dateStr,
                        uri = uri,
                        file = null
                    ))
                }
            }
        }

        return list
    }

    fun deleteVideoItem(context: Context, item: DashcamVideoFile) {
        try {
            item.file?.let {
                if (it.exists()) it.delete()
            }
            item.id?.let {
                deleteVideo(context, it)
            }
            item.uri?.let {
                context.contentResolver.delete(it, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting video item", e)
        }
    }

    private fun deleteVideo(context: Context, id: Long) {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        context.contentResolver.delete(uri, null, null)
    }

    private data class VideoItem(val id: Long, val size: Long, val dateAdded: Long)
}

data class DashcamVideoFile(
    val id: Long?,
    val name: String,
    val sizeMB: String,
    val formattedDate: String,
    val uri: android.net.Uri?,
    val file: java.io.File?
)