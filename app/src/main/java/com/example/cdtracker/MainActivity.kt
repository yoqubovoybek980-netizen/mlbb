package com.example.cdtracker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

class MainActivity : Activity() {
    private var asked = false

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Panel tayyor. MLBB'ni och.", Toast.LENGTH_LONG).show()
            finish()
        } else if (!asked) {
            asked = true
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            finish()
        }
    }
}
