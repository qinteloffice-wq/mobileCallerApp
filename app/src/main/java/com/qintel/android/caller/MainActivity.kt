package com.qintel.android.caller

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_CODE = 1

    private lateinit var simSpinner: Spinner
    private lateinit var phoneNumberEditText: EditText
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnManageStorage: Button
    private lateinit var btnManagePermissions: Button
    private lateinit var simNumber1EditText: EditText
    private lateinit var simNumber2EditText: EditText
    private lateinit var btnSaveSimNumbers: Button

    // Used to hold the sim index when requesting permissions
    private var pendingSimIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        phoneNumberEditText = findViewById(R.id.phoneNumberEditText)
        simSpinner = findViewById(R.id.simSpinner)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnManageStorage = findViewById(R.id.btnManageStorage)
        btnManagePermissions = findViewById(R.id.btnManagePermissions)
        simNumber1EditText = findViewById(R.id.simNumber1EditText)
        simNumber2EditText = findViewById(R.id.simNumber2EditText)
        btnSaveSimNumbers = findViewById(R.id.btnSaveSimNumbers)

        val simOptions = arrayOf("SIM 0", "SIM 1")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, simOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        simSpinner.adapter = adapter
        simSpinner.visibility = View.GONE // Hide spinner as it's not used for automatic calls

        findViewById<Button>(R.id.btnCall).setOnClickListener {
            makeCall() // Manual calls will use the spinner's value
        }

        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find and enable the 'Caller' service.", Toast.LENGTH_LONG).show()
        }

        btnManageStorage.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Toast.makeText(this, "This feature is for Android 11+.", Toast.LENGTH_SHORT).show()
            }
        }

        btnManagePermissions.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
            Toast.makeText(this, "Grant Phone and Files permissions here.", Toast.LENGTH_LONG).show()
        }

        btnSaveSimNumbers.setOnClickListener {
            saveSimNumbers()
        }

        loadSimNumbers()

        handleWorkIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent called.")
        handleWorkIntent(intent)
    }

    private fun handleWorkIntent(intent: Intent?) {
        if (intent?.hasExtra("workItem") == true) {
            val workItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra("workItem", WorkItem::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra("workItem") as? WorkItem
            }

            if (workItem != null) {
                Log.d("MainActivity", "Handling work: $workItem")
                val sharedPref = getSharedPreferences("CallAppPrefs", Context.MODE_PRIVATE)
                val sim1 = sharedPref.getString("simNumber1", "") ?: ""
                val simIndex = if (workItem.simCardName == sim1) 0 else 1

                phoneNumberEditText.setText(workItem.callSequance)
                makeCall(simIndex)
            }
        } else {
            // If not launched with a work item, start the polling service
            startCallWorkerService()
        }
    }

    private fun startCallWorkerService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                startService()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSIONS_REQUEST_CODE)
            }
        } else {
            startService()
        }
    }

    private fun startService() {
        val intent = Intent(this, CallWorkerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun loadSimNumbers() {
        val sharedPref = getSharedPreferences("CallAppPrefs", Context.MODE_PRIVATE)
        simNumber1EditText.setText(sharedPref.getString("simNumber1", ""))
        simNumber2EditText.setText(sharedPref.getString("simNumber2", ""))
    }

    private fun saveSimNumbers() {
        val sharedPref = getSharedPreferences("CallAppPrefs", Context.MODE_PRIVATE) ?: return
        with(sharedPref.edit()) {
            putString("simNumber1", simNumber1EditText.text.toString())
            putString("simNumber2", simNumber2EditText.text.toString())
            apply()
        }
        Toast.makeText(this, "SIM names saved!", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityButton()
        updateStorageButton()
    }

    private fun updateAccessibilityButton() {
        if (isAccessibilityServiceEnabled()) {
            btnEnableAccessibility.text = "Accessibility Service Enabled"
            btnEnableAccessibility.isEnabled = false
        } else {
            btnEnableAccessibility.text = "Enable Accessibility Service"
            btnEnableAccessibility.isEnabled = true
        }
    }

    private fun updateStorageButton() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                btnManageStorage.text = "All Files Access Enabled"
                btnManageStorage.isEnabled = false
            } else {
                btnManageStorage.text = "Enable All Files Access"
                btnManageStorage.isEnabled = true
            }
        } else {
            btnManageStorage.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${CallEndService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service, ignoreCase = true) == true
    }

    private fun makeCall(simIndexToUse: Int? = null) {
        val simIndex = simIndexToUse ?: simSpinner.selectedItemPosition

        val requiredPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.CALL_PHONE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (requiredPermissions.isNotEmpty()) {
            pendingSimIndex = simIndex
            ActivityCompat.requestPermissions(this, requiredPermissions.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        } else {
            startCall(simIndex)
        }
    }

    private fun startCall(simIndex: Int) {
        phoneNumberEditText.clearFocus() // Fix for WindowLeaked crash

        val rawPhone = phoneNumberEditText.text.toString()
        if (rawPhone.isEmpty()) {
            Toast.makeText(this, "Please enter a phone number", Toast.LENGTH_SHORT).show()
            return
        }
        val phone = rawPhone.replace("#", "%23")

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val phoneAccountHandleList: List<PhoneAccountHandle> = telecomManager.callCapablePhoneAccounts
            if (simIndex < phoneAccountHandleList.size) {
                intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandleList[simIndex])
            } else {
                Toast.makeText(this, "SIM at index $simIndex not found.", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Could not place call. Permission denied. Please grant permissions in Settings.", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                // This could be for notifications or for calling, handle appropriately
                if (permissions.contains(Manifest.permission.POST_NOTIFICATIONS)) {
                    startService()
                }
                pendingSimIndex?.let {
                    startCall(it)
                    pendingSimIndex = null
                }
            } else {
                Toast.makeText(this, "Required permissions were not granted.", Toast.LENGTH_LONG).show()
                pendingSimIndex = null
            }
        }
    }
}
