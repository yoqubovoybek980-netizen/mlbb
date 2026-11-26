package com.example.cdtracker

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

class MainActivity : Activity() {
    private var askedOverlay = false
    private var askedCapture = false
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (started) return

        // 1) "Boshqa ilovalar ustida chizish" ruxsati
        if (!Settings.canDrawOverlays(this)) {
            if (!askedOverlay) {
                askedOverlay = true
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                finish()
            }
            return
        }

        // 2) Ekranni yozib olish ruxsati (MediaProjection)
        if (!askedCapture) {
            askedCapture = true
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100) {
            if (resultCode == RESULT_OK && data != null) {
                started = true
                stopService(Intent(this, OverlayService::class.java))
                val i = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                startForegroundService(i)
                Toast.makeText(this, "Panel ishga tushdi. MLBB'ni och.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Ekranni yozishga ruxsat berilmadi", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}
