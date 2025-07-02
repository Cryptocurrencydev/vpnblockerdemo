package com.example.vpnblockerdemo

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var isRunning = false
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)

        toggleButton.setOnClickListener {
            if (!isRunning) {
                val intent = VpnService.prepare(this)
                if (intent != null) {
                    startActivityForResult(intent, 0)
                } else {
                    onActivityResult(0, Activity.RESULT_OK, null)
                }
            } else {
                stopService(Intent(this, DnsVpnService::class.java))
                isRunning = false
                statusText.text = "VPN Stopped"
                toggleButton.text = "Start VPN"
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            startService(Intent(this, DnsVpnService::class.java))
            isRunning = true
            statusText.text = "VPN Running"
            toggleButton.text = "Stop VPN"
        }
        super.onActivityResult(requestCode, resultCode, data)
    }
}
