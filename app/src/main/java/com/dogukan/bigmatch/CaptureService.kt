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
    companion object { const val START = "capture.start"; const val STOP = "capture.stop" }
    private val channel = "bigmatch_capture"
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var original: Bitmap? = null
    private var cards: List<Card> = emptyList()
    private var pairs: List<Match> = emptyList()
    private var state = 0 // 0 = scan; 1 = await hidden; 2 = tapping; 3 = completed
    private var firstSeen = 0L
    private var lastChecked = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action != START || projection != null) return START_NOT_STICKY
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "Ekran eşleştirme", NotificationManager.IMPORTANCE_LOW))
        showNotification("Kartlar bekleniyor")
        try {
            val permission = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
                else intent.getParcelableExtra("data") as? Intent
            if (permission == null) { stopSelf(); return START_NOT_STICKY }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(intent.getIntExtra("resultCode", 0), permission)
            projection = mp
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
            handler?.postDelayed(::scanFrame, 250)
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun showNotification(status: String) {
        val stop = PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Big Match Yardımcısı")
            .setContentText(status)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(101, notification)
    }

    private fun frame(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val w = image.width; val h = image.height
            val stride = plane.pixelStride; val rowStride = plane.rowStride
            val padded = Bitmap.createBitmap(w + (rowStride - stride*w)/stride, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(buffer)
            val result = Bitmap.createBitmap(padded, 0, 0, w, h)
            padded.recycle()
            result
        } catch (_: Exception) { null } finally { image.close() }
    }

    private fun scanFrame() {
        if (projection == null) return
        val bitmap = frame()
        if (bitmap != null) {
            try { process(bitmap) } catch (_: Exception) { state = 0; original?.recycle(); original = null }
            finally { if (bitmap !== original) bitmap.recycle() }
        }
        handler?.postDelayed(::scanFrame, 220)
    }

    private fun process(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastChecked < 400 || state >= 2) return
        lastChecked = now
        if (state == 0) {
            val detected = CardVision.findCards(bitmap)
            if (detected.size != 16) return
            val solved = CardVision.solve(bitmap, detected) ?: return
            cards = detected
            pairs = solved
            original?.recycle()
            original = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            firstSeen = now
            state = 1
            showNotification("8 eşleşme bulundu, kartların kapanması bekleniyor")
        } else if (state == 1) {
            if (now - firstSeen > 12_000L) {
                state = 0; original?.recycle(); original = null; showNotification("Zaman aşımı; kartlar yeniden taranıyor")
                return
            }
            // Prevent accidental taps on initially visible faces. Detect a meaningful icon change.
            if (now - firstSeen > 700 && original?.let { CardVision.looksTurnedOver(bitmap, cards, it) } == true) {
                state = 2
                original?.recycle(); original = null
                val tapper = TapService.active
                if (tapper == null) { state = 0; showNotification("Erişilebilirlik hizmetini etkinleştirin"); return }
                showNotification("Kartlar sırayla eşleştiriliyor")
                tapper.tapPairs(pairs) { state = 3; showNotification("16 karta dokunuldu — tamamlandı") }
            }
        }
    }

    override fun onDestroy() {
        TapService.active?.cancelTaps()
        original?.recycle(); original = null
        display?.release(); display = null
        reader?.close(); reader = null
        val p = projection; projection = null; p?.stop()
        worker?.quitSafely(); worker = null; handler = null
        super.onDestroy()
    }
}
