package com.websarva.wings.android.maskapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.AudioManager
import android.media.MediaParser
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.ml.custom.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main2.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.Collections.rotate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs


// typealias LumaListener = (luma: Double) -> Unit

class MainActivity2 : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService
    lateinit var mediaPlayer: MediaPlayer

    //カスタムモデルのローカルモデルを構成する
    val localModel = FirebaseCustomLocalModel.Builder()
        .setAssetFilePath("mask_detector.tflite")
        .build()

    //インタープリター
    val options = FirebaseModelInterpreterOptions.Builder(localModel).build()
    val interpreter = FirebaseModelInterpreter.getInstance(options)

    //入力データの推論
    val inputOutputOptions = FirebaseModelInputOutputOptions.Builder()
        .setInputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 224, 224, 3))
        .setOutputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 2))
        .build()

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        // Set up the listener for take photo button
        // camera_capture_button.setOnClickListener { takePhoto() }

        outputDirectory = getOutputDirectory()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // mediaPlayer = MediaPlayer.create(this, R.raw.mask)

        val language: String? = intent.getStringExtra("language")

        if (language == "Japanese"){
            mediaPlayer = MediaPlayer.create(this, R.raw.japanese_ver)
        }else{
            mediaPlayer = MediaPlayer.create(this, R.raw.mask)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("UnsafeExperimentalUsageError", "SetTextI18n")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.createSurfaceProvider())
                }

            imageCapture = ImageCapture.Builder()
                .build()


            val point = Point()
            val size = display?.getRealSize(point)

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(point.x, point.y))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) //カメラの動きが早すぎるとき，一部のフレームをスキップ
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, { imageProxy ->
                        val options = FaceDetectorOptions.Builder()
                            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                            .build()
                        val detector = FaceDetection.getClient(options)
                        val mediaImage = imageProxy.image

                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                            // 顔検出情報
                            detector.process(image)
                                .addOnSuccessListener { faces ->
                                    // 今回は1つの顔のみ検出
                                    if (faces.size > 1) return@addOnSuccessListener
                                    for (face in faces) {
                                        //if (viewFinder.childCount > 1) viewFinder.removeViewAt(1)
                                        val bounds = face.boundingBox

                                        // val element = Draw(this, bounds)
                                        // viewFinder.addView(element,1)

                                        val bitmap = imageProxy.toBitmap()

                                        val xOffset: Int = if (bounds.bottom >= bitmap.width) {
                                            return@addOnSuccessListener
                                        } else if (bounds.top >= bitmap.width){
                                            return@addOnSuccessListener
                                        }else if (bounds.bottom < 0) {
                                            return@addOnSuccessListener
                                        }else if (bounds.top < 0) {
                                            return@addOnSuccessListener
                                        }else {
                                            bounds.top
                                        }

                                        val yOffset: Int = if (bounds.left >= bitmap.height) {
                                            return@addOnSuccessListener
                                        } else if (bounds.right >= bitmap.height) {
                                            return@addOnSuccessListener
                                        } else if (bounds.left < 0) {
                                            return@addOnSuccessListener
                                        } else if(bounds.right < 0) {
                                            return@addOnSuccessListener
                                        } else {
                                            bitmap.height - bounds.right
                                        }

                                        //幅と高さの取得
                                        val cropWidth = bounds.width()
                                        val cropHeight = bounds.height()

                                        //imageProxyをbitmapに変換後crop
                                        val cropBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, cropHeight, cropWidth)
                                        //cropした画像を画面に表示
                                        minView(cropBitmap)

                                        // bitmapを回転後，マスク検知
                                        val roteBitmap = rotateBitmap(bitmap, 90)
                                        val resizeBitmap = Bitmap.createScaledBitmap(roteBitmap, 224, 224, true)
                                        val batchNum = 0
                                        val input = Array(1) { Array(224) { Array(224) { FloatArray(3) } } }
                                        for (x in 0..223) {
                                            for (y in 0..223) {
                                                val pixel = resizeBitmap.getPixel(x, y)
                                                input[batchNum][x][y][0] = (Color.red(pixel)) / 255.0f
                                                input[batchNum][x][y][1] = (Color.green(pixel)) / 255.0f
                                                input[batchNum][x][y][2] = (Color.blue(pixel)) / 255.0f
                                            }
                                        }
                                        val inputs = FirebaseModelInputs.Builder()
                                            .add(input) // add() as many input arrays as your model requires
                                            .build()
                                        interpreter?.run(inputs, inputOutputOptions)?.addOnSuccessListener { result ->
                                            //結果の出力をしたい
                                            val output = result.getOutput<Array<FloatArray>>(0)
                                            val probabilities = output[0]
                                            val without_mask = probabilities[0]
                                            val with_mask = probabilities[1]
                                            if (with_mask > without_mask) {
                                                if (viewFinder.childCount > 1) viewFinder.removeViewAt(1)
                                                val element = Draw2(this, bounds)
                                                viewFinder.addView(element,1)
                                                textView.setTextColor(Color.GREEN)
                                                textView.text = String.format("Masked: %1.1f", with_mask * 100)
                                            }else {
                                                if (viewFinder.childCount > 1) viewFinder.removeViewAt(1)
                                                val element = Draw(this, bounds)
                                                viewFinder.addView(element,1)
                                                textView.setTextColor(Color.RED)
                                                textView.text = String.format("Not masked: %1.1f", without_mask * 100)
                                                player()
                                            }
                                            Log.d(TAG, "classification success")

                                        }?.addOnFailureListener { e ->
                                            Log.d(TAG, "classification failure")
                                        }

                                        Log.d(TAG, "Face recognition success")
                                        // player()
                                    }
                                    imageProxy.close()
                                }
                                .addOnFailureListener { e ->
                                    Log.d(TAG, "Face recognition failure")
                                    imageProxy.close()
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        }

                    })
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }




    private class Draw(context: Context?, var rect: Rect) : View(context) {

        lateinit var paint: Paint
        lateinit var textPaint: Paint

        init {
            init()
        }

        private fun init() {
            paint = Paint()
            paint.color = Color.RED
            paint.strokeWidth = 10f
            paint.style = Paint.Style.STROKE

            textPaint = Paint()
            textPaint.color = Color.RED
            textPaint.style = Paint.Style.FILL
            textPaint.textSize = 80f
        }


        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            //canvas.drawText(text, rect.centerX().toFloat(), rect.centerY().toFloat(), textPaint)
            //canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)
        }
    }

    private class Draw2(context: Context?, var rect: Rect) : View(context) {

        lateinit var paint: Paint

        init {
            init()
        }

        private fun init() {
            paint = Paint()
            paint.color = Color.GREEN
            paint.strokeWidth = 10f
            paint.style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)
        }
    }

