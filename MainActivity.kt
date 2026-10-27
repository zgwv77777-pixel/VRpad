package com.example.vrpad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.graphics.*
import android.hardware.*
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.*

class StickView(c: Context, val cb: (Float, Float) -> Unit) : View(c) {
    private var kx = 0f; private var ky = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val r = min(width, height) / 2f
        p.color = 0xFF333333.toInt(); cv.drawCircle(width / 2f, height / 2f, r, p)
        p.color = 0xFF88AAFF.toInt()
        cv.drawCircle(width / 2f + kx * r * 0.7f, height / 2f + ky * r * 0.7f, r * 0.3f, p)
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            kx = 0f; ky = 0f
        } else {
            val r = min(width, height) / 2f
            var dx = (e.x - width / 2f) / (r * 0.7f)
            var dy = (e.y - height / 2f) / (r * 0.7f)
            val l = hypot(dx, dy); if (l > 1f) { dx /= l; dy /= l }
            kx = dx; ky = dy
        }
        invalidate(); cb(kx, ky); return true
    }
}

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class MainActivity : AppCompatActivity(), SensorEventListener {
    // 16 buttons (2 bytes) + X,Y,Z,Rz signed 8-bit = 6 bytes report, id 0
    private val descriptor = byteArrayOf(
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
        0xC0.toByte()
    )

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var buttons = 0
    private var lx = 0f; private var ly = 0f
    private var sx = 0f; private var sy = 0f
    private var gx = 0f; private var gy = 0f
    private var gyroOn = false
    private val gyroGain = 0.6f
    private var lastSend = 0L
    private lateinit var status: TextView
    private lateinit var devRow: LinearLayout
    private lateinit var sm: SensorManager

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = "pair VR phone in bluetooth settings first, then pick it:" }
        devRow = LinearLayout(this)
        val hs = HorizontalScrollView(this).apply { addView(devRow) }
        val gyroBtn = ToggleButton(this).apply {
            textOn = "gyro ON"; textOff = "gyro OFF"; isChecked = false
            setOnCheckedChangeListener { _, on -> gyroOn = on; if (!on) { gx = 0f; gy = 0f }; send() }
        }
        val pad = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val w = (resources.displayMetrics.density * 170).toInt()
        val left = StickView(this) { x, y -> lx = x; ly = y; send() }
        val right = StickView(this) { x, y -> sx = x; sy = y; send() }
        val grid = GridLayout(this).apply { columnCount = 2 }
        listOf("A", "B", "X", "Y", "L1", "R1", "SEL", "START").forEachIndexed { i, n ->
            grid.addView(Button(this).apply {
                text = n
                setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> buttons = buttons or (1 shl i)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> buttons = buttons and (1 shl i).inv()
                    }
                    send(); true
                }
            })
        }
        pad.addView(left, LinearLayout.LayoutParams(w, w))
        pad.addView(grid, LinearLayout.LayoutParams(0, -2, 1f))
        pad.addView(right, LinearLayout.LayoutParams(w, w))
        root.addView(status); root.addView(hs); root.addView(gyroBtn)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != 0)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        else initHid()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (r.isNotEmpty() && r[0] == 0) initHid() else status.text = "bluetooth permission denied"
    }

    private fun initHid() {
        val ad = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ad.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(pr: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "VRPad", "gamepad", "vrpad",
                    BluetoothHidDevice.SUBCLASS1_COMBO, descriptor)
                h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(),
                    object : BluetoothHidDevice.Callback() {
                        override fun onConnectionStateChanged(d: BluetoothDevice, st: Int) {
                            runOnUiThread {
                                if (st == BluetoothProfile.STATE_CONNECTED) {
                                    host = d; status.text = "connected: ${d.name}"
                                } else if (host == d) { host = null; status.text = "disconnected" }
                            }
                        }
                    })
                ad.bondedDevices.forEach { d ->
                    devRow.addView(Button(this@MainActivity).apply {
                        text = d.name
                        setOnClickListener { h.connect(d); status.text = "connecting ${d.name}..." }
                    })
                }
            }
            override fun onServiceDisconnected(pr: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun b(f: Float) = (f.coerceIn(-1f, 1f) * 127).toInt().toByte()

    private fun send() {
        val h = hid ?: return
        val d = host ?: return
        val now = System.nanoTime()
        if (now - lastSend < 8_000_000L) return
        lastSend = now
        val rx = if (sx != 0f || sy != 0f) sx else gx
        val ry = if (sx != 0f || sy != 0f) sy else gy
        val r = byteArrayOf(
            (buttons and 0xFF).toByte(), ((buttons shr 8) and 0xFF).toByte(),
            b(lx), b(ly), b(rx), b(ry)
        )
        h.sendReport(d, 0, r)
    }

    override fun onResume() {
        super.onResume()
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }
    override fun onPause() { super.onPause(); sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        if (!gyroOn) return
        gx = (-e.values[1] * gyroGain).coerceIn(-1f, 1f)
        gy = (-e.values[0] * gyroGain).coerceIn(-1f, 1f)
        send()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
