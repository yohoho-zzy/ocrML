package com.example.ocrml

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CameraOcrActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener {

    private lateinit var textureView: TextureView
    private lateinit var textView: TextView

    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private lateinit var backgroundHandler: Handler

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_ocr)
        textureView = findViewById(R.id.preview)
        textView = findViewById(R.id.result)

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

    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val input = InputImage.fromMediaImage(image, 0)
        recognizer.process(input)
            .addOnSuccessListener { textView.text = it.text }
            .addOnFailureListener { }
        image.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        if (::cameraDevice.isInitialized) cameraDevice.close()
        if (::imageReader.isInitialized) imageReader.close()
    }
}
