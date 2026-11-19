package com.example.dashcam

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CleanupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        StorageManager.cleanup(applicationContext)
        return Result.success()
    }
}