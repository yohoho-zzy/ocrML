package com.example.ocrml

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
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

    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var backgroundHandler: Handler

    @Volatile private var isProcessing = false
    @Volatile private var scanningActive = true

    // ML Kit 日本語OCR
    private val recognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    // 各グループ（氏名・住所・交付日・有効期限・番号）の履歴（最大7件）
    private val history = Array(5) { mutableListOf<String>() }
    private val HISTORY_LIMIT = 5

    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.CAMERA)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_ocr)

        textureView = findViewById(R.id.preview)
        textView = findViewById(R.id.result)
        overlay = findViewById(R.id.ocr_area)
        rescanButton = findViewById(R.id.rescan_button)

        // 再スキャンボタン
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

    // カメラを開く
    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first()

        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map!!.getOutputSizes(ImageFormat.YUV_420_888)[0]

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener(this, backgroundHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(size)
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

        val bitmap = textureView.bitmap ?: return
        val box = overlay.getBoxRect()
        val cropped = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        val input = InputImage.fromBitmap(cropped, 0)

        isProcessing = true
        recognizer.process(input)
            .addOnSuccessListener { result ->
                // OCR結果をリアルタイム表示
                textView.text = result.text

                val infoArr = getInfoArrRaw(result.text)
                // 任意のフィールドがあれば、そのフィールドだけ履歴に追加
                for (i in infoArr.indices) {
                    if (infoArr[i].isNotEmpty()) {
                        val list = history[i]
                        if (list.size >= HISTORY_LIMIT) list.removeAt(0)
                        list.add(infoArr[i])
                        Log.d("OCR_HISTORY", "グループ${i+1}: ${list.size}件 -> ${infoArr[i]}")
                    }
                }

                // 5項目すべて7件に達したら最終判定
                if (history.all { it.size >= HISTORY_LIMIT }) {
                    val formattedAll = mutableListOf<List<String>>()

                    // 7回分の候補を整形
                    for (i in 0 until HISTORY_LIMIT) {
                        val candidate = history.map { it[i] } // 各グループから i 番目を取る
                        val formatted = formatFromLines(candidate) // 整形済みの6フィールド
                        formattedAll.add(formatted)
                    }

                    // 縦に集計して最頻値を選ぶ
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
            t = t.replace(Regex("""月\s+(\d)日""")) { m -> "月1${m.groupValues[1]}日" }
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
            // 文字自体は preClean 後なのでそのまま返す
            s
        }
        // 番号（数字のみ）
        val no = l5.replace("第", "")
            .replace("号", "")
            .replace(Regex("\\D"), "")

        return listOf(name, birth, addr, issue, valid, no)
    }

    // 最頻値を返す
    private fun selectBest(list: List<String>): String = list.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""

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