package com.dogukan.bigmatch

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup

class MainActivity : Activity() {
    private val captureCode = 1402
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 65, 36, 32) }
        val info = TextView(this).apply {
            text = "Big Match Yardımcısı\n\n1. Erişilebilirlik hizmetini etkinleştirin.\n2. Ekran yakalama izni verin.\n3. Oyuna geçin; kartlar görünürken otomatik eşleştirme başlayacak.\n\nKartlar: 4×4, açık kalma: 5 saniye."
            textSize = 18f
        }
        root.addView(info)
        fun add(label: String, click: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { click() } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        add("1. Erişilebilirlik iznini aç") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        add("2. Ekran yakalamayı başlat") {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), captureCode)
        }
        add("Durdur") { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.STOP)) }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1207)
        }
    }

    @Deprecated("Activity result API for dependency-free build")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureCode && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.START
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            })
        }
    }
}
