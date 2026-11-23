package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.KartiApplication
import com.example.MainActivity
import com.example.R
import com.example.utils.PermissionHelper
import kotlin.math.abs

class OverlayBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleRoot: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startBubbleForeground()
        if (!PermissionHelper.isOverlayGranted(this)) {
            stopSelf()
            return
        }
        CardDispatchCoordinator.setBubbleRunning(true)
        setupBubbleWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val networkName = intent?.getStringExtra(EXTRA_NETWORK_NAME).orEmpty()
        if (networkName.isNotBlank()) {
            CardDispatchCoordinator.updateDetectedNetwork(this, networkName)
        }
        if (bubbleRoot == null && PermissionHelper.isOverlayGranted(this)) {
            setupBubbleWindow()
        }
        return START_STICKY
    }

    private fun startBubbleForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, OverlayBubbleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, KartiApplication.CHANNEL_BUBBLE)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.bubble_notif_title))
            .setContentText(getString(R.string.bubble_notif_text))
            .setContentIntent(openAppIntent)
            .addAction(0, "إخفاء الأيقونة", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val bubbleSizePx = (60 * density).toInt()
        val closeSizePx = (22 * density).toInt()
        val wrapperSizePx = (68 * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels

        val root = FrameLayout(this)

        val circleBg = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#00E676"), Color.parseColor("#00B0FF"))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke((2.5f * density).toInt(), Color.parseColor("#07111E"))
        }

        val innerCircle = FrameLayout(this).apply {
            background = circleBg
            elevation = 10f * density
        }

        val cameraIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setColorFilter(Color.parseColor("#07111E"))
            val pad = (14 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        innerCircle.addView(
            cameraIcon,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val bubbleParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
        root.addView(innerCircle, bubbleParams)

        // Small dismiss badge on top-end corner
        val closeBadge = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D32F2F"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#07111E"))
            }
            val p = (3 * density).toInt()
            setPadding(p, p, p, p)
            setOnClickListener { stopSelf() }
        }
        val closeParams = FrameLayout.LayoutParams(closeSizePx, closeSizePx).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        root.addView(closeBadge, closeParams)

        val params = WindowManager.LayoutParams(
            wrapperSizePx,
            wrapperSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - wrapperSizePx - (12 * density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        innerCircle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (initialX + dx).coerceIn(0, screenWidth - wrapperSizePx)
                        params.y = (initialY + dy).coerceIn(
                            (24 * density).toInt(),
                            resources.displayMetrics.heightPixels - wrapperSizePx - (48 * density).toInt()
                        )
                        runCatching { windowManager?.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onBubbleTapped()
                    } else {
                        // Snap to nearest horizontal edge
                        val center = params.x + wrapperSizePx / 2
                        params.x = if (center < screenWidth / 2) {
                            (8 * density).toInt()
                        } else {
                            screenWidth - wrapperSizePx - (8 * density).toInt()
                        }
                        runCatching { windowManager?.updateViewLayout(root, params) }
                    }
                    true
                }
                else -> false
            }
        }

        bubbleRoot = root
        layoutParams = params
        runCatching { windowManager?.addView(root, params) }
    }

    private fun onBubbleTapped() {
        if (!PermissionHelper.isCameraGranted(this)) {
            Toast.makeText(this, "يرجى منح صلاحية الكاميرا أولاً", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        val scannerIntent = Intent(this, ScannerOverlayService::class.java)
        runCatching {
            ContextCompat.startForegroundService(this, scannerIntent)
        }.onFailure {
            // Fallback to opening MainActivity in-app scanner if OEM blocks background camera FGS
            val fallback = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_OPEN_IN_APP_SCANNER, true)
            }
            startActivity(fallback)
        }
    }

    override fun onDestroy() {
        CardDispatchCoordinator.setBubbleRunning(false)
        bubbleRoot?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        bubbleRoot = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.example.action.STOP_BUBBLE"
        const val EXTRA_NETWORK_NAME = "extra_network_name"
        private const val NOTIFICATION_ID = 4001
    }
}
