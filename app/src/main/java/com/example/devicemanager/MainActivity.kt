package com.example.devicemanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var ipTextView: TextView

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 80, 50, 50)
        }

        ipTextView = TextView(this).apply {
            textSize = 20f
            text = "Remote Controller Active\n\nOpen on Phone A:\nhttp://${getDeviceIp()}:8080"
            setPadding(0, 0, 0, 40)
        }

        val btnRequestPerms = Button(this).apply {
            text = "1. Grant SMS & Call Permissions"
            setOnClickListener { checkAndRequestPermissions() }
        }

        val btnNotificationAccess = Button(this).apply {
            text = "2. Enable Notification Listener"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val btnAccessibilityAccess = Button(this).apply {
            text = "3. Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(ipTextView)
        layout.addView(btnRequestPerms)
        layout.addView(btnNotificationAccess)
        layout.addView(btnAccessibilityAccess)
        setContentView(layout)

        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        ipTextView.text = "Remote Controller Active\n\nOpen on Phone A:\nhttp://${getDeviceIp()}:8080"
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    private fun getDeviceIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }
}
