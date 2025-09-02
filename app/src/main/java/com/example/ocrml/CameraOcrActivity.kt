package com.example.ocrml

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.text.Normalizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission

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

    // 変形防止のためのプレビュー設定
    private lateinit var previewSize: Size
    private var sensorOrientation: Int = 0

    @Volatile private var isProcessing = false
    @Volatile private var scanningActive = true

    // ML Kit 日本語OCR
    private val recognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    // 各グループ（氏名・住所・交付日・有効期限・番号）の履歴（最大5件）
    private val history = Array(5) { mutableListOf<String>() }
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

        // 再スキャンボタン（履歴と表示をリセット）
        rescanButton.setOnClickListener {
            scanningActive = true
            isProcessing = false
            textView.text = ""
            rescanButton.visibility = View.GONE
            for (list in history) list.clear()
        }

        val handlerThread = HandlerThread("CameraBackground")
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)

        if (allPermissionsGranted()) {
            // TextureView のレイアウト完了後にカメラを開く
            textureView.post { openCamera() }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
        }
    }

    // カメラ権限チェック
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

    // ===== アスペクト比を維持しつつプレビュー用サイズを選択（TextureView に最適化） =====
    private fun chooseOptimalPreviewSize(
        choices: Array<Size>,
        viewW: Int,
        viewH: Int,
        swapped: Boolean
    ): Size {
        val targetW = if (swapped) viewH else viewW
        val targetH = if (swapped) viewW else viewH
        val targetRatio = targetW.toFloat() / targetH

        return choices.minByOrNull { s ->
            val ratio = s.width.toFloat() / s.height
            val ratioDiff = kotlin.math.abs(ratio - targetRatio)
            // 目標より小さいサイズには軽いペナルティ
            val tooSmall = if (s.width < targetW || s.height < targetH) 0.2f else 0f
            ratioDiff + tooSmall
        } ?: choices.first()
    }

    // ===== YUV サイズはプレビューと同一のアスペクト比に合わせる（後段処理のズレ防止） =====
    private fun chooseYuvSizeWithSameAspect(choices: Array<Size>, ref: Size): Size {
        val refRatio = ref.width.toFloat() / ref.height
        return choices.minByOrNull { s ->
            val ratio = s.width.toFloat() / s.height
            val ratioDiff = kotlin.math.abs(ratio - refRatio)
            // 過大サイズを避けつつ比率優先
            ratioDiff * 10f + kotlin.math.abs(s.width - ref.width) / 10000f
        } ?: choices.first()
    }

    // ===== TextureView への等比スケーリング + 回転（歪みなし・中央トリミング）=====
    // ★重要：センサー角度は使わず、Display の回転だけで行列を作る（Google の Camera2Basic と同流儀）
    private fun configureTransform(viewW: Int, viewH: Int) {
        if (!::previewSize.isInitialized) return

        val rotation = windowManager.defaultDisplay.rotation
        val matrix = android.graphics.Matrix()
        val viewRect = android.graphics.RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        when (rotation) {
            // 90度 or 270度の時はバッファの幅高が入れ替わる前提で合わせる
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                // バッファは横長前提：幅=previewSize.height, 高=previewSize.width として矩形を作る
                val bufferRect = android.graphics.RectF(
                    0f, 0f,
                    previewSize.height.toFloat(),
                    previewSize.width.toFloat()
                )
                // ビュー中心に合わせてバッファ矩形をオフセット
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())

                // 等比でビュー全面を充填（中央トリミング、伸縮なし）
                matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)

                // 追加スケール（Camera2Basic と同じ）：これで上下左右の欠けが最小化される
                val scale = kotlin.math.max(
                    viewH.toFloat() / previewSize.height.toFloat(),
                    viewW.toFloat() / previewSize.width.toFloat()
                )
                matrix.postScale(scale, scale, centerX, centerY)

                // 回転：90*(rotation-2) → ROTATION_90:-90°, ROTATION_270:+90°
                matrix.postRotate(90f * (rotation - 2), centerX, centerY)
            }

            // 180度の時だけ 180 回転
            Surface.ROTATION_180 -> {
                matrix.postRotate(180f, centerX, centerY)
            }

            // 0度（縦持ち標準）の時はそのまま。追加の回転は不要
            else -> {
                // 何もしない（歪み防止のための行列適用は不要）
            }
        }

        textureView.setTransform(matrix)
    }


    // カメラを開く（プレビュー比率の整合をとった上で ImageReader を構成）
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

        // TextureView の実寸から最適なプレビューサイズを選ぶ
        val viewW = textureView.width
        val viewH = textureView.height
        val previewChoices = map.getOutputSizes(SurfaceTexture::class.java)
        previewSize = chooseOptimalPreviewSize(previewChoices, viewW, viewH, swapped)

        // YUV はプレビューと同じアスペクト比に合わせる
        val yuvChoices = map.getOutputSizes(ImageFormat.YUV_420_888)
        val yuvSize = chooseYuvSizeWithSameAspect(yuvChoices, previewSize)

        // プレビューのバッファサイズを設定し、非伸長の変換を適用
        val surfaceTexture = textureView.surfaceTexture!!
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        configureTransform(viewW, viewH)

        imageReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2)
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

    // プレビューとOCRセッション作成
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
                    // ここで再度変換を適用（状態変化時の安全策）
                    configureTransform(textureView.width, textureView.height)

                    captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
    }

    // OCR処理
    @SuppressLint("SetTextI18n")
    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage()
        image?.close()

        if (!scanningActive || isProcessing) return

        // 画面に表示された（変換後の）プレビューから矩形領域を切り出す
        val bitmap = textureView.bitmap ?: return
        val box = overlay.getBoxRect()
        val cropped = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val input = InputImage.fromBitmap(cropped, 0)

        isProcessing = true
        recognizer.process(input)
            .addOnSuccessListener { result ->
                // OCR結果をリアルタイム表示
                // textView.text = result.text

                // OCR結果をリアルタイム表示 + 履歴件数（ラベル付き）
                val groupNames = arrayOf("氏名/生年月日", "住所", "交付日", "有効期限", "番号")
                val progressLines = buildString {
                    appendLine("履歴進捗（各グループ 件数/目標$HISTORY_LIMIT）")
                    for (i in history.indices) {
                        appendLine("${groupNames[i]}：${history[i].size}/$HISTORY_LIMIT")
                    }
                }
                textView.text = "$progressLines\n---\n${result.text}"

                val infoArr = getInfoArrRaw(result.text)
                // 抽出できた項目のみ履歴に追加（先入れ先出し）
                for (i in infoArr.indices) {
                    if (infoArr[i].isNotEmpty()) {
                        val list = history[i]
                        if (list.size >= HISTORY_LIMIT) list.removeAt(0)
                        list.add(infoArr[i])
                    }
                }

                // 全5項目が所定回数たまったら多数決で最終決定
                if (history.all { it.size >= HISTORY_LIMIT }) {
                    val formattedAll = mutableListOf<List<String>>()

                    // 各回の候補を整形して保存
                    for (i in 0 until HISTORY_LIMIT) {
                        val candidate = history.map { it[i] } // 各グループから i 番目を取得
                        val formatted = formatFromLines(candidate) // 整形後の6フィールド
                        formattedAll.add(formatted)
                    }

                    // 列方向に集計して最頻値を採用
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
            .addOnCompleteListener { isProcessing = false }
    }

    // ===== 生データ抽出（再整形なし） =====
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
        val reAddress   = Regex("""住[所居]\s*[:：]?[^\r\n]+""")
        val reIssue     = Regex("""[交文]付\s*$DATE_ERA.*""")
        val reValid     = Regex("""$DATE_ANY.*?[迄まマﾏ][でﾃ]\s*有[効效]""")
        val reNumber    = Regex("""第\s*[0-9０-９]{10,12}\s*号""")

        val line1 = reNameBirth.find(text)?.value ?: ""
        val line2 = reAddress.find(text)?.value ?: ""
        val line3 = reIssue.find(text)?.value ?: ""
        val line4 = reValid.find(text)?.value ?: ""
        val line5 = reNumber.find(text)?.value ?: ""

        return listOf(line1, line2, line3, line4, line5)
    }

    // ===== 最終出力整形（完全版） =====
    private fun formatFromLines(lines: List<String>): List<String> {
        fun preClean(raw: String): String {
            var t = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace(Regex("[ー−―－]"), "-")
            t = t.replace(Regex("\\s+"), "")
            return t
        }

        val l1 = preClean(lines.getOrNull(0).orEmpty())
        val l2 = preClean(lines.getOrNull(1).orEmpty())
        val l3 = preClean(lines.getOrNull(2).orEmpty())
        val l4 = preClean(lines.getOrNull(3).orEmpty())
        val l5 = preClean(lines.getOrNull(4).orEmpty())

        val DATE_ANY = Regex("""(?:\d{4}年(?:\([^)]*\))?\d{1,2}月\d{1,2}日|(?:昭和|平成|令和)\d{1,2}年\d{1,2}月\d{1,2}日)""")
        // 氏名 + 生年月日
        var name = ""
        var birth = ""
        if (l1.startsWith("氏名")) {
            val body = l1.removePrefix("氏名")
            val dm = DATE_ANY.find(body)
            if (dm != null) {
                name = body.substring(0, dm.range.first)
                    .replace(Regex("(昭和|平成|令和).*$"), "")
                    .trim()
                birth = dm.value.replace("生", "")
            }
        }
        // 住所
        val addr = l2.removePrefix("住所")
        // 交付日（交付/文付 + 任意5桁コード）
        val issue = run {
            val s = l3.replace(Regex("[交文]付"), "")
            val dm = DATE_ANY.find(s)
            val dateOnly = dm?.value ?: ""
            val code = Regex("""(?<!\d)\d{5}(?!\d)""").find(s)?.value.orEmpty()
            if (dateOnly.isNotEmpty() && code.isNotEmpty()) "$dateOnly($code)" else dateOnly
        }
        // 有効期限（「まで有効/まで領効/まで效」を除去、()内の和暦も保持）
        val valid = run {
            val s = l4.replace(Regex("""まで[有領]?[効效]"""), "")
            s
        }
        // 番号（数字のみ）
        val no = l5.replace("第", "")
            .replace("号", "")
            .replace(Regex("\\D"), "")

        return listOf(name, birth, addr, issue, valid, no)
    }

    // 最頻値を返す
    private fun selectBest(list: List<String>): String =
        list.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""

    // デバッグ用：全履歴を文字列化
    private fun debugHistory(): String {
        val sb = StringBuilder()
        for (i in history.indices) {
            sb.append("グループ${i + 1}:\n")
            history[i].forEachIndexed { idx, item ->
                sb.append("  [${idx+1}] $item\n")
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
