package com.phonefinder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.phonefinder.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy {
        getSharedPreferences("phonefinder_prefs", MODE_PRIVATE)
    }

    // Show "Stop Alarm" button when the service broadcasts that the alarm is firing
    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            binding.stopAlarmButton.visibility = View.VISIBLE
        }
    }

    private val requiredPermissions get() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startListenerService()
            promptBatteryOptimizationIfNeeded()
        } else {
            binding.statusText.text = getString(R.string.permission_rationale)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            if (prefs.getBoolean("service_enabled", false)) {
                disableService()
            } else {
                checkPermissionsAndEnable()
            }
        }

        binding.stopAlarmButton.setOnClickListener {
            sendBroadcast(Intent("com.phonefinder.STOP_ALARM"))
            binding.stopAlarmButton.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
        registerReceiver(alarmReceiver, IntentFilter("com.phonefinder.ALARM_FIRING"),
            RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(alarmReceiver)
    }

    private fun checkPermissionsAndEnable() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startListenerService()
            promptBatteryOptimizationIfNeeded()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startListenerService() {
        prefs.edit().putBoolean("service_enabled", true).apply()
        val intent = Intent(this, PhoneFinderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUiState()
    }

    private fun disableService() {
        prefs.edit().putBoolean("service_enabled", false).apply()
        startService(Intent(this, PhoneFinderService::class.java).apply {
            action = PhoneFinderService.ACTION_STOP
        })
        binding.stopAlarmButton.visibility = View.GONE
        updateUiState()
    }

    private fun updateUiState() {
        val enabled = prefs.getBoolean("service_enabled", false)
        binding.toggleButton.text = if (enabled) getString(R.string.btn_disable)
                                    else getString(R.string.btn_enable)
        binding.statusText.text = if (enabled) getString(R.string.status_listening)
                                  else getString(R.string.status_off)
    }

    private fun promptBatteryOptimizationIfNeeded() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_opt_title))
            .setMessage(getString(R.string.battery_opt_message))
            .setPositiveButton(getString(R.string.battery_opt_go)) { _, _ ->
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            .setNegativeButton(getString(R.string.battery_opt_skip), null)
            .show()
    }
}
