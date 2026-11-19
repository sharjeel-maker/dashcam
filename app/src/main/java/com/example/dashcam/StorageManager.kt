package com.example.dashcam

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

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

            // Look for videos in Movies/Dashcam
            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
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

    private fun deleteVideo(context: Context, id: Long) {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        context.contentResolver.delete(uri, null, null)
    }

    private data class VideoItem(val id: Long, val size: Long, val dateAdded: Long)
}