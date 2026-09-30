package com.example.aranimation

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aranimation.adapter.ModelPickerAdapter
import com.example.aranimation.databinding.ActivityMainBinding
import com.example.aranimation.model.ARModelItem
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Màn hình 3D Model Viewer thuần túy sử dụng thư viện Sceneview (Google Filament Engine).
 *
 * Tính năng chính:
 * 1. Hoạt động trên mọi thiết bị Android (không yêu cầu Google ARCore hay cảm biến phần cứng AR).
 * 2. Tự động nạp mô hình 3D mặc định ("stag.glb") ngay khi mở ứng dụng.
 * 3. Hỗ trợ thao tác cử chỉ mượt mà:
 *    - Vuốt 1 ngón tay: Xoay con vật theo mọi hướng (Orbit / Rotate).
 *    - Chụm/mở 2 ngón tay (Pinch): Phóng to / Thu nhỏ mô hình (Zoom in / Zoom out).
 * 4. Tự động kích hoạt Skeleton Animation chạy lặp tuần hoàn vô tận (loop = true).
 * 5. Thanh RecyclerView cuộn ngang hỗ trợ chuyển đổi linh hoạt giữa 6 con vật khác nhau.
 * 6. Quản lý bộ nhớ tối ưu (Zero Memory Leak): Hủy triệt để ModelNode cũ trước khi nạp model mới.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
    }

    // =========================================================================
    // 2. NẠP MÔ HÌNH 3D & ANIMATION (SCENEVIEW V2.2.1)
    // =========================================================================

    /**
     * Nạp mô hình 3D từ assets vào SceneView:
     * - Hủy job nạp cũ và giải phóng Node cũ để tránh tràn bộ nhớ.
     * - Nạp bất đồng bộ file .glb bằng [createModelInstance] / [loadModelInstance].
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
    // 3. QUẢN LÝ VÒNG ĐỜI & GIẢI PHÓNG BỘ NHỚ (LIFECYCLE & MEMORY CLEANUP)
    // =========================================================================

    override fun onPause() {
        super.onPause()
        modelLoadingJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        modelLoadingJob?.cancel()

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
