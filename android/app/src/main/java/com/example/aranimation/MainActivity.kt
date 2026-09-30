package com.example.aranimation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aranimation.adapter.ModelPickerAdapter
import com.example.aranimation.databinding.ActivityMainBinding
import com.example.aranimation.model.ARModelItem
import com.example.aranimation.recorder.Camera3DRecorder
import com.google.android.filament.View as FilamentView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Màn hình NATIVE CAMERA RECORDING kết hợp 3D Model Overlay:
 * - TUYỆT ĐỐI KHÔNG dùng MediaProjection, không có pop-up hệ thống hỏi quyền ghi màn hình.
 * - Bấm quay là quay ngay lập tức giống TikTok / Instagram.
 * - Video chỉ gồm luồng Camera thực tế + Mô hình 3D từ [captureContainer], hoàn toàn loại bỏ giao diện UI.
 * - Hỗ trợ đổi Camera Trước/Sau, xoay/zoom/kéo rê con vật đặt cạnh người thật và animation cử động lặp vô tận.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding

    // Danh sách 6 mô hình động vật 3D từ assets
    private val modelList: List<ARModelItem> = ARModelItem.getDefaultList()
    private lateinit var modelPickerAdapter: ModelPickerAdapter

    // Mô hình đang được chọn (mặc định: con vật đầu tiên "stag.glb")
    private var currentSelectedItem: ARModelItem = modelList.first { it.isSelected }

    // Quản lý nút mô hình 3D trên SceneView
    private var currentModelNode: ModelNode? = null

    // Quản lý Coroutine Job nạp 3D Model bất đồng bộ
    private var modelLoadingJob: Job? = null

    // =========================================================================
    // CAMERAX STATE
    // =========================================================================
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private lateinit var cameraExecutor: ExecutorService

    // =========================================================================
    // NATIVE 3D RECORDER & TIMER STATE
    // =========================================================================
    private lateinit var camera3DRecorder: Camera3DRecorder
    private var isRecording: Boolean = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartTime: Long = 0L

    // Cập nhật nhãn thời lượng quay (00:01, 00:02...)
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsedMillis = SystemClock.uptimeMillis() - recordingStartTime
                val totalSeconds = (elapsedMillis / 1000).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                binding.tvRecordTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

                // Nhấp nháy chấm đỏ ghi hình
                binding.viewBlinkDot.visibility = if ((totalSeconds % 2) == 0) View.VISIBLE else View.INVISIBLE

                timerHandler.postDelayed(this, 500)
            }
        }
    }

    // Permission Launcher cho CAMERA và RECORD_AUDIO
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true

        if (cameraGranted) {
            startCamera()
        } else {
            showPermissionDeniedDialog()
        }

        if (!audioGranted) {
            Toast.makeText(
                this,
                getString(R.string.record_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 1. Cấu hình SceneView nền trong suốt nằm trong captureContainer
        setupTransparentSceneView()

        // 2. Khởi tạo Camera3DRecorder ghi hình Native từ captureContainer
        camera3DRecorder = Camera3DRecorder(
            context = this,
            captureContainer = binding.captureContainer,
            cameraPreview = binding.cameraPreview,
            sceneView = binding.sceneView
        )

        // 3. Khởi tạo UI điều khiển
        setupModelPickerRecyclerView()
        setupListeners()

        // 4. Kiểm tra quyền và khởi chạy Camera
        checkAndRequestPermissions()

        // 5. Nạp mô hình 3D mặc định
        loadModel(currentSelectedItem)
    }

    // =========================================================================
    // 1. CẤU HÌNH SCENEVIEW NỀN TRONG SUỐT (TRANSPARENT OVERLAY)
    // =========================================================================

    private fun setupTransparentSceneView() {
        val sceneView = binding.sceneView

        try {
            if (sceneView is SurfaceView) {
                sceneView.holder.setFormat(PixelFormat.TRANSLUCENT)
                sceneView.setZOrderMediaOverlay(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi thiết lập SurfaceView TRANSLUCENT: ${e.message}")
        }

        try {
            sceneView.view.blendMode = FilamentView.BlendMode.TRANSLUCENT
            sceneView.renderer.clearOptions = sceneView.renderer.clearOptions.apply {
                clear = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi thiết lập Filament TRANSLUCENT: ${e.message}")
        }

        setupModelDragGesture()
    }

    /**
     * Hỗ trợ chạm kéo 1 ngón tay trên màn hình để di chuyển con vật đứng cạnh người
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupModelDragGesture() {
        var lastTouchX = 0f
        var lastTouchY = 0f
        var isDragging = false

        binding.sceneView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    isDragging = true
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging && event.pointerCount == 1) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY

                        currentModelNode?.let { node ->
                            val sensitivity = 0.0015f
                            val currentPos = node.position
                            node.position = Position(
                                x = currentPos.x + dx * sensitivity,
                                y = currentPos.y - dy * sensitivity,
                                z = currentPos.z
                            )
                        }

                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    false
                }
                else -> false
            }
        }
    }

    // =========================================================================
    // 2. KHỞI TẠO VÀ ĐỔI CAMERAX TRƯỚC / SAU
    // =========================================================================

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            startCamera()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi lấy CameraProvider: ${e.message}", e)
                Toast.makeText(this, "Không thể kết nối camera: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                cameraSelector,
                preview
            )
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi gắn Camera UseCase: ${e.message}", e)
            Toast.makeText(this, "Lỗi mở camera: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        bindCameraUseCases()
        val cameraName = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "Camera sau" else "Camera trước"
        Toast.makeText(this, "Đã chuyển sang $cameraName", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 3. NẠP MÔ HÌNH 3D & ANIMATION LẶP VÔ TẬN
    // =========================================================================

    private fun loadModel(modelItem: ARModelItem) {
        modelLoadingJob?.cancel()

        currentModelNode?.let { oldNode ->
            binding.sceneView.removeChildNode(oldNode)
            try {
                oldNode.destroy()
            } catch (_: Exception) {}
            currentModelNode = null
        }

        binding.loadingIndicator.visibility = View.VISIBLE
        binding.tvInstruction.text = getString(R.string.status_loading_model, modelItem.displayName)

        modelLoadingJob = lifecycleScope.launch {
            try {
                val assetExists = assets.list("")?.contains(modelItem.assetPath) == true
                if (!assetExists) {
                    binding.loadingIndicator.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Không tìm thấy file: ${modelItem.assetPath} trong assets",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val modelInstance = binding.sceneView.modelLoader.createModelInstance(
                    assetFileLocation = modelItem.assetPath
                )

                if (modelInstance == null) {
                    binding.loadingIndicator.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Lỗi nạp ${modelItem.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val modelNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = 0.6f
                ).apply {
                    position = Position(x = 0.0f, y = -0.3f, z = 0.0f)
                    isEditable = true
                    isRotationEditable = true
                    isScaleEditable = true
                }

                if (modelInstance.animator.animationCount > 0) {
                    modelNode.playAnimation(animationIndex = 0, loop = true)
                }

                binding.sceneView.addChildNode(modelNode)
                currentModelNode = modelNode

                binding.loadingIndicator.visibility = View.GONE
                binding.tvInstruction.text = getString(R.string.status_model_placed, modelItem.displayName)

            } catch (e: Exception) {
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(
                    this@MainActivity,
                    "Lỗi nạp mô hình: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun resetModelPosition() {
        currentModelNode?.let { node ->
            node.position = Position(x = 0.0f, y = -0.3f, z = 0.0f)
            Toast.makeText(this, getString(R.string.action_reset), Toast.LENGTH_SHORT).show()
        } ?: run {
            loadModel(currentSelectedItem)
        }
    }

    // =========================================================================
    // 4. QUAY VIDEO NATIVE (KHÔNG MEDIA PROJECTION - BẤM QUAY LÀ CHẠY NGAY)
    // =========================================================================

    private fun toggleVideoRecording() {
        if (!isRecording) {
            startNativeRecording()
        } else {
            stopNativeRecording()
        }
    }

    private fun startNativeRecording() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val started = camera3DRecorder.start(enableAudio = hasAudioPermission)
        if (started) {
            isRecording = true

            // Đổi nút quay sang Stop icon
            binding.btnRecord.setBackgroundResource(R.drawable.bg_record_active)

            // Kích hoạt đồng hồ đếm thời gian
            binding.cardRecordTimer.visibility = View.VISIBLE
            binding.tvRecordTimer.text = "00:00"
            recordingStartTime = SystemClock.uptimeMillis()
            timerHandler.post(timerRunnable)

            Toast.makeText(this, getString(R.string.record_started), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.record_saved_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopNativeRecording() {
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)

        // Trả giao diện về bình thường
        binding.btnRecord.setBackgroundResource(R.drawable.bg_record_idle)
        binding.cardRecordTimer.visibility = View.GONE

        camera3DRecorder.stop { savedUri ->
            runOnUiThread {
                if (savedUri != null) {
                    Toast.makeText(
                        this,
                        "Đã lưu video vào Bộ sưu tập: $savedUri",
                        Toast.LENGTH_LONG
                    ).show()
                    showVideoSavedDialog(savedUri)
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.record_saved_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun showVideoSavedDialog(videoUri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.record_saved_success))
            .setMessage("Video Camera + 3D Model đã được lưu vào Bộ sưu tập (DCIM/ARVideo). Bạn có muốn mở xem ngay không?")
            .setPositiveButton(getString(R.string.record_action_view)) { _, _ ->
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(videoUri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(viewIntent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Không tìm thấy trình phát video", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(getString(R.string.record_action_share)) { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, videoUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.record_action_share)))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    // =========================================================================
    // 5. GIAO DIỆN & BỘ LẮNG NGHE
    // =========================================================================

    private fun setupModelPickerRecyclerView() {
        modelPickerAdapter = ModelPickerAdapter(
            itemList = modelList,
            onModelSelected = { selectedModel ->
                currentSelectedItem = selectedModel
                loadModel(selectedModel)
            }
        )

        binding.rvModelPicker.apply {
            layoutManager = LinearLayoutManager(
                this@MainActivity,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            adapter = modelPickerAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupListeners() {
        binding.fabSwitchCamera.setOnClickListener {
            switchCamera()
        }

        binding.fabReset.setOnClickListener {
            resetModelPosition()
        }

        binding.btnRecord.setOnClickListener {
            toggleVideoRecording()
        }
    }

    private fun showPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.camera_permission_required))
            .setMessage("Ứng dụng cần quyền Camera để ghi nhận hình ảnh thực tế. Vui lòng cấp quyền trong Cài đặt.")
            .setPositiveButton("Mở Cài đặt") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Thoát") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // =========================================================================
    // 6. LIFECYCLE & GIẢI PHÓNG BỘ NHỚ
    // =========================================================================

    override fun onPause() {
        super.onPause()
        modelLoadingJob?.cancel()

        if (isRecording) {
            stopNativeRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        modelLoadingJob?.cancel()
        timerHandler.removeCallbacks(timerRunnable)

        camera3DRecorder.cancel()
        cameraExecutor.shutdown()

        currentModelNode?.let { node ->
            try {
                binding.sceneView.removeChildNode(node)
                node.destroy()
            } catch (_: Exception) {}
            currentModelNode = null
        }

        try {
            binding.sceneView.destroy()
        } catch (_: Exception) {}
    }
}
