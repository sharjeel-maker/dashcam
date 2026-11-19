package com.example.dashcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var dashcamService by mutableStateOf<DashcamService?>(null)
    private var isBound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DashcamService.LocalBinder
            dashcamService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            dashcamService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            sendCommandToService("START")
        } else {
            Toast.makeText(this, "Permissions denied. App cannot record.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schedule daily cleanup WorkManager
        val cleanupWork = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "DashcamCleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupWork
        )

        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            sendCommandToService("START")
        } else {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }

        setContent {
            DashcamUI()
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, DashcamService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    @Composable
    fun DashcamUI() {
        val isRecording = dashcamService?.isRecording?.collectAsState(initial = false)?.value ?: false
        val duration = dashcamService?.recordingDuration?.collectAsState(initial = 0L)?.value ?: 0L
        val storageMB = dashcamService?.storageUsedMB?.collectAsState(initial = 0L)?.value ?: 0L

        // Format duration mm:ss
        val minutes = (duration / 1000) / 60
        val seconds = (duration / 1000) % 60
        val timeString = String.format("%02d:%02d", minutes, seconds)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            
            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (isRecording) {
                        sendCommandToService("STOP")
                    } else {
                        StorageManager.cleanup(this@MainActivity) // Clean on start
                        sendCommandToService("START")
                        moveTaskToBack(true)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color(0xFF00C853)
                ),
                modifier = Modifier
                    .size(200.dp, 200.dp)
            ) {
                Text(
                    text = if (isRecording) "STOP" else "START",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = if (isRecording) "Recording... $timeString" else "Idle",
                color = Color.White,
                fontSize = 20.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Storage used: $storageMB MB",
                color = Color.Gray,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { openVideosFolder() },
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("Open videos folder")
            }

            Button(
                onClick = { shareLatestVideo(this@MainActivity) },
                modifier = Modifier.padding(bottom = 48.dp)
            ) {
                Text("Share latest clip to WhatsApp")
            }
        }
    }

    private fun shareLatestVideo(context: Context) {
        val latestVideoUri = StorageManager.getLatestVideoUri(context)
        if (latestVideoUri != null) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, latestVideoUri)
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(shareIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "WhatsApp is not installed.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "No clips found to share.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendCommandToService(action: String) {
        val intent = Intent(this, DashcamService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun openVideosFolder() {
        try {
            // First try: Document Provider pointing specifically to Movies/Dashcam
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2FDashcam")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                // Second try: Generic fallback to file manager
                val genericIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "video/mp4"
                }
                startActivity(genericIntent)
            } catch (e2: Exception) {
                // Final fallback
                Toast.makeText(this, "Clips saved in Movies/Dashcam", Toast.LENGTH_LONG).show()
            }
        }
    }
}