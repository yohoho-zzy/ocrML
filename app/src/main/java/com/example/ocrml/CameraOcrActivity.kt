package com.example.ocrml

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CameraOcrActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener {

    private lateinit var textureView: TextureView
    private lateinit var textView: TextView
    private lateinit var overlay: OverlayView
    private lateinit var rescanButton: Button
    private lateinit var versionText: TextView

    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var backgroundHandler: Handler

    // ── プレビュー用のサイズ（SurfaceTexture と一致） ──
    private lateinit var previewSize: Size

    // ── センサー物理向き ──
    private var sensorOrientation: Int = 0

    @Volatile private var isProcessing = false
    @Volatile private var scanningActive = true

    // ── デフォルトでデバッグ表示を ON（ボタンが出なくても見えるように）──
    @Volatile private var debugOverlayEnabled = false

    // ── ML Kit 日本語OCR クライアント ──
    private val recognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    // ── 履歴（氏名・生年月日・住所・交付日・有効期限・番号）──
    private val history = Array(6) { mutableListOf<String>() }
    private val HISTORY_LIMIT = 5

    @SuppressLint("SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_ocr)

        textureView = findViewById(R.id.preview)
        textView = findViewById(R.id.result)
        overlay = findViewById(R.id.ocr_area)
        rescanButton = findViewById(R.id.rescan_button)
        versionText = findViewById(R.id.version_text)
        versionText.text = "v${BuildConfig.VERSION_NAME}"

        // ── 再スキャン（履歴と表示のリセット） ──
        rescanButton.setOnClickListener {
            scanningActive = true
            isProcessing = false
            textView.text = ""
            rescanButton.visibility = View.GONE
            for (list in history) list.clear()
        }

        // ── 長押しでデバッグ可視化の ON/OFF を切替（任意）──
        rescanButton.setOnLongClickListener {
            debugOverlayEnabled = !debugOverlayEnabled
            overlay.setDebugEnabled(debugOverlayEnabled)
            if (!debugOverlayEnabled) overlay.setDebugRect(null)
            textView.append("\n[debug=${if (debugOverlayEnabled) "ON" else "OFF"}]")
            true
        }

        // ── デフォルトでデバッグ可視化を ON にして、まずは緑枠と同じ位置に青枠を出す ──
        overlay.post {
            overlay.setDebugEnabled(true)
            overlay.setDebugRect(overlay.getBoxRect())
        }

        // ── バックグラウンドスレッド（Camera2 コールバック用） ──
        val handlerThread = HandlerThread("CameraBackground")
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)

        if (allPermissionsGranted()) {
            textureView.post { openCamera() }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
        }
    }

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            textureView.post { openCamera() }
        } else {
            finish()
        }
    }

    // ── TextureView に最適なプレビューサイズ ──
    private fun chooseOptimalPreviewSize(
        choices: Array<Size>, viewW: Int, viewH: Int, swapped: Boolean
    ): Size {
        val targetW = if (swapped) viewH else viewW
        val targetH = if (swapped) viewW else viewH
        val targetRatio = targetW.toFloat() / targetH
        return choices.minByOrNull { s ->
            val ratio = s.width.toFloat() / s.height
            val ratioDiff = abs(ratio - targetRatio)
            val tooSmall = if (s.width < targetW || s.height < targetH) 0.2f else 0f
            ratioDiff + tooSmall
        } ?: choices.first()
    }

    // ── TextureView の等比スケール＋回転（Camera2Basic 相当）──
    private fun configureTransform(viewW: Int, viewH: Int) {
        if (!::previewSize.isInitialized) return

        val rotation = windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        when (rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                val bufferRect = RectF(
                    0f, 0f,
                    previewSize.height.toFloat(),
                    previewSize.width.toFloat()
                )
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = max(
                    viewH.toFloat() / previewSize.height.toFloat(),
                    viewW.toFloat() / previewSize.width.toFloat()
                )
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(90f * (rotation - 2), centerX, centerY)
            }
            Surface.ROTATION_180 -> {
                matrix.postRotate(180f, centerX, centerY)
            }
            else -> { /* ROTATION_0: 何もしない（縦向きは既存の完璧な見た目を維持） */ }
        }
        textureView.setTransform(matrix)
    }

    // ── カメラを開く（SurfaceTexture と ImageReader を previewSize に統一）──
    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first()

        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val rotation = windowManager.defaultDisplay.rotation
        val swapped = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> (sensorOrientation == 90 || sensorOrientation == 270)
            Surface.ROTATION_90, Surface.ROTATION_270 -> (sensorOrientation == 0 || sensorOrientation == 180)
            else -> false
        }

        val viewW = textureView.width
        val viewH = textureView.height
        val previewChoices = map.getOutputSizes(SurfaceTexture::class.java)
        previewSize = chooseOptimalPreviewSize(previewChoices, viewW, viewH, swapped)

        val surfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        configureTransform(viewW, viewH)

        imageReader = ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2
        )
        imageReader.setOnImageAvailableListener(this, backgroundHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(previewSize)
            }
            override fun onDisconnected(device: CameraDevice) { device.close() }
            override fun onError(device: CameraDevice, error: Int) { device.close() }
        }, backgroundHandler)
    }

    private fun createSession(size: Size) {
        val surfaceTexture = textureView.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(surfaceTexture)

        previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequestBuilder.addTarget(surface)
        previewRequestBuilder.addTarget(imageReader.surface)

        cameraDevice.createCaptureSession(listOf(surface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    configureTransform(textureView.width, textureView.height)
                    captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
    }

    // ── ML Kit に渡す回転角（背面カメラ想定）──
    private fun computeRotationDegrees(): Int {
        val rotation = windowManager.defaultDisplay.rotation
        val deviceRotation = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - deviceRotation + 360) % 360
    }

    // ─────────────────────────────────────────────────────────────
    //  ビュー矩形 → 「未回転のプレビューバッファ」への厳密写像（横向き専用）
    // ─────────────────────────────────────────────────────────────
    private fun mapViewRectToUnrotatedBufferExact(rectInView: Rect): Rect {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        val W = previewSize.width.toFloat()   // 未回転バッファ幅
        val H = previewSize.height.toFloat()  // 未回転バッファ高

        val rotation = windowManager.defaultDisplay.rotation
        require(rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)

        val rotW = H
        val rotH = W
        val s = max(viewW / rotW, viewH / rotH)
        val offsetX = (viewW - s * rotW) / 2f
        val offsetY = (viewH - s * rotH) / 2f

        fun viewToRotated(xv: Float, yv: Float): Pair<Float, Float> {
            val xr = (xv - offsetX) / s
            val yr = (yv - offsetY) / s
            return xr to yr
        }
        fun rotatedToUnrotated(xr: Float, yr: Float): Pair<Float, Float> {
            return if (rotation == Surface.ROTATION_90) {
                val xu = W - yr
                val yu = xr
                xu to yu
            } else {
                val xu = yr
                val yu = H - xr
                xu to yu
            }
        }

        val corners = arrayOf(
            rectInView.left.toFloat() to rectInView.top.toFloat(),
            rectInView.right.toFloat() to rectInView.top.toFloat(),
            rectInView.right.toFloat() to rectInView.bottom.toFloat(),
            rectInView.left.toFloat() to rectInView.bottom.toFloat()
        )

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        for ((xv, yv) in corners) {
            val (xr, yr) = viewToRotated(xv, yv)
            val xrC = min(max(xr, 0f), rotW)
            val yrC = min(max(yr, 0f), rotH)
            val (xu, yu) = rotatedToUnrotated(xrC, yrC)
            if (xu < minX) minX = xu
            if (yu < minY) minY = yu
            if (xu > maxX) maxX = xu
            if (yu > maxY) maxY = yu
        }

        minX = min(max(minX, 0f), W)
        minY = min(max(minY, 0f), H)
        maxX = min(max(maxX, 0f), W)
        maxY = min(max(maxY, 0f), H)

        val l = (minX.roundToInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(0)
        val t = (minY.roundToInt() and 0xFFFFFFFE.toInt()).coerceAtLeast(0)
        var r = (maxX.roundToInt() and 0xFFFFFFFE.toInt()).coerceAtMost(W.toInt())
        var b = (maxY.roundToInt() and 0xFFFFFFFE.toInt()).coerceAtMost(H.toInt())
        if (r <= l) r = (l + 2).coerceAtMost(W.toInt())
        if (b <= t) b = (t + 2).coerceAtMost(H.toInt())
        return Rect(l, t, r, b)
    }

    // ─────────────────────────────────────────────────────────────
    //  「未回転バッファの矩形」→ ビュー矩形 への正方向写像（デバッグ描画用）
    // ─────────────────────────────────────────────────────────────
    private fun unrotatedBufferRectToViewRectExact(buf: Rect): Rect {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        val W = previewSize.width.toFloat()
        val H = previewSize.height.toFloat()

        val rotation = windowManager.defaultDisplay.rotation
        require(rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)

        val rotW = H
        val rotH = W
        val s = max(viewW / rotW, viewH / rotH)
        val offsetX = (viewW - s * rotW) / 2f
        val offsetY = (viewH - s * rotH) / 2f

        fun unrotatedToRotated(xu: Float, yu: Float): Pair<Float, Float> {
            return if (rotation == Surface.ROTATION_90) {
                val xr = yu
                val yr = W - xu
                xr to yr
            } else {
                val xr = H - yu
                val yr = xu
                xr to yr
            }
        }
        fun rotatedToView(xr: Float, yr: Float): Pair<Float, Float> {
            val xv = xr * s + offsetX
            val yv = yr * s + offsetY
            return xv to yv
        }

        val corners = arrayOf(
            buf.left.toFloat() to buf.top.toFloat(),
            buf.right.toFloat() to buf.top.toFloat(),
            buf.right.toFloat() to buf.bottom.toFloat(),
            buf.left.toFloat() to buf.bottom.toFloat()
        )

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((xu, yu) in corners) {
            val (xr, yr) = unrotatedToRotated(xu, yu)
            val (xv, yv) = rotatedToView(xr, yr)
            if (xv < minX) minX = xv
            if (yv < minY) minY = yv
            if (xv > maxX) maxX = xv
            if (yv > maxY) maxY = yv
        }

        val l = min(max(minX, 0f), viewW).roundToInt()
        val t = min(max(minY, 0f), viewH).roundToInt()
        val r = min(max(maxX, 0f), viewW).roundToInt()
        val b = min(max(maxY, 0f), viewH).roundToInt()
        return Rect(l, t, r, b)
    }

    // ── YUV(4:2:0) → NV21 （rowStride/pixelStride 対応）──
    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val out = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var dst = 0
        for (row in 0 until height) {
            val base = row * yRowStride
            if (yPixelStride == 1) {
                yBuf.position(base)
                yBuf.get(out, dst, width)
                dst += width
            } else {
                for (col in 0 until width) {
                    out[dst++] = yBuf.get(base + col * yPixelStride)
                }
            }
        }

        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var uvDst = ySize
        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride
                out[uvDst++] = vBuf.get(vIndex) // V
                out[uvDst++] = uBuf.get(uIndex) // U
            }
        }
        return out
    }

    // ── Bitmap の回転（ML Kit を 0°で使うため事前正立化）──
    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        val m = Matrix()
        m.postRotate(d.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    @SuppressLint("SetTextI18n")
    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage()

        // ── 横向きなら、OCR の実行可否に関わらず毎フレーム “青枠” を更新して見せる ──
        val rotation = windowManager.defaultDisplay.rotation
        val landscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
        if (landscape && debugOverlayEnabled && ::previewSize.isInitialized) {
            try {
                val viewBox = overlay.getBoxRect()
                val bufBox = mapViewRectToUnrotatedBufferExact(viewBox)
                val backToView = unrotatedBufferRectToViewRectExact(bufBox)
                overlay.setDebugRect(backToView) // ← デフォルトで常時表示
            } catch (_: Throwable) {
                // 何かあっても表示だけなので握りつぶす
            }
        }

        if (!scanningActive || isProcessing) {
            image?.close()
            return
        }

        // ── 縦向き：既存のスクショ方式（完璧な挙動を維持）──
        if (!landscape) {
            image?.close()

            val bmp = textureView.getBitmap(textureView.width, textureView.height) ?: return
            val box = overlay.getBoxRect()
            val left = box.left.coerceAtLeast(0)
            val top = box.top.coerceAtLeast(0)
            val right = box.right.coerceAtMost(bmp.width)
            val bottom = box.bottom.coerceAtMost(bmp.height)
            if (right <= left || bottom <= top) return
            val cropped = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)

            isProcessing = true
            val input = InputImage.fromBitmap(cropped, 0)
            recognizer.process(input)
                .addOnSuccessListener { handleOcrResult(it.text) }
                .addOnFailureListener { e -> Log.e("OCR", "process failed: ${e.message}", e) }
                .addOnCompleteListener { isProcessing = false }
            return
        }

        // ── 横向き：厳密逆写像 → ROI 抽出 → 正立化 → ML Kit(0°) ──
        val yuvImage = image ?: return
        isProcessing = true

        try {
            val viewBox = overlay.getBoxRect()
            val bufBox = mapViewRectToUnrotatedBufferExact(viewBox)

            // ▼ YUV を NV21 に変換し、ROI だけ JPEG 化（ここで実クロップ）
            val nv21 = yuv420888ToNv21(yuvImage)
            val yuvImg = YuvImage(nv21, ImageFormat.NV21, previewSize.width, previewSize.height, null)

            val baos = ByteArrayOutputStream()
            yuvImg.compressToJpeg(bufBox, 85, baos)
            val jpeg = baos.toByteArray()
            var roiBitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)

            // 端末依存の回転解釈差を排除：事前に正立化 → ML Kit へは 0°
            val rotationDegrees = computeRotationDegrees()
            roiBitmap = rotateBitmap(roiBitmap, rotationDegrees)

            val input = InputImage.fromBitmap(roiBitmap, 0)
            recognizer.process(input)
                .addOnSuccessListener { handleOcrResult(it.text) }
                .addOnFailureListener { e -> Log.e("OCR", "process failed: ${e.message}", e) }
                .addOnCompleteListener {
                    isProcessing = false
                    yuvImage.close()
                }
        } catch (t: Throwable) {
            Log.e("OCR", "onImageAvailable exception: ${t.message}", t)
            isProcessing = false
            yuvImage.close()
        }
    }

    // ── OCRテキスト結果処理（従来ロジックそのまま）──
    private fun handleOcrResult(recognizedText: String) {
        val groupNames = arrayOf("氏名", "生年月日", "住所", "交付日", "有効期限", "番号")
        val progressLines = buildString {
            appendLine("履歴進捗（各グループ 件数/目標$HISTORY_LIMIT）")
            for (i in history.indices) {
                appendLine("${groupNames[i]}：${history[i].size}/$HISTORY_LIMIT")
            }
        }
        textView.text = "$progressLines\n---\n$recognizedText"

        val infoArr = getInfoArrRaw(recognizedText)
        for (i in infoArr.indices) {
            if (infoArr[i].isNotEmpty()) {
                val list = history[i]
                if (list.size >= HISTORY_LIMIT) list.removeAt(0)
                list.add(infoArr[i])
            }
        }

        if (history.all { it.size >= HISTORY_LIMIT }) {
            val formattedAll = mutableListOf<List<String>>()
            for (i in 0 until HISTORY_LIMIT) {
                val candidate = history.map { it[i] }
                val formatted = formatFromLines(candidate)
                formattedAll.add(formatted)
            }
            val bestFields = (0 until formattedAll[0].size).map { col ->
                val columnValues = formattedAll.map { it[col] }
                selectBest(columnValues)
            }
            val finalText = buildString {
                appendLine("氏　　名：${bestFields[0]}")
                appendLine("生年月日：${bestFields[1]}")
                appendLine("住　　所：${bestFields[2]}")
                appendLine("交  付  日：${bestFields[3]}")
                appendLine("有効期限：${bestFields[4]}")
                append("番　　号：${bestFields[5]}")
            }
            Log.d("OCR_HISTORY", debugHistory())
            scanningActive = false
            textView.text = "スキャン成功\n$finalText"
            rescanButton.visibility = View.VISIBLE
        }
    }

    // ── 生データ抽出（再整形なし）──
    private fun getInfoArrRaw(raw: String): List<String> {
        val text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .replace('\u00A0', ' ')
            .replace(Regex("[|｜]"), "")
            .trim()
        val ERA = "(?:昭和|平成|令和)"
        val NUM = "\\d{1,2}"
        val PAREN_OPT = "(?:[（(][^）)]*[）)])?"
        val DATE_WEST = "\\d{4}\\s*年\\s*$PAREN_OPT\\s*$NUM\\s*月\\s*$PAREN_OPT\\s*$NUM\\s*日"
        val DATE_ERA = "(?:$ERA\\s*$NUM\\s*年\\s*$NUM\\s*月\\s*$NUM\\s*日)"
        val DATE_ANY = "(?:$DATE_WEST|$DATE_ERA)"
        val reNameBirth = Regex("""氏名\s*.+?$DATE_ERA\s*生?""")
        val reNameOnly  = Regex("""(?m)^.*氏名.*$""")
        val reBirthOnly = Regex("""(?m)^.*$DATE_ERA\s*生[^\r\n]*$""")
        val reAddress   = Regex("""住[所居]\s*[:：]?[^\r\n]+""")
        val reIssue     = Regex("""[交文]付\s*$DATE_ERA.*""")
        val reValid     = Regex("""$DATE_ANY.*?[迄まマﾏ][でﾃ]\s*有[効效]""")
        val reNumber    = Regex("""第\s*[0-9０-９]{10,12}\s*号""")
        val combined = reNameBirth.find(text)?.value ?: ""
        val nameOnly = reNameOnly.find(text)?.value
        val birthOnly = reBirthOnly.find(text)?.value
        val line1 = if (combined.isNotEmpty()) combined
        else if (nameOnly != null && birthOnly != null) "${nameOnly.trim()} ${birthOnly.trim()}"
        else ""
        val line2 = reAddress.find(text)?.value ?: ""
        val line3 = reIssue.find(text)?.value ?: ""
        val line4 = reValid.find(text)?.value ?: ""
        val line5 = reNumber.find(text)?.value ?: ""
        return listOf(line1, line1, line2, line3, line4, line5)
    }

    // ── 最終出力整形（完全版）──
    private fun formatFromLines(lines: List<String>): List<String> {
        fun preClean(raw: String): String {
            var t = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace(Regex("[ー−―－]"), "-")
            t = t.replace(Regex("\\s+"), "")
            return t
        }
        val l1 = preClean(lines.getOrNull(0).orEmpty())
        val l2 = preClean(lines.getOrNull(2).orEmpty())
        val l3 = preClean(lines.getOrNull(3).orEmpty())
        val l4 = preClean(lines.getOrNull(4).orEmpty())
        val l5 = preClean(lines.getOrNull(5).orEmpty())
        val DATE_ANY = Regex("""(?:\d{4}年(?:\([^)]*\))?\d{1,2}月\d{1,2}日|(?:昭和|平成|令和)\d{1,2}年\d{1,2}月\d{1,2}日)""")
        var name = ""
        var birth = ""
        if (l1.contains("氏名")) {
            val body = l1.replaceFirst(Regex(""".*?氏名\s*[:：]?"""), "").trim()
            val dm = DATE_ANY.find(body)
            if (dm != null) {
                name = body.substring(0, dm.range.first)
                    .replace(Regex("(昭和|平成|令和).*$"), "")
                    .trim()
                birth = dm.value.replace("生", "").trim()
            }
        }
        val addr = l2.removePrefix("住所")
        val issue = run {
            val s = l3.replace(Regex("[交文]付"), "")
            val dm = DATE_ANY.find(s)
            val dateOnly = dm?.value ?: ""
            val code = Regex("""(?<!\d)\d{5}(?!\d)""").find(s)?.value.orEmpty()
            if (dateOnly.isNotEmpty() && code.isNotEmpty()) "$dateOnly($code)" else dateOnly
        }
        val valid = l4.replace(Regex("""まで[有領]?[効效]"""), "")
        val no = l5.replace("第", "").replace("号", "").replace(Regex("\\D"), "")
        return listOf(name, birth, addr, issue, valid, no)
    }

    private fun selectBest(list: List<String>): String =
        list.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""

    private fun debugHistory(): String {
        val sb = StringBuilder()
        for (i in history.indices) {
            sb.append("グループ${i + 1}:\n")
            history[i].forEachIndexed { idx, item ->
                sb.append("  [${idx + 1}] $item\n")
            }
        }
        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        if (::cameraDevice.isInitialized) cameraDevice.close()
        if (::imageReader.isInitialized) imageReader.close()
    }
}
