package com.qintel.android.caller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            Log.d("CallStateReceiver", "Phone state changed to: $state")

            when (state) {
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    lastState = TelephonyManager.EXTRA_STATE_OFFHOOK

                    // Delay sending the mute command to allow the dialer UI to load.
                    Handler(Looper.getMainLooper()).postDelayed({
                        val muteIntent = Intent(CallEndService.ACTION_MUTE_CALL).apply {
                            setPackage(context.packageName)
                        }
                        context.sendBroadcast(muteIntent)
                        Log.d("CallStateReceiver", "Sent explicit broadcast to mute call after delay.")
                    }, 3000) // 3-second delay

                    val startTimerIntent = Intent(CallEndService.ACTION_START_TIMER).apply {
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(startTimerIntent)
                    Log.d("CallStateReceiver", "Sent explicit broadcast to start end-call timer.")
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    val stopTimerIntent = Intent(CallEndService.ACTION_STOP_TIMER).apply {
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(stopTimerIntent)
                    Log.d("CallStateReceiver", "Sent explicit broadcast to stop end-call timer.")

                    if (lastState == TelephonyManager.EXTRA_STATE_OFFHOOK) {
                        lastState = TelephonyManager.EXTRA_STATE_IDLE
                        Log.d("CallStateReceiver", "Call ended. Starting upload process.")

                        val pendingResult = goAsync()
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                if (checkStoragePermission(context)) {
                                    uploadLatestRecording(context)
                                } else {
                                    Log.w("CallStateReceiver", "Storage permission denied. Work will time out in the service.")
                                }
                            } finally {
                                // Always signal completion to the service to unlock the polling loop.
                                val workCompleteIntent = Intent(CallWorkerService.ACTION_WORK_COMPLETE).apply {
                                    setPackage(context.packageName)
                                }
                                context.sendBroadcast(workCompleteIntent)
                                Log.d("CallStateReceiver", "Upload process finished. Sent broadcast to service.")
                                pendingResult.finish()
                            }
                        }
                    }
                }
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    lastState = TelephonyManager.EXTRA_STATE_RINGING
                }
            }
        }
    }

    private fun checkStoragePermission(context: Context): Boolean {
        // On Android 11+ (R), we need the All Files Access permission to delete the recording.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.e("checkStoragePermission", "MANAGE_EXTERNAL_STORAGE permission is not granted.")
                return false
            }
            return true
        } else {
            // For older versions, the standard storage permission is sufficient.
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.e("checkStoragePermission", "READ_EXTERNAL_STORAGE permission is not granted.")
            }
            return granted
        }
    }

    private suspend fun uploadLatestRecording(context: Context) {
        val sharedPref = context.getSharedPreferences("CallAppPrefs", Context.MODE_PRIVATE)
        val fileName = sharedPref.getString("lastFileName", null)

        if (fileName == null) {
            Log.e("UploadFile", "Could not retrieve file name for upload.")
            return
        }

        val possibleSourceDirs = listOf(
            File(Environment.getExternalStorageDirectory(), "Recordings/sound_recorder/call_rec"),
            File(Environment.getExternalStorageDirectory(), "MIUI/sound_recorder/call_rec"),
            File(Environment.getExternalStorageDirectory(), "CallRecorder"),
            File(Environment.getExternalStorageDirectory(), "call_records")
        )

        val sourceDir = possibleSourceDirs.find { it.exists() && it.isDirectory }

        if (sourceDir == null) {
            Log.e("UploadFile", "Could not find a valid call recording source directory.")
            return
        }

        // Add a small delay to ensure the file is fully written
        delay(2000)
        val latestFile = sourceDir.listFiles()?.maxByOrNull { it.lastModified() }

        if (latestFile == null) {
            Log.e("UploadFile", "No new recordings found in the directory.")
            return
        }
        
        val maxRetries = 3
        for (attempt in 1..maxRetries) {
            val client = HttpClient(CIO)
            try {
                Log.d("UploadFile", "Attempt $attempt of $maxRetries to upload ${latestFile.name}...")
                val response: HttpResponse = client.submitFormWithBinaryData(
                    url = "https://qintel-backend.onrender.com/api/post-recording",
                    formData = formData {
                        append("fileName", fileName)
                        append("file", latestFile.readBytes(), Headers.build {
                            append(HttpHeaders.ContentType, "audio/mpeg")
                            append(HttpHeaders.ContentDisposition, "filename=\"${latestFile.name}\"")
                        })
                    }
                )

                if (response.status == HttpStatusCode.OK) {
                    Log.d("UploadFile", "Successfully uploaded ${latestFile.name}. Now clearing the entire recording directory.")

                    // --- ROBUST CLEANUP LOGIC ---
                    clearRecordingDirectory(sourceDir)
                    // --- END OF CLEANUP LOGIC ---

                    return // Exit after successful upload and cleanup
                } else {
                    Log.e("UploadFile", "Upload attempt $attempt failed with status: ${response.status.value}")
                }
            } catch (e: Exception) {
                Log.e("UploadFile", "Error during file upload attempt $attempt: ", e)
            } finally {
                client.close()
            }

            if (attempt < maxRetries) {
                Log.d("UploadFile", "Waiting 5 seconds before next retry...")
                delay(5_000)
            }
        }
        Log.e("UploadFile", "Failed to upload file after $maxRetries attempts.")
    }

    private fun clearRecordingDirectory(directory: File) {
        if (!directory.exists() || !directory.isDirectory) {
            Log.w("Cleanup", "Cannot clear directory, as it does not exist or is not a directory: ${directory.absolutePath}")
            return
        }

        val filesToDelete = directory.listFiles()
        if (filesToDelete.isNullOrEmpty()) {
            Log.d("Cleanup", "Recording directory is already empty.")
            return
        }

        var successCount = 0
        var failureCount = 0
        for (file in filesToDelete) {
            // Ensure we don't delete subdirectories, only files.
            if (file.isFile) {
                if (file.delete()) {
                    successCount++
                } else {
                    failureCount++
                    Log.e("Cleanup", "Failed to delete file: ${file.name}")
                }
            }
        }
        Log.d("Cleanup", "Cleanup complete. Deleted $successCount files, failed to delete $failureCount files.")
    }
}
