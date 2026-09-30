package com.example.aranimation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aranimation.adapter.ModelPickerAdapter
import com.example.aranimation.databinding.ActivityMainBinding
import com.example.aranimation.model.ARModelItem
import com.example.aranimation.recorder.VideoRecorder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Màn hình AR Camera kết hợp 3D Model Animation và AR Video Capture.
 *
 * Tính năng chính:
 * 1. Khung hình Camera thực tế (ARCore) hiển thị toàn màn hình kết hợp phát hiện mặt phẳng sàn (Plane Detection).
 * 2. Chạm vào mặt phẳng (Tap-to-Place): Tạo [AnchorNode] neo chặt mô hình 3D vào tọa độ thế giới thực (World Tracking).
 *    Người dùng có thể đứng vào khung hình cạnh con vật, lia máy xung quanh mà con vật không bị trôi nổi.
 * 3. Hỗ trợ thao tác cử chỉ xoay và phóng to/thu nhỏ mô hình bằng 2 ngón tay.
 * 4. Tự động kích hoạt Skeleton Animation lặp tuần hoàn.
 * 5. Thanh danh sách cuộn ngang hỗ trợ đổi linh hoạt giữa 6 con vật.
 * 6. QUAY VIDEO AR CHUẨN:
 *    - Ghi nhận trực tiếp từ Surface engine đồ họa Filament: Chỉ ghi Camera thực tế + Mô hình 3D + Bóng đổ.
 *    - TUYỆT ĐỐI KHÔNG dính giao diện (UI controls, buttons, timer).
 *    - Thu âm thanh môi trường qua Microphone.
 *    - Lưu tự động vào Thư viện ảnh (DCIM/ARAnimation) kèm quét MediaScanner.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Danh sách 6 mô hình động vật 3D từ assets
    private val modelList: List<ARModelItem> = ARModelItem.getDefaultList()
    private lateinit var modelPickerAdapter: ModelPickerAdapter

    // Mô hình đang được chọn (mặc định: "stag.glb")
    private var currentSelectedItem: ARModelItem = modelList.first { it.isSelected }

    // Quản lý AnchorNode (neo sàn thế giới thực) và ModelNode (con vật 3D)
    private var currentAnchorNode: AnchorNode? = null
    private var currentModelNode: ModelNode? = null

    // Trạng thái đã đặt con vật lên sàn hay chưa
    private var isModelPlaced: Boolean = false

    // Quản lý Coroutine Job nạp 3D Model bất đồng bộ
    private var modelLoadingJob: Job? = null

    // Quản lý trạng thái khởi tạo AR
    private var isARSceneSetup: Boolean = false

    // =========================================================================
    // VIDEO RECORDER & TIMER STATE
    // =========================================================================
    private lateinit var videoRecorder: VideoRecorder
    private var isRecording: Boolean = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartTime: Long = 0L

    // Runnable cập nhật đồng hồ đếm thời lượng quay
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsedMillis = SystemClock.uptimeMillis() - recordingStartTime
                val totalSeconds = (elapsedMillis / 1000).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                binding.tvRecordTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

                // Nhấp nháy chấm tròn ghi hình (blink dot)
                binding.viewBlinkDot.visibility = if ((totalSeconds % 2) == 0) View.VISIBLE else View.INVISIBLE

                timerHandler.postDelayed(this, 500)
            }
        }
    }

    // Permission Launcher cho CAMERA
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setupARScene()
        } else {
            showCameraPermissionDeniedDialog()
        }
    }

    // Permission Launcher cho RECORD_AUDIO
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVideoRecording(enableAudio = true)
        } else {
            Toast.makeText(
                this,
                getString(R.string.record_permission_required),
                Toast.LENGTH_SHORT
            ).show()
            // Vẫn cho phép quay video không có âm thanh nếu bị từ chối
            startVideoRecording(enableAudio = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Khởi tạo tiện ích quay video
        videoRecorder = VideoRecorder(context = this, sceneView = binding.sceneView)

        setupModelPickerRecyclerView()
        setupListeners()
        checkCameraPermissionAndStart()
    }

    // =========================================================================
    // 1. QUYỀN CAMERA & KHỞI TẠO AR SCENE
    // =========================================================================

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupARScene()
        } else {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Khởi tạo cấu hình ARCore và gắn bộ lắng nghe sự kiện
     */
    private fun setupARScene() {
        if (isARSceneSetup) return
        isARSceneSetup = true

        binding.sceneView.apply {
            // Cấu hình ARCore Session: Phát hiện mặt phẳng ngang (Horizontal Planes - sàn nhà/mặt đất)
            configureSession { session, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                config.focusMode = Config.FocusMode.AUTO
            }

            // Hiện lưới hỗ trợ nhận diện mặt phẳng sàn
            planeRenderer.isVisible = true

            // Cập nhật trạng thái HUD hướng dẫn theo thời gian thực
            onSessionUpdated = { _, frame ->
                if (!isModelPlaced && !isDestroyed) {
                    val hasTrackingPlane = frame.getUpdatedTrackables(Plane::class.java).any {
                        it.trackingState == TrackingState.TRACKING
                    }
                    runOnUiThread {
                        if (!isDestroyed && !isModelPlaced) {
                            if (hasTrackingPlane) {
                                binding.tvInstruction.text = getString(R.string.status_plane_found)
                                binding.loadingIndicator.visibility = View.GONE
                            } else {
                                binding.tvInstruction.text = getString(R.string.status_scan_plane)
                                binding.loadingIndicator.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }

            // Bắt sự kiện Tap-to-Place qua GestureDetector
            setOnGestureListener(
                onSingleTapConfirmed = { e: MotionEvent, node: Node? ->
                    if (!isModelPlaced) {
                        // hitTestAR: Bắn tia từ điểm chạm vào không gian AR để tìm mặt sàn
                        val hitResult: HitResult? = binding.sceneView.hitTestAR(e.x, e.y)
                        if (hitResult != null) {
                            val trackable = hitResult.trackable
                            if (trackable is Plane && trackable.isPoseInPolygon(hitResult.hitPose)) {
                                handleTapToPlace(hitResult)
                            }
                        }
                    }
                }
            )
        }
    }

    // =========================================================================
    // 2. TAP-TO-PLACE & ĐẶT MÔ HÌNH VÀO KHÔNG GIAN THỰC TẾ
    // =========================================================================

    /**
     * Tạo Anchor tại điểm chạm sàn và gắn mô hình con vật vào không gian
     */
    private fun handleTapToPlace(hitResult: HitResult) {
        // Tạo Anchor neo cố định vào mặt sàn thực tế
        val anchor = hitResult.createAnchor()
        val anchorNode = AnchorNode(binding.sceneView.engine, anchor)

        currentAnchorNode = anchorNode
        isModelPlaced = true

        // Ẩn lưới quét mặt phẳng sau khi đã neo mô hình
        binding.sceneView.planeRenderer.isVisible = false

        // Đưa AnchorNode vào SceneView
        binding.sceneView.addChildNode(anchorNode)

        // Nạp mô hình 3D và gắn vào AnchorNode
        loadModelAndAttach(anchorNode, currentSelectedItem)
    }

    /**
     * Nạp file .glb bất đồng bộ từ assets và gắn vào AnchorNode
     */
    private fun loadModelAndAttach(anchorNode: AnchorNode, modelItem: ARModelItem) {
        modelLoadingJob?.cancel()

        binding.loadingIndicator.visibility = View.VISIBLE
        binding.tvInstruction.text = getString(R.string.status_loading_model, modelItem.displayName)

        modelLoadingJob = lifecycleScope.launch {
            try {
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

                // Khởi tạo ModelNode với kích thước chuẩn hóa 0.5m phù hợp không gian AR
                val modelNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = 0.5f
                ).apply {
                    // Cho phép người dùng chạm để xoay hoặc phóng to/thu nhỏ
                    isEditable = true
                    isRotationEditable = true
                    isScaleEditable = true
                }

                // Tự động kích hoạt Skeleton Animation lặp tuần hoàn
                if (modelInstance.animator.animationCount > 0) {
                    modelNode.playAnimation(animationIndex = 0, loop = true)
                }

                // Gắn con vật vào AnchorNode (neo sàn)
                anchorNode.addChildNode(modelNode)
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

    /**
     * Chuyển đổi con vật khác khi người dùng chọn trên RecyclerView
     */
    private fun switchModel(newModelItem: ARModelItem) {
        currentSelectedItem = newModelItem

        val anchorNode = currentAnchorNode
        if (anchorNode == null || !isModelPlaced) {
            // Chưa đặt sàn thì lưu lựa chọn và thông báo
            Toast.makeText(
                this,
                "Đã chọn ${newModelItem.displayName}. Chạm vào sàn để đặt!",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // Gỡ bỏ con vật cũ và nạp con vật mới vào cùng vị trí AnchorNode
        currentModelNode?.let { oldNode ->
            anchorNode.removeChildNode(oldNode)
            try {
                oldNode.destroy()
            } catch (_: Exception) {}
            currentModelNode = null
        }

        loadModelAndAttach(anchorNode, newModelItem)
    }

    /**
     * Đặt lại Scene để quét sàn và đặt con vật ở vị trí mới
     */
    private fun resetScene() {
        modelLoadingJob?.cancel()

        currentModelNode?.let { node ->
            currentAnchorNode?.removeChildNode(node)
            try { node.destroy() } catch (_: Exception) {}
            currentModelNode = null
        }

        currentAnchorNode?.let { anchor ->
            binding.sceneView.removeChildNode(anchor)
            try { anchor.destroy() } catch (_: Exception) {}
            currentAnchorNode = null
        }

        isModelPlaced = false
        binding.sceneView.planeRenderer.isVisible = true
        binding.tvInstruction.text = getString(R.string.status_scan_plane)
        binding.loadingIndicator.visibility = View.VISIBLE

        Toast.makeText(this, getString(R.string.action_reset), Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 3. LOGIC QUAY VIDEO AR (START / STOP / MEDIASTORE)
    // =========================================================================

    private fun toggleVideoRecording() {
        if (!isRecording) {
            checkAudioPermissionAndStartRecording()
        } else {
            stopVideoRecording()
        }
    }

    private fun checkAudioPermissionAndStartRecording() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            startVideoRecording(enableAudio = true)
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVideoRecording(enableAudio: Boolean) {
        val started = videoRecorder.startRecording(enableAudio = enableAudio)
        if (started) {
            isRecording = true

            // Đổi giao diện nút quay sang Stop (Active)
            binding.btnRecord.setBackgroundResource(R.drawable.bg_record_active)

            // Hiển thị HUD đếm thời gian
            binding.cardRecordTimer.visibility = View.VISIBLE
            binding.tvRecordTimer.text = "00:00"
            recordingStartTime = SystemClock.uptimeMillis()
            timerHandler.post(timerRunnable)

            Toast.makeText(this, getString(R.string.record_started), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.record_saved_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopVideoRecording() {
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)

        // Đổi giao diện nút quay về trạng thái chờ
        binding.btnRecord.setBackgroundResource(R.drawable.bg_record_idle)
        binding.cardRecordTimer.visibility = View.GONE

        // Dừng ghi hình và lưu vào MediaStore
        videoRecorder.stopRecording { savedUri ->
            runOnUiThread {
                if (savedUri != null) {
                    Toast.makeText(
                        this,
                        getString(R.string.record_saved_success),
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
            .setMessage("Video AR chất lượng cao đã được lưu vào Bộ sưu tập (DCIM/ARAnimation). Bạn có muốn mở xem ngay không?")
            .setPositiveButton(getString(R.string.record_action_view)) { _, _ ->
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(videoUri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    startActivity(viewIntent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Không tìm thấy ứng dụng phát video thích hợp", Toast.LENGTH_SHORT).show()
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
    // 4. GIAO DIỆN & NÚT BẤM
    // =========================================================================

    private fun setupModelPickerRecyclerView() {
        modelPickerAdapter = ModelPickerAdapter(
            itemList = modelList,
            onModelSelected = { selectedModel ->
                switchModel(selectedModel)
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
        binding.fabReset.setOnClickListener {
            resetScene()
        }

        binding.btnRecord.setOnClickListener {
            toggleVideoRecording()
        }
    }

    private fun showCameraPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.camera_permission_required))
            .setMessage("Ứng dụng cần quyền Camera để hiển thị không gian AR. Vui lòng cấp quyền trong Cài đặt.")
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
    // 5. LIFECYCLE & GIẢI PHÓNG BỘ NHỚ
    // =========================================================================

    override fun onResume() {
        super.onResume()
        if (!isARSceneSetup &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupARScene()
        }
    }

    override fun onPause() {
        super.onPause()
        modelLoadingJob?.cancel()

        // Tự động dừng quay và lưu video an toàn khi thoát app hoặc bấm Home
        if (isRecording) {
            stopVideoRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        modelLoadingJob?.cancel()
        timerHandler.removeCallbacks(timerRunnable)

        videoRecorder.cancelRecording()

        currentModelNode?.let { node ->
            try { node.destroy() } catch (_: Exception) {}
            currentModelNode = null
        }

        currentAnchorNode?.let { anchor ->
            try { anchor.destroy() } catch (_: Exception) {}
            currentAnchorNode = null
        }

        try {
            binding.sceneView.destroy()
        } catch (_: Exception) {}
    }
}
