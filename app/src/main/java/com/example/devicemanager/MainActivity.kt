package com.example.devicemanager

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var ipTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 80, 50, 50)
        }

        ipTextView = TextView(this).apply {
            textSize = 20f
            setPadding(0, 0, 0, 40)
        }

        val btnRefresh = Button(this).apply {
            text = "Refresh IP"
            setOnClickListener {
                updateIpDisplay()
            }
        }

        val btnOpenAccessibility = Button(this).apply {
            text = "Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(ipTextView)
        layout.addView(btnRefresh)
        layout.addView(btnOpenAccessibility)
        setContentView(layout)

        updateIpDisplay()
    }

    override fun onResume() {
        super.onResume()
        updateIpDisplay()
    }

    private fun updateIpDisplay() {
        val ip = getDeviceIp()
        ipTextView.text = "Connect from Phone A at:\nhttp://$ip:8080"
    }

    private fun getDeviceIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // First check Wi-Fi interfaces (wlan0, etc.)
            for (intf in interfaces) {
                if (intf.isUp && !intf.isLoopback && intf.name.contains("wlan")) {
                    for (addr in Collections.list(intf.inetAddresses)) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: ""
                        }
                    }
                }
            }
            // Fallback for Hotspot or any other non-loopback active IPv4 interface
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
