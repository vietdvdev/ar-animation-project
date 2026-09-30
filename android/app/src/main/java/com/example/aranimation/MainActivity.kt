package com.example.aranimation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Màn hình 3D Model Viewer thuần túy kết hợp Video Recording.
 *
 * Tính năng chính:
 * 1. Hoạt động trên mọi thiết bị Android (không yêu cầu Google ARCore).
 * 2. Tự động nạp mô hình 3D mặc định ("stag.glb") ngay khi mở ứng dụng.
 * 3. Hỗ trợ thao tác cử chỉ: xoay (orbit) và zoom (pinch).
 * 4. Tự động kích hoạt Skeleton Animation lặp tuần hoàn.
 * 5. Thanh RecyclerView chọn linh hoạt 6 con vật khác nhau.
 * 6. QUAY VIDEO (Video Recording):
 *    - Ghi lại toàn bộ khung cảnh 3D thành file MP4 chuẩn H.264/AAC.
 *    - Tự động xuất video vào MediaStore (Gallery).
 *    - Đồng hồ đếm thời lượng quay thời gian thực (00:15).
 *    - Dialog xem lại hoặc chia sẻ video ngay sau khi hoàn tất.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Danh sách 6 mô hình động vật 3D từ assets
    private val modelList: List<ARModelItem> = ARModelItem.getDefaultList()
    private lateinit var modelPickerAdapter: ModelPickerAdapter

    // Mô hình 3D đang được chọn (mặc định: con vật đầu tiên "stag.glb")
    private var currentSelectedItem: ARModelItem = modelList.first { it.isSelected }

    // Quản lý nút mô hình 3D hiện tại trên SceneView
    private var currentModelNode: ModelNode? = null

    // Quản lý Coroutine Job nạp 3D Model bất đồng bộ để tránh xung đột
    private var modelLoadingJob: Job? = null

    // =========================================================================
    // VIDEO RECORDER & TIMER STATE
    // =========================================================================
    private lateinit var videoRecorder: VideoRecorder
    private var isRecording: Boolean = false

    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordingStartTime: Long = 0L

    // Runnable cập nhật đồng hồ đếm thời lượng quay và hiệu ứng nhấp nháy
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsedMillis = SystemClock.uptimeMillis() - recordingStartTime
                val totalSeconds = (elapsedMillis / 1000).toInt()
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                binding.tvRecordTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

                // Hiệu ứng nhấp nháy chấm tròn ghi hình (blink dot)
                binding.viewBlinkDot.visibility = if ((totalSeconds % 2) == 0) View.VISIBLE else View.INVISIBLE

                timerHandler.postDelayed(this, 500)
            }
        }
    }

    // Launcher xin quyền RECORD_AUDIO trước khi quay
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
            // Vẫn cho phép quay video không có tiếng
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

        // Tự động nạp ngay mô hình mặc định khi khởi động ứng dụng
        loadModel(currentSelectedItem)
    }

    // =========================================================================
    // 1. GIAO DIỆN & DANH SÁCH CHỌN MÔ HÌNH (RECYCLERVIEW)
    // =========================================================================

    /**
     * Khởi tạo RecyclerView cuộn ngang chứa danh sách chọn 6 con vật
     */
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

    /**
     * Cài đặt các sự kiện nút bấm giao diện
     */
    private fun setupListeners() {
        // Nút Reset: Nạp lại mô hình hiện tại về vị trí và tỷ lệ chuẩn ban đầu
        binding.fabReset.setOnClickListener {
            loadModel(currentSelectedItem)
            Toast.makeText(this, getString(R.string.action_reset), Toast.LENGTH_SHORT).show()
        }

        // Nút Quay video nổi tròn
        binding.btnRecord.setOnClickListener {
            toggleVideoRecording()
        }
    }

    // =========================================================================
    // 2. LOGIC QUAY VIDEO (START / STOP / SAVE / SHARE)
    // =========================================================================

    /**
     * Chuyển đổi trạng thái Bắt đầu hoặc Dừng quay video
     */
    private fun toggleVideoRecording() {
        if (!isRecording) {
            checkPermissionAndStartRecording()
        } else {
            stopVideoRecording()
        }
    }

    /**
     * Kiểm tra quyền RECORD_AUDIO trước khi quay
     */
    private fun checkPermissionAndStartRecording() {
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

    /**
     * Bắt đầu ghi hình video
     */
    private fun startVideoRecording(enableAudio: Boolean) {
        val started = videoRecorder.startRecording(enableAudio = enableAudio)
        if (started) {
            isRecording = true

            // Cập nhật giao diện nút quay sang Stop icon
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

    /**
     * Dừng ghi hình và lưu video vào MediaStore
     */
    private fun stopVideoRecording() {
        isRecording = false
        timerHandler.removeCallbacks(timerRunnable)

        // Cập nhật giao diện nút quay về trạng thái chờ
        binding.btnRecord.setBackgroundResource(R.drawable.bg_record_idle)
        binding.cardRecordTimer.visibility = View.GONE

        // Dừng và lưu file
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

    /**
     * Hiển thị popup cho phép người dùng xem ngay hoặc chia sẻ video vừa quay
     */
    private fun showVideoSavedDialog(videoUri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.record_saved_success))
            .setMessage("Video 3D đã được lưu an toàn vào Thư viện ảnh (Gallery). Bạn có muốn mở xem ngay không?")
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
    // 3. NẠP MÔ HÌNH 3D & ANIMATION (SCENEVIEW V2.2.1)
    // =========================================================================

    /**
     * Nạp mô hình 3D từ assets vào SceneView:
     * - Hủy job nạp cũ và giải phóng Node cũ để tránh tràn bộ nhớ.
     * - Nạp bất đồng bộ file .glb bằng [createModelInstance].
     * - Tạo [ModelNode] với kích thước chuẩn hóa [scaleToUnits = 0.5f].
     * - Bật tương tác cử chỉ xoay và phóng to [isEditable = true].
     * - Kích hoạt animation chạy lặp tuần hoàn [playAnimation].
     * - Đưa Node vào [binding.sceneView.addChildNode].
     *
     * @param modelItem Dữ liệu con vật được chọn
     */
    private fun loadModel(modelItem: ARModelItem) {
        // 1. Hủy bỏ tác vụ nạp mô hình đang thực hiện dở dang (nếu có)
        modelLoadingJob?.cancel()

        // 2. Gỡ bỏ và giải phóng bộ nhớ của Node cũ khỏi SceneView
        currentModelNode?.let { oldNode ->
            binding.sceneView.removeChildNode(oldNode)
            try {
                oldNode.destroy()
            } catch (_: Exception) {}
            currentModelNode = null
        }

        // Cập nhật trạng thái HUD hiển thị
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.tvInstruction.text = getString(R.string.status_loading_model, modelItem.displayName)

        // 3. Khởi chạy Coroutine nạp mô hình bất đồng bộ
        modelLoadingJob = lifecycleScope.launch {
            try {
                // Kiểm tra sự tồn tại của tệp trong assets trước khi nạp
                val assetExists = assets.list("")?.contains(modelItem.assetPath) == true
                if (!assetExists) {
                    binding.loadingIndicator.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Không tìm thấy tệp: ${modelItem.assetPath} trong assets",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // Nạp ModelInstance từ assets
                val modelInstance = binding.sceneView.modelLoader.createModelInstance(
                    assetFileLocation = modelItem.assetPath
                )

                if (modelInstance == null) {
                    binding.loadingIndicator.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Lỗi nạp ${modelItem.displayName}: Không thể khởi tạo ModelInstance",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                // Khởi tạo ModelNode với kích thước hiển thị cân đối trong không gian 3D
                val modelNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = 0.5f // Chuẩn hóa kích thước vừa vặn trong màn hình
                ).apply {
                    // Đặt vị trí mô hình tại tâm của không gian quan sát
                    position = Position(x = 0.0f, y = -0.1f, z = 0.0f)

                    // Bật cử chỉ tương tác 3D: cho phép chạm vuốt để xoay và phóng to/thu nhỏ
                    isEditable = true
                    isRotationEditable = true
                    isScaleEditable = true
                }

                // Tự động kích hoạt Skeleton Animation chạy lặp tuần hoàn vô tận
                if (modelInstance.animator.animationCount > 0) {
                    modelNode.playAnimation(animationIndex = 0, loop = true)
                }

                // Đưa mô hình vào SceneView để hiển thị
                binding.sceneView.addChildNode(modelNode)
                currentModelNode = modelNode

                // Cập nhật giao diện HUD hướng dẫn cử chỉ
                binding.loadingIndicator.visibility = View.GONE
                binding.tvInstruction.text =
                    getString(R.string.status_model_placed, modelItem.displayName)

            } catch (e: Exception) {
                binding.loadingIndicator.visibility = View.GONE
                Toast.makeText(
                    this@MainActivity,
                    "Ngoại lệ khi nạp 3D Model: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // =========================================================================
    // 4. QUẢN LÝ VÒNG ĐỜI & GIẢI PHÓNG BỘ NHỚ (LIFECYCLE & MEMORY CLEANUP)
    // =========================================================================

    override fun onPause() {
        super.onPause()
        modelLoadingJob?.cancel()

        // Tự động dừng quay và lưu video an toàn khi người dùng thoát app hoặc nhấn Home
        if (isRecording) {
            stopVideoRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        modelLoadingJob?.cancel()
        timerHandler.removeCallbacks(timerRunnable)

        // Hủy bỏ quay khẩn cấp nếu còn sót
        videoRecorder.cancelRecording()

        // Giải phóng ModelNode hiện tại
        currentModelNode?.let { node ->
            try {
                binding.sceneView.removeChildNode(node)
                node.destroy()
            } catch (_: Exception) {}
            currentModelNode = null
        }

        // Dọn dẹp tài nguyên SceneView và Filament Engine an toàn
        try {
            binding.sceneView.destroy()
        } catch (_: Exception) {}
    }
}
