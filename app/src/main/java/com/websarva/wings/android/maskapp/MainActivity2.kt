package com.websarva.wings.android.maskapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
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
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main2.*
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity2 : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    lateinit var mediaPlayer: MediaPlayer

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

        cameraExecutor = Executors.newSingleThreadExecutor()

        val language: String? = intent.getStringExtra("language")

        mediaPlayer = if (language == "Japanese") {
            MediaPlayer.create(this, R.raw.japanese_ver)
        } else {
            MediaPlayer.create(this, R.raw.mask)
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
                                            // 顔座標の取得
                                            if (faces.size > 1) return@addOnSuccessListener
                                            for (face in faces) {
                                                val bounds = face.boundingBox
                                                val bitmap = imageProxy.toBitmap()
                                                val xOffset: Int = if (bounds.bottom >= bitmap.width) {
                                                    return@addOnSuccessListener
                                                } else if (bounds.top >= bitmap.width) {
                                                    return@addOnSuccessListener
                                                } else if (bounds.bottom < 0) {
                                                    return@addOnSuccessListener
                                                } else if (bounds.top < 0) {
                                                    return@addOnSuccessListener
                                                } else {
                                                    bounds.top
                                                }

                                                val yOffset: Int = if (bounds.left >= bitmap.height) {
                                                    return@addOnSuccessListener
                                                } else if (bounds.right >= bitmap.height) {
                                                    return@addOnSuccessListener
                                                } else if (bounds.left < 0) {
                                                    return@addOnSuccessListener
                                                } else if (bounds.right < 0) {
                                                    return@addOnSuccessListener
                                                } else {
                                                    bitmap.height - bounds.right
                                                }

                                                //幅と高さの取得
                                                val cropWidth = bounds.width()
                                                val cropHeight = bounds.height()

                                                //bitmapを顔座標を基にrectの形でcrop，そのままUIに表示
                                                val cropBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, cropHeight, cropWidth)
                                                minView(cropBitmap)

                                                //interpreterを実行するための引数を用意
                                                val interpreter = LoadModel().first
                                                val inputOutputOptions = LoadModel().second
                                                val inputs = InputImage(bitmap)

                                                interpreter?.run(inputs, inputOutputOptions)?.addOnSuccessListener { result ->
                                                    val output = result.getOutput<Array<FloatArray>>(0)
                                                    val probabilities = output[0]
                                                    val notMasked = probabilities[0]
                                                    val masked = probabilities[1]
                                                    // マスク着用
                                                    if (masked > notMasked) {
                                                        if (viewFinder.childCount > 1) viewFinder.removeViewAt(1)
                                                        val element = DrawNotMasked(this, bounds)
                                                        viewFinder.addView(element, 1)
                                                        textView.setTextColor(Color.GREEN)
                                                        textView.text = String.format("Masked: %1.1f", masked * 100)
                                                    } else {
                                                        // マスク未着用
                                                        if (viewFinder.childCount > 1) viewFinder.removeViewAt(1)
                                                        val element = DrawMasked(this, bounds)
                                                        viewFinder.addView(element, 1)
                                                        textView.setTextColor(Color.RED)
                                                        textView.text = String.format("Not masked: %1.1f", notMasked * 100)
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

    private class DrawMasked(context: Context?, var rect: Rect) : View(context) {

        lateinit var paint: Paint

        init {
            init()
        }

        private fun init() {
            paint = Paint()
            paint.color = Color.RED
            paint.strokeWidth = 10f
            paint.style = Paint.Style.STROKE
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)
        }
    }

    private class DrawNotMasked(context: Context?, var rect: Rect) : View(context) {

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

    //モデル読み込み(モデル，入手力のインタープリターを返す)
    private fun LoadModel(): Pair<FirebaseModelInterpreter?, FirebaseModelInputOutputOptions> {
        //カスタムモデルのローカルモデルを構成する
        val localModel = FirebaseCustomLocalModel.Builder()
                .setAssetFilePath("mask_detector.tflite")
                .build()

        //インタープリター
        val options = FirebaseModelInterpreterOptions.Builder(localModel).build()
        val interpreter = FirebaseModelInterpreter.getInstance(options)

        //インタープリターの入力と出力の形式，サイズを指定
        val inputOutputOptions = FirebaseModelInputOutputOptions.Builder()
                .setInputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 224, 224, 3))
                .setOutputFormat(0, FirebaseModelDataType.FLOAT32, intArrayOf(1, 2))
                .build()

        return Pair(interpreter, inputOutputOptions)
    }

    // 入力の画像をモデルの入力に合わせるように変換する(1,224,224,3)
    private fun InputImage(bitmap: Bitmap): FirebaseModelInputs {
        val roteBitmap = rotateBitmap(bitmap, 90) // 画像を90°回転
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

        return FirebaseModelInputs.Builder()
                .add(input)
                .build()
    }

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
        if (!mediaPlayer.isPlaying) {
            mediaPlayer.start()
        }
    }

    // crop画像の表示
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        mediaPlayer.release()
    }

    companion object {
        private const val TAG = "CameraXBasic"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}


