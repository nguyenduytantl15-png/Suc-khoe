package vn.suckhoe.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range

/**
 * Đo nhịp tim bằng phương pháp PPG: bật đèn flash, ngón tay che camera sau,
 * độ sáng ảnh thay đổi theo lượng máu ở đầu ngón tay sau mỗi nhịp tim.
 */
class HeartRateMonitor(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onSample(value: Double, fingerOn: Boolean, elapsedSec: Double)
        fun onLive(bpm: Int, quality: Double)
        fun onDone(result: SignalProc.Result?)
        fun onError(msg: String)
    }

    companion object {
        const val DURATION_SEC = 25.0
        private const val WARMUP_FRAMES = 30
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest.Builder? = null

    @Volatile private var running = false
    private var fingerFrames = 0
    private var startNs = 0L
    private var lastLiveNs = 0L
    private var aeLocked = false
    private val times = ArrayList<Long>()
    private val vals = ArrayList<Double>()

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (id == null) { listener.onError("Không tìm thấy camera sau."); return }
            val ch = cm.getCameraCharacteristics(id)
            val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            if (sizes == null || sizes.isEmpty()) { listener.onError("Camera không hỗ trợ định dạng ảnh cần thiết."); return }
            val size = sizes.filter { it.width >= 160 && it.height >= 120 }.minByOrNull { it.width * it.height } ?: sizes[0]
            val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val fps: Range<Int>? = fpsRanges?.filter { it.upper <= 30 }?.maxWithOrNull(compareBy<Range<Int>>({ it.upper }, { it.lower }))

            running = true
            fingerFrames = 0; startNs = 0L; lastLiveNs = 0L; aeLocked = false
            times.clear(); vals.clear()

            val th = HandlerThread("hr-camera")
            th.start()
            thread = th
            val h = Handler(th.looper)
            handler = h

            val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = rd
            rd.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (e: Exception) { null }
                if (img != null) {
                    try { if (running) process(img) } catch (e: Exception) { } finally { img.close() }
                }
            }, h)

            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    if (!running) { c.close(); return }
                    camera = c
                    createSession(c, rd, hasFlash, fps)
                }
                override fun onDisconnected(c: CameraDevice) { c.close(); camera = null }
                override fun onError(c: CameraDevice, error: Int) {
                    c.close(); camera = null
                    fail("Không mở được camera (mã lỗi $error). Hãy đóng các app đang dùng camera rồi thử lại.")
                }
            }, h)
        } catch (e: Exception) {
            fail("Lỗi camera: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(c: CameraDevice, rd: ImageReader, flash: Boolean, fps: Range<Int>?) {
        try {
            c.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (!running) return
                    session = s
                    try {
                        val b = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(rd.surface)
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        if (fps != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                        if (flash) b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                        request = b
                        s.setRepeatingRequest(b.build(), null, handler)
                    } catch (e: Exception) {
                        fail("Không khởi động được camera: ${e.message}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { fail("Không cấu hình được camera.") }
            }, handler)
        } catch (e: Exception) {
            fail("Không khởi động được camera: ${e.message}")
        }
    }

    /** Khóa phơi sáng/cân bằng trắng sau khi đặt ngón tay để tín hiệu không bị nhảy. */
    private fun lockExposure() {
        val b = request ?: return
        val s = session ?: return
        try {
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            s.setRepeatingRequest(b.build(), null, handler)
            aeLocked = true
        } catch (e: Exception) { }
    }

    private fun unlockExposure() {
        val b = request ?: return
        val s = session ?: return
        try {
            b.set(CaptureRequest.CONTROL_AE_LOCK, false)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            s.setRepeatingRequest(b.build(), null, handler)
            aeLocked = false
        } catch (e: Exception) { }
    }

    private fun planeMean(p: Image.Plane, w: Int, h: Int, step: Int): Double {
        val buf = p.buffer
        val rs = p.rowStride
        val ps = p.pixelStride
        val limit = buf.limit()
        var sum = 0L
        var cnt = 0
        var y = 0
        while (y < h) {
            var x = 0
            val row = y * rs
            while (x < w) {
                val idx = row + x * ps
                if (idx < limit) { sum += (buf.get(idx).toInt() and 0xFF); cnt++ }
                x += step
            }
            y += step
        }
        return if (cnt == 0) 0.0 else sum.toDouble() / cnt
    }

    private fun process(img: Image) {
        val w = img.width
        val h = img.height
        val planes = img.planes
        val yMean = planeMean(planes[0], w, h, 4)
        val uMean = planeMean(planes[1], w / 2, h / 2, 2)
        val vMean = planeMean(planes[2], w / 2, h / 2, 2)
        val red = yMean + 1.402 * (vMean - 128)
        val green = yMean - 0.344 * (uMean - 128) - 0.714 * (vMean - 128)
        // Ngón tay che camera + flash => ảnh đỏ rực, kênh xanh lá rất thấp
        val finger = red > 60 && green < red * 0.6
        val ts = img.timestamp

        if (!finger) {
            if (fingerFrames > 0 || startNs != 0L) {
                fingerFrames = 0; startNs = 0L; lastLiveNs = 0L
                times.clear(); vals.clear()
                if (aeLocked) unlockExposure()
            }
            main.post { listener.onSample(0.0, false, 0.0) }
            return
        }
        fingerFrames++
        val value = -yMean   // máu nhiều -> tối hơn; đảo dấu để đỉnh = nhịp đập
        if (fingerFrames == 15 && !aeLocked) lockExposure()
        if (fingerFrames < WARMUP_FRAMES) {
            main.post { listener.onSample(value, true, 0.0) }
            return
        }
        if (startNs == 0L) startNs = ts
        times.add(ts); vals.add(value)
        val elapsed = (ts - startNs) / 1e9
        main.post { listener.onSample(value, true, elapsed) }

        if (elapsed >= 6.0 && ts - lastLiveNs > 1_000_000_000L) {
            lastLiveNs = ts
            val r = SignalProc.estimate(times, vals, 12.0)
            if (r != null) main.post { listener.onLive(r.bpm, r.quality) }
        }
        if (elapsed >= DURATION_SEC) {
            running = false
            val r = SignalProc.estimate(times, vals)
            main.post {
                stop()
                listener.onDone(r)
            }
        }
    }

    private fun fail(msg: String) {
        running = false
        main.post {
            stop()
            listener.onError(msg)
        }
    }

    fun stop() {
        running = false
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { camera?.close() } catch (e: Exception) { }
        camera = null
        val rd = reader
        reader = null
        val th = thread
        thread = null
        handler = null
        request = null
        // Đóng ImageReader trên luồng camera để không đụng ảnh đang xử lý
        if (th != null) {
            Handler(th.looper).post {
                try { rd?.close() } catch (e: Exception) { }
                th.quitSafely()
            }
        } else {
            try { rd?.close() } catch (e: Exception) { }
        }
    }
}
