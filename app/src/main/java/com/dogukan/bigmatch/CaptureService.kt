package com.dogukan.bigmatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock

class CaptureService : Service() {
    companion object {
        const val START = "capture.start"
        const val STOP = "capture.stop"
        @Volatile var active: CaptureService? = null; private set
    }
    private val channel = "bigmatch_capture"
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var matches: List<Match> = emptyList()
    private var state = 0 // 0 = ready to scan, 1 = scanning, 2 = matched, 3 = tapping
    private var scanRequestedAt = 0L
    private var lastDiagnostic = 0L
    private var lastStatus = "Kartlar açılınca OKU"

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START || projection != null) return START_NOT_STICKY
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "Ekran eşleştirme", NotificationManager.IMPORTANCE_LOW))
        showStatus("Ekran yakalama hazırlanıyor")
        try {
            val permission = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
                else intent.getParcelableExtra("data") as? Intent
            if (permission == null) { stopSelf(); return START_NOT_STICKY }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(intent.getIntExtra("resultCode", 0), permission)
            projection = mp
            active = this
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(mainLooper))
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            worker = HandlerThread("CardImageReader").also { it.start() }
            handler = Handler(worker!!.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            display = mp.createVirtualDisplay("BigMatchCapture", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            showStatus("Kartlar açılınca OKU")
            handler?.postDelayed(::readFrame, 300)
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    fun updatePanel() {
        TapService.active?.updateUI(lastStatus, when(state) {
            1, 3 -> "BEKLE"
            2 -> "EŞLEŞTİR"
            else -> "OKU"
        })
    }

    fun onOverlayAction() {
        handler?.post {
            when(state) {
                0 -> {
                    matches = emptyList()
                    state = 1
                    scanRequestedAt = SystemClock.elapsedRealtime()
                    lastDiagnostic = 0L
                    showStatus("Semboller okunuyor", "BEKLE")
                }
                2 -> {
                    val tapper = TapService.active
                    if (tapper == null) { showStatus("Erişilebilirlik kapalı"); return@post }
                    state = 3
                    showStatus("8 çift dokunuluyor", "BEKLE")
                    tapper.tapPairs(matches) {
                        handler?.post {
                            state = 0
                            matches = emptyList()
                            showStatus("Bitti! Yeni tur için OKU", "OKU")
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun showStatus(status: String, action: String = when(state) {
        1, 3 -> "BEKLE"
        2 -> "EŞLEŞTİR"
        else -> "OKU"
    }) {
        lastStatus = status
        TapService.active?.updateUI(status, action)
        val stop = PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Big Match Yardımcısı")
            .setContentText(status)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .build()
        startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun frame(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val stride = plane.pixelStride; val rowStride = plane.rowStride
            val padded = Bitmap.createBitmap(image.width + (rowStride-stride*image.width)/stride,
                image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            result
        } catch (_: Exception) { null } finally { image.close() }
    }

    private fun readFrame() {
        if (projection == null) return
        val bitmap = frame()
        if (bitmap != null) {
            try {
                if (state == 1) processScan(bitmap)
            } catch (_: Exception) {
                state = 0
                showStatus("Okuma hatası, tekrar OKU")
            } finally { bitmap.recycle() }
        }
        handler?.postDelayed(::readFrame, 180)
    }

    private fun processScan(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (now - scanRequestedAt > 4000L) {
            state = 0
            showStatus("Okunamadı, tekrar OKU")
            return
        }
        val cards = CardVision.findCards(bitmap)
        if (cards.size == 16) {
            val solved = CardVision.solve(bitmap, cards)
            if (solved != null) {
                matches = solved
                state = 2
                showStatus("8 çift hazır • Kapanınca bas", "EŞLEŞTİR")
                return
            }
        }
        if (now - lastDiagnostic > 900L) {
            val status = if (cards.size == 16) "16 kart var, simgeler belirsiz"
                else "Kart aranıyor: ${cards.size}/16"
            showStatus(status, "BEKLE")
            lastDiagnostic = now
        }
    }

    override fun onDestroy() {
        TapService.active?.cancelTaps()
        active = null
        TapService.active?.updateUI("Ekran yakalama kapalı", "OKU")
        display?.release()
        reader?.close()
        projection?.stop()
        projection = null
        worker?.quitSafely()
        super.onDestroy()
    }
}
