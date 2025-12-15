package com.qintel.android.caller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class CallWorkerService : Service() {

    private val TAG = "CallWorkerService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "CallWorkerChannel"

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            requestTimeoutMillis = 20_000
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Polling for work..."))
        startPolling()
        Log.d(TAG, "Service started and polling initiated.")
        return START_STICKY // Ensures service restarts if killed
    }

    private fun startPolling() {
        serviceScope.launch {
            while (isActive) {
                if (!isWorkInProgress()) {
                    try {
                        val sharedPref = getSharedPreferences("CallAppPrefs", Context.MODE_PRIVATE)
                        val sim1 = sharedPref.getString("simNumber1", "") ?: ""
                        val sim2 = sharedPref.getString("simNumber2", "") ?: ""

                        val simCards = mutableListOf<String>()
                        if (sim1.isNotBlank()) simCards.add(sim1)
                        if (sim2.isNotBlank()) simCards.add(sim2)

                        val simData = SimData(simCards)
                        Log.d(TAG, "Checking for work with payload: $simData")
                        updateNotification("Checking for work...")

                        val workItem: WorkItem = client.post("https://qintel-backend.onrender.com/api/take-work") {
                            contentType(ContentType.Application.Json)
                            setBody(simData)
                        }.body()

                        if (!workItem.callSequance.isNullOrBlank() && !workItem.fileName.isNullOrBlank()) {
                            Log.d(TAG, "Received work: $workItem. Launching MainActivity.")
                            setWorkInProgress(true, workItem)

                            // Launch MainActivity to handle the call
                            val mainActivityIntent = Intent(this@CallWorkerService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                putExtra("workItem", workItem)
                            }
                            startActivity(mainActivityIntent)
                        } else {
                            Log.d(TAG, "No work available.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching work.", e)
                    }
                } else {
                    Log.d(TAG, "Work is in progress. Skipping poll.")
                    updateNotification("Work in progress...")
                }
                delay(5_000)
            }
        }
    }
    
    private fun isWorkInProgress(): Boolean {
        val sharedPref = getSharedPreferences("CallAppPrefs", Context.MODE_PRIVATE)
        val isInProgress = sharedPref.getBoolean("isWorkInProgress", false)
        if (!isInProgress) {
            return false
        }

        val workStartTime = sharedPref.getLong("workStartTime", 0L)
        val elapsedTime = System.currentTimeMillis() - workStartTime
        val threeMinutesInMillis = 3 * 60 * 1000

        if (elapsedTime > threeMinutesInMillis) {
            Log.w(TAG, "Work in progress flag is stale (> 3 minutes). Clearing it.")
            setWorkInProgress(false, null) // Clear the stale work
            return false
        }

        return true
    }

    private fun setWorkInProgress(inProgress: Boolean, workItem: WorkItem?) {
        val sharedPref = getSharedPreferences("CallAppPrefs", Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putBoolean("isWorkInProgress", inProgress)
            if (inProgress && workItem != null) {
                putLong("workStartTime", System.currentTimeMillis())
                putString("lastFileName", workItem.fileName)
                val durationSeconds = workItem.recordingDuration ?: 60
                putLong("callDurationMs", durationSeconds * 1000L)
            } else {
                remove("workStartTime")
            }
            apply()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Call Worker Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caller App is running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel() // Cancel all coroutines started by this scope
        client.close()
        Log.d(TAG, "Service destroyed.")
    }
}
