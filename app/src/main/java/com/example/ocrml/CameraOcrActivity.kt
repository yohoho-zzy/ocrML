package com.example.ocrml

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
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
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.text.Normalizer
import android.util.Log

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

    // Flag to avoid queuing multiple recognition tasks which causes delays
    @Volatile
    private var isProcessing = false

    // Use ML Kit's Japanese text recognizer
    private val recognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    private var lastNameLine: String? = null
    private var stableCount = 0
    @Volatile
    private var scanningActive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_ocr)
        textureView = findViewById(R.id.preview)
        textView = findViewById(R.id.result)
        overlay = findViewById<OverlayView>(R.id.ocr_area)
        rescanButton = findViewById(R.id.rescan_button)
        rescanButton.setOnClickListener {
            // Restart scanning and reset state when the user taps "Rescan"
            scanningActive = true
            stableCount = 0
            lastNameLine = null
            isProcessing = false
            rescanButton.visibility = View.GONE
            textView.text = ""
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

    private fun allPermissionsGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
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

    private fun openCamera() {
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.first()

        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map!!.getOutputSizes(ImageFormat.YUV_420_888)[0]

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener(this, backgroundHandler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createSession(size)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
            }
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
                    captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) { }
            }, backgroundHandler)
    }

    @SuppressLint("SetTextI18n")
    override fun onImageAvailable(reader: ImageReader) {
        // Always acquire and close the latest image to keep the pipeline flowing
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
                val infoArr = getInfoArr(result.text)
                if (infoArr!=null && infoArr[2]!="") {
                    Log.d("TEXT：", result.text)
                    Log.d("ARR：", infoArr.toString())
                }
                if (infoArr != null && infoArr[4] != "") {
                    val infoEnd = formatFromLines(infoArr)
                    scanningActive = false
                    textView.text = "スキャン成功\n$infoEnd"
                    rescanButton.visibility = View.VISIBLE
                } else {
                    textView.text = result.text
                    lastNameLine = null
                    stableCount = 0
                }
            }
            .addOnFailureListener { }
            .addOnCompleteListener { isProcessing = false }
    }

    private fun getInfoArr(raw: String): List<String>? {
        var text = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        text = text.replace('\u00A0', ' ')
            .replace(Regex("[|｜]"), "")
            .replace("領効", "有効")
            .replace("效", "効")
            .replace(Regex("(?<=日)[年入八はﾊ]\\s*で"), "まで")
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        val ERA = "(?:昭和|平成|令和)"
        val NUM = "\\d{1,2}"
        val PAREN_OPT = "(?:[（(][^）)]*[）)])?"
        val DATE_WEST = "\\d{4}\\s*年\\s*$PAREN_OPT\\s*$NUM\\s*月\\s*$PAREN_OPT\\s*$NUM\\s*日"
        val DATE_ERA = "(?:$ERA\\s*$NUM\\s*年\\s*$NUM\\s*月\\s*$NUM\\s*日)"
        val DATE_ANY = "(?:$DATE_WEST|$DATE_ERA)"

        val reNameBirth = Regex(
            """氏名\s*(?<name>[\p{InCJKUnifiedIdeographs}々〆ヶぁ-ゖァ-ヺー・\s]{2,40}?)\s*(?<birth>$DATE_ERA)\s*生?""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        )
        val reAddress = Regex(
            """住[所居]\s*[:：]?\s*(?<addr>[^\r\n]+)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        )
        val reIssue = Regex(
            """[交文]付\s*(?<date>$DATE_ERA)(?:[^\r\n]*?(?<code>\d{2,6}))?""",
            setOf(RegexOption.MULTILINE)
        )
        val reValidStrict = Regex(
            """(?<date>$DATE_ANY)\s*(?:（[^）]*）|\([^)]*\)|[^有\n]){0,12}?[迄まマﾏ][でﾃ]\s*有[効效]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        )
        val reValidLoose = Regex(
            """(?<date>$DATE_ANY)\s*(?:（[^）]*）|\([^)]*\)|[^有\n]){0,12}?[でﾃ]\s*有[効效]""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        )
        val reNumber = Regex(
            """第\s*(?<no>[0-9０-９]{10,12})\s*号""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
        )

        val m1 = reNameBirth.find(text) ?: return listOf("", "", "", "", "")
        val name  = m1.groups["name"]!!.value.replace(Regex("\\s+"), " ").trim()
        val birth = m1.groups["birth"]!!.value.replace(Regex("\\s+"), " ").trim()
        val line1 = "氏名 $name $birth 生"

        val m2 = reAddress.find(text) ?: return listOf(line1, "", "", "", "")
        val addr = m2.groups["addr"]!!.value.replace(Regex("\\s+"), " ").trim()
        val line2 = "住所 $addr"

        val m3 = reIssue.find(text) ?: return listOf(line1, line2, "", "", "")
        val issueDate = m3.groups["date"]!!.value.replace(Regex("\\s+"), " ").trim()
        val issueCode = m3.groups["code"]?.value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val line3 = ("交付 $issueDate " + issueCode).trim()

        val m4 = reValidStrict.find(text) ?: reValidLoose.find(text) ?: return listOf(line1, line2, line3, "", "")
        val validDate = m4.groups["date"]!!.value.replace(Regex("\\s+"), " ").trim()
        val line4 = "$validDate まで有効"

        val m5 = reNumber.find(text) ?: return listOf(line1, line2, line3, line4, "")
        val no = m5.groups["no"]!!.value.replace(Regex("[^0-9]"), "")
        val line5 = "第 $no 号"

        return listOf(line1, line2, line3, line4, line5)
    }

    private fun formatFromLines(lines: List<String>): String {
        fun ns(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).replace(Regex("\\s+"), "")

        val l1 = lines.getOrNull(0).orEmpty()
        val l2 = lines.getOrNull(1).orEmpty()
        val l3 = lines.getOrNull(2).orEmpty()
        val l4 = lines.getOrNull(3).orEmpty()
        val l5 = lines.getOrNull(4).orEmpty()

        val ERA = "(?:昭和|平成|令和)"
        val NUM = "\\d{1,2}"
        val PAREN_OPT = "(?:[（(][^）)]*[）)])?"
        val DATE_WEST = "\\d{4}\\s*年\\s*$PAREN_OPT\\s*$NUM\\s*月\\s*$PAREN_OPT\\s*$NUM\\s*日"
        val DATE_ERA  = "$ERA\\s*$NUM\\s*年\\s*$NUM\\s*月\\s*$NUM\\s*日"
        val DATE_ANY  = "(?:$DATE_WEST|$DATE_ERA)"

        val m1 = Regex("""^氏名\s*(?<name>.+?)\s*(?<birth>$DATE_ERA)\s*生?""").find(l1)
        val name  = m1?.groups?.get("name")?.value?.let(::ns).orEmpty()
        val birth = m1?.groups?.get("birth")?.value?.let(::ns).orEmpty()

        val m2 = Regex("""^住所\s*(?<addr>.+)$""").find(l2)
        val addr = m2?.groups?.get("addr")?.value?.let(::ns).orEmpty()

        val mi = Regex("""^交付\s*(?<date>$DATE_ERA)(?:\s*(?<code>[0-9０-９]{5}))?""").find(l3)
        val issue = mi?.groups?.get("date")?.value?.let(::ns).orEmpty()
        val code5 = mi?.groups?.get("code")?.value
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC).replace(Regex("\\D"), "") }
            .orEmpty()

        val mv = Regex("""(?<date>$DATE_ANY)""").find(l4)
        val valid = mv?.groups?.get("date")?.value?.let(::ns).orEmpty()

        val no = Regex("""([0-9０-９]{10,12})""").find(l5)?.groupValues?.get(1)
            ?.let { Normalizer.normalize(it, Normalizer.Form.NFKC).replace(Regex("\\D"), "") }
            .orEmpty()

        return buildString {
            appendLine("氏　　名：$name")
            appendLine("住　　所：$addr")
            appendLine("生年月日：$birth")
            appendLine("交  付  日：$issue" + if (code5.isNotEmpty()) "（$code5）" else "")
            appendLine("有効期限：$valid")
            append("番　　号：$no")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        if (::cameraDevice.isInitialized) cameraDevice.close()
        if (::imageReader.isInitialized) imageReader.close()
    }
}
