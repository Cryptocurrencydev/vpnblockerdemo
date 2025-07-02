// file: LogHelper.kt
package com.example.vpnblockerdemo

import android.util.Log

object LogHelper {
    private const val TAG = "DnsVpn"
    fun log(msg: String) {
        Log.d(TAG, msg)
    }
}