//    // imageProxyをbitmapに変換後crop
//    private fun cropImage(image: Image, rotationDegree: Int, xOffset: Int, yOffset: Int, cropWidth: Int, cropHeight: Int): Bitmap? {
//        // 1 - Convert image to Bitmap
//        val buffer: ByteBuffer = image.getPlanes().get(0).getBuffer()
//        val bytes = ByteArray(buffer.remaining())
//        buffer[bytes]
//        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//
//        // 2 - Rotate the Bitmap
//        if (rotationDegree != 0) {
//            val rotationMatrix = Matrix()
//            rotationMatrix.postRotate(rotationDegree.toFloat())
//            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true)
//        }
//
//        // 3 - Crop the Bitmap
//        bitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, cropWidth, cropHeight)
//        return bitmap
//    }



    //ImageProxyをBitmapに変換
    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    //音楽再生
    private fun player() {
        if(!mediaPlayer.isPlaying) {
            mediaPlayer.start()
        }
    }

    private fun minView(bitmap: Bitmap) {
        val roteBitmap = rotateBitmap(bitmap, 90)
        val image = imageView.setImageBitmap(roteBitmap)

    }

    private fun rotateBitmap(bitmap: Bitmap, rotation: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mediaPlayer.release()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

//    private fun takePhoto() {
//        // Get a stable reference of the modifiable image capture use case
//        val imageCapture = imageCapture ?: return
//
//        // Create time-stamped output file to hold the image
//        val photoFile = File(
//                outputDirectory,
//                SimpleDateFormat(FILENAME_FORMAT, Locale.US
//                ).format(System.currentTimeMillis()) + ".jpg")
//
//        // Create output options object which contains file + metadata
//        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
//
//        // Set up image capture listener, which is triggered after photo has
//        // been taken
//        imageCapture.takePicture(
//                outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
//            override fun onError(exc: ImageCaptureException) {
//                Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
//            }
//
//            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
//                val savedUri = Uri.fromFile(photoFile)
//                val msg = "Photo capture succeeded: $savedUri"
//                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
//                Log.d(TAG, msg)
//            }
//        })
//    }

//    private class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer {
//
//        private fun ByteBuffer.toByteArray(): ByteArray {
//            rewind()    // Rewind the buffer to zero
//            val data = ByteArray(remaining())
//            get(data)   // Copy the buffer into a byte array
//            return data // Return the byte array
//        }
//
//        override fun analyze(image: ImageProxy) {
//
//            val buffer = image.planes[0].buffer
//            val data = buffer.toByteArray()
//            val pixels = data.map { it.toInt() and 0xFF }
//            val luma = pixels.average()
//
//            listener(luma)
//
//            image.close()
//        }
//    }
}

//    private class FaceAnalyzer(private var listener: (Rect) -> Unit) : ImageAnalysis.Analyzer {
//
//        val detector = FaceDetection.getClient()
//
//        @SuppressLint("UnsafeExperimentalUsageError")
//        override fun analyze(imageProxy: ImageProxy) {
//
//            val mediaImage = imageProxy.image
//
//            if (mediaImage != null) {
//                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
//
//                // 顔検出情報
//                detector.process(image)
//                    .addOnSuccessListener { faces ->
//                        for (face in faces) {
//                            val bounds = face.boundingBox
//                            // val rotY = face.headEulerAngleY // Head is rotated to the right rotY degrees
//                            // val rotZ = face.headEulerAngleZ // Head is tilted sideways rotZ degrees
//                            // listener(bounds)
//                        }
//
//                    }
//                    .addOnFailureListener { e ->
//                        Log.d(TAG, "Face recognition failure")
//                        imageProxy.close()
//                    }
//                    .addOnCompleteListener{
//                        imageProxy.close()
//                    }
//            }
//        }
//
//    }

//            val imageAnalyzer = ImageAnalysis.Builder()
//                    .build()
//                    .also {
//                        it.setAnalyzer(cameraExecutor, FaceAnalyzer { bounds ->
//                            Log.d(TAG, "Face detected: $bounds")
//                            //boundsには座標が格納されている
//                            //座標を基に画像をキャプチャする処理を以下に記入
//                            // camera_capture_button.setEnabled(faces > 0)
//                        })
//                    }