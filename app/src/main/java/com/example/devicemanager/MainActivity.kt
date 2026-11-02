package com.example.devicemanager

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.InetAddress
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 80, 50, 50)
        }

        val ipTextView = TextView(this).apply {
            textSize = 18f
            text = "Connect from Phone A at:\nhttp://${getDeviceIp()}:8080"
        }

        val btnOpenAccessibility = Button(this).apply {
            text = "Enable Accessibility Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(ipTextView)
        layout.addView(btnOpenAccessibility)
        setContentView(layout)
    }

    private fun getDeviceIp(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            var ip = wifiManager.connectionInfo.ipAddress
            if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                ip = Integer.reverseBytes(ip)
            }
            val ipByteArray = java.math.BigInteger.valueOf(ip.toLong()).toByteArray()
            InetAddress.getByAddress(ipByteArray).hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            "Unknown IP"
        }
    }
}
