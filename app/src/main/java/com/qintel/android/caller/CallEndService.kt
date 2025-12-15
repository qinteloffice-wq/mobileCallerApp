package com.qintel.android.caller

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CallEndService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var endCallRunnable: Runnable? = null
    private val TAG = "CallEndService"

    companion object {
        const val ACTION_START_TIMER = "com.qintel.android.caller.ACTION_START_TIMER"
        const val ACTION_STOP_TIMER = "com.qintel.android.caller.ACTION_STOP_TIMER"
        const val ACTION_MUTE_CALL = "com.qintel.android.caller.ACTION_MUTE_CALL"
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_TIMER -> {
                    Log.d(TAG, "Received start timer command.")
                    startEndCallTimer()
                }
                ACTION_STOP_TIMER -> {
                    Log.d(TAG, "Received stop timer command.")
                    stopEndCallTimer()
                }
                ACTION_MUTE_CALL -> {
                    Log.d(TAG, "Received mute call command. Logging view hierarchy before attempting action.")
                    logViewHierarchy(rootInActiveWindow, 0)
                    findAndClickMuteButton(rootInActiveWindow)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected.")
        val filter = IntentFilter().apply {
            addAction(ACTION_START_TIMER)
            addAction(ACTION_STOP_TIMER)
            addAction(ACTION_MUTE_CALL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    private fun startEndCallTimer() {
        stopEndCallTimer() // Cancel any existing timer

        val sharedPref = getSharedPreferences("CallAppPrefs", Context.MODE_PRIVATE)
        val durationMs = sharedPref.getLong("callDurationMs", 60_000L) // Default to 60 seconds

        endCallRunnable = Runnable {
            Log.d(TAG, "Timer elapsed ($durationMs ms). Logging view hierarchy before attempting to end call.")
            logViewHierarchy(rootInActiveWindow, 0)
            findAndClickEndCallButton(rootInActiveWindow)
        }
        handler.postDelayed(endCallRunnable!!, durationMs)
    }

    private fun stopEndCallTimer() {
        endCallRunnable?.let {
            handler.removeCallbacks(it)
            endCallRunnable = null
            Log.d(TAG, "End call timer cancelled.")
        }
    }

    private fun logViewHierarchy(nodeInfo: AccessibilityNodeInfo?, depth: Int) {
        if (nodeInfo == null) return
        val padding = "  ".repeat(depth)
        Log.d(TAG, "$padding- Text: '${nodeInfo.text}', Desc: '${nodeInfo.contentDescription}', ID: '${nodeInfo.viewIdResourceName}', Clickable: ${nodeInfo.isClickable}")

        for (i in 0 until nodeInfo.childCount) {
            logViewHierarchy(nodeInfo.getChild(i), depth + 1)
        }
    }

    private fun findAndClickMuteButton(rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) {
            Log.e(TAG, "Cannot search for Mute button, root node is null.")
            return
        }

        val muteKeywords = listOf("Mute", "Unmute")
        for (keyword in muteKeywords) {
            val muteButtonNodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            if (muteButtonNodes.isNotEmpty()) {
                for (button in muteButtonNodes) {
                    if (button.isClickable) {
                        Log.d(TAG, "Found mute button with text/description: '$keyword'. Clicking it.")
                        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return // Found and clicked, our job is done
                    }
                }
            } else {
                Log.d(TAG, "Did not find mute button with keyword: '$keyword'")
            }
        }
        Log.w(TAG, "Mute button not found after checking all keywords.")
    }

    private fun findAndClickEndCallButton(rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) {
            Log.e(TAG, "Cannot search for End Call button, root node is null.")
            return
        }

        val endCallDescriptions = listOf("End call", "Hang up", "End")
        for (desc in endCallDescriptions) {
            val endCallNodes = rootNode.findAccessibilityNodeInfosByText(desc)
            if (endCallNodes.isNotEmpty()) {
                for (button in endCallNodes) {
                    if (button.isClickable) {
                        Log.d(TAG, "Found end call button with text/description: '$desc'. Clicking it.")
                        button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return // Found and clicked, our job is done
                    }
                }
            } else {
                 Log.d(TAG, "Did not find end call button with keyword: '$desc'")
            }
        }
        Log.w(TAG, "End call button not found after checking all keywords.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Accessibility service unbound.")
        unregisterReceiver(commandReceiver)
        stopEndCallTimer()
        return super.onUnbind(intent)
    }
}
