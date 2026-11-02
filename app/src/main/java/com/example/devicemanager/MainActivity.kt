package com.example.devicemanager

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var compName: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        compName = ComponentName(this, AdminReceiver::class.java)

        val btnEnable = findViewById<Button>(R.id.btnEnableAdmin)
        val btnLock = findViewById<Button>(R.id.btnLockScreen)

        btnEnable.setOnClickListener {
            if (!devicePolicyManager.isAdminActive(compName)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Administrative privileges are required to lock this device.")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Admin is already active", Toast.LENGTH_SHORT).show()
            }
        }

        btnLock.setOnClickListener {
            if (devicePolicyManager.isAdminActive(compName)) {
                devicePolicyManager.lockNow()
            } else {
                Toast.makeText(this, "Please enable admin privileges first", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
