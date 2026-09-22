package com.example.aranimation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aranimation.adapter.ModelPickerAdapter
import com.example.aranimation.databinding.ActivityMainBinding
import com.example.aranimation.model.ARModelItem
import com.google.android.material.snackbar.Snackbar
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.PlacementMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Màn hình AR chính cấp độ Production:
 * 1. Giao diện phụ trợ (Overlay HUD):
 *    - TextView bán trong suốt phía trên màn hình hiển thị trạng thái hướng dẫn:
 *      "Di chuyển máy để tìm sàn..." -> "Chạm vào màn hình để đặt con vật".
 *    - Nút tròn FloatingActionButton (FAB) nhỏ góc trên bên phải để xóa mô hình hiện tại (Reset Scene).
 * 2. Luồng chọn và đổi nhanh giữa 6 mô hình 3D (Stag, Wolf, Bull, Cow, Deer, Shiba).
 * 3. Hỗ trợ cử chỉ tương tác Pinch-to-zoom (0.2x - 2.5x) và Twist/Rotate quanh trục Y.
 * 4. Quản lý vòng đời chặt chẽ và giải phóng bộ nhớ (Memory Cleanup):
 *    - onPause(): Tạm dừng ARSession, animation và giải phóng camera stream để tiết kiệm pin.
 *    - onResume(): Phục hồi phiên AR mượt mà.
 *    - onDestroy(): Dọn dẹp triệt để Node, Anchor, Coroutine Job và Filament Engine tránh rò rỉ bộ nhớ (OOM/Crash).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Danh sách 6 mô hình động vật mặc định
    private val modelList: List<ARModelItem> = ARModelItem.getDefaultList()
    private lateinit var modelPickerAdapter: ModelPickerAdapter

    // Mô hình 3D đang được chọn (khởi tạo mặc định: "stag.glb")
    private var currentSelectedItem: ARModelItem = modelList.first { it.isSelected }

    // Quản lý các Node trong không gian AR
    private var currentAnchorNode: AnchorNode? = null
    private var currentModelNode: ArModelNode? = null

    // Quản lý Coroutine Job nạp 3D Model
    private var modelLoadingJob: Job? = null

    // Cờ trạng thái đã neo mô hình trong thế giới thực hay chưa
    private var isModelPlaced: Boolean = false

    // Đăng ký nhận kết quả yêu cầu cấp quyền Camera Runtime
    private val cameraPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupARScene()
        } else {
            handlePermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModelPickerRecyclerView()
        setupListeners()
        checkCameraPermissionAndStart()
    }

    /**
     * Khởi tạo RecyclerView cuộn ngang chứa danh sách chọn 6 con vật
     */
    private fun setupModelPickerRecyclerView() {
        modelPickerAdapter = ModelPickerAdapter(
            itemList = modelList,
            onModelSelected = { selectedModel ->
                onUserSelectedModel(selectedModel)
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
     * Xử lý sự kiện khi người dùng click chọn một con vật khác trong RecyclerView
     */
    private fun onUserSelectedModel(selectedModel: ARModelItem) {
        currentSelectedItem = selectedModel

        val anchorNode = currentAnchorNode
        val oldModelNode = currentModelNode

        // TRƯỜNG HỢP 1: Đã có mô hình hiển thị trên mặt phẳng AR
        if (isModelPlaced && anchorNode != null && oldModelNode != null) {
            replaceModelOnCurrentAnchor(anchorNode, oldModelNode, selectedModel)
        } else {
            // TRƯỜNG HỢP 2: Chưa đặt mô hình, lần chạm tới sẽ nạp con vật này
            binding.tvInstruction.text = "Đã chọn ${selectedModel.displayName}. ${getString(R.string.status_plane_found)}"
        }
    }

    /**
     * Thay thế mô hình cũ bằng mô hình mới tại đúng vị trí và góc xoay, giải phóng bộ nhớ mô hình cũ
     */
    private fun replaceModelOnCurrentAnchor(
        anchorNode: AnchorNode,
        oldModelNode: ArModelNode,
        newModelItem: ARModelItem
    ) {
        val engine = binding.sceneView.engine

        // 1. Lưu lại các giá trị Transform (Position, Rotation, Scale) của mô hình cũ
        val savedPosition = oldModelNode.position
        val savedRotation = oldModelNode.rotation
        val savedScale = oldModelNode.scale

        // 2. GIẢI PHÓNG BỘ NHỚ CỦA MODEL CŨ (Tránh OOM khi đổi nhiều lần)
        anchorNode.removeChild(oldModelNode)
        oldModelNode.destroy()

        // 3. Khởi tạo node mới kế thừa lại vị trí và góc xoay
        val newModelNode = ArModelNode(
            engine = engine,
            placementMode = PlacementMode.PLANE_HORIZONTAL
        ).apply {
            position = savedPosition
            rotation = savedRotation
            scale = savedScale

            isPositionEditable = false
            isRotationEditable = true
            isScaleEditable = true
            minScale = 0.2f
            maxScale = 2.5f

            followHitPosition = false
        }

        anchorNode.addChild(newModelNode)
        currentModelNode = newModelNode

        binding.tvInstruction.text = "Đang đổi sang ${newModelItem.displayName}..."

        // 4. Tải model mới và kích hoạt Looping Animation
        loadAndAnimateModel(newModelNode, newModelItem.assetPath)
    }

    /**
     * Kiểm tra quyền Camera runtime trước khi bắt đầu phiên AR
     */
    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupARScene()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun handlePermissionDenied() {
        Toast.makeText(
            this,
            getString(R.string.camera_permission_required),
            Toast.LENGTH_LONG
        ).show()

        Snackbar.make(
            binding.root,
            "Ứng dụng cần quyền Camera để quét không gian AR. Vui lòng cấp quyền trong Cài đặt.",
            Snackbar.LENGTH_INDEFINITE
        ).setAction("Cài đặt") {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }.show()
    }

    /**
     * Cài đặt các sự kiện nút bấm giao diện
     */
    private fun setupListeners() {
        // Nút tròn FAB góc trên bên phải: Reset Scene để quét và đặt lại vị trí mới
        binding.fabReset.setOnClickListener {
            resetARScene()
        }
    }

    /**
     * Cấu hình ARSceneView và bắt sự kiện chạm Tap to Place
     */
    private fun setupARScene() {
        binding.sceneView.apply {
            planeRenderer.isVisible = true

            configureSession { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.focusMode = Config.FocusMode.AUTO
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            }

            // HUD OVERLAY: Cập nhật thông báo hướng dẫn theo trạng thái quét mặt phẳng
            onSessionUpdated = { _, frame ->
                if (!isModelPlaced) {
                    val hasTrackingPlane = frame.getUpdatedTrackables(Plane::class.java).any {
                        it.trackingState == TrackingState.TRACKING
                    }

                    if (hasTrackingPlane) {
                        binding.tvInstruction.text = getString(R.string.status_plane_found)
                        binding.loadingIndicator.visibility = View.GONE
                    } else {
                        binding.tvInstruction.text = getString(R.string.status_scan_plane)
                        binding.loadingIndicator.visibility = View.VISIBLE
                    }
                }
            }

            // Bắt sự kiện Tap để đặt mô hình tại vị trí va chạm
            onTap = { hitResult: HitResult? ->
                if (!isModelPlaced && hitResult != null) {
                    val trackable = hitResult.trackable
                    if (trackable is Plane && trackable.isPoseInPolygon(hitResult.hitPose)) {
                        handleTapToPlace(hitResult)
                    }
                }
            }

            onTouchAR = { _, _ ->
                false
            }
        }
    }

    /**
     * Đặt mô hình tại tọa độ va chạm (HitResult) của thế giới thực
     */
    private fun handleTapToPlace(hitResult: HitResult) {
        val engine = binding.sceneView.engine

        val anchor = hitResult.createAnchor()
        val anchorNode = AnchorNode(engine = engine, anchor = anchor)

        val modelNode = ArModelNode(
            engine = engine,
            placementMode = PlacementMode.PLANE_HORIZONTAL
        ).apply {
            isPositionEditable = false
            isRotationEditable = true
            isScaleEditable = true
            minScale = 0.2f
            maxScale = 2.5f
            followHitPosition = false
        }

        anchorNode.addChild(modelNode)
        binding.sceneView.addChild(anchorNode)

        currentAnchorNode = anchorNode
        currentModelNode = modelNode
        isModelPlaced = true

        // Ẩn lưới quét mặt phẳng
        binding.sceneView.planeRenderer.isVisible = false

        // Cập nhật trạng thái hướng dẫn HUD
        binding.tvInstruction.text = getString(R.string.status_model_placed, currentSelectedItem.displayName)
        binding.loadingIndicator.visibility = View.GONE

        // Tải file 3D từ assets và chạy animation lặp lại
        loadAndAnimateModel(modelNode, currentSelectedItem.assetPath)
    }

    /**
     * Nạp tệp .glb từ thư mục assets và kích hoạt Looping Animation
     */
    private fun loadAndAnimateModel(node: ArModelNode, assetPath: String) {
        modelLoadingJob?.cancel()

        modelLoadingJob = lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE

            node.loadModelGlbAsync(
                glbFileLocation = assetPath,
                autoAnimate = true,
                scaleToUnits = 0.5f,
                centerOrigin = null,
                onError = { exception ->
                    binding.loadingIndicator.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Lỗi tải ${currentSelectedItem.displayName}: ${exception.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onLoaded = { modelInstance ->
                    binding.loadingIndicator.visibility = View.GONE

                    // Kích hoạt Skeletal Animation lặp tuần hoàn vô tận
                    val animator = modelInstance.animator
                    if (animator.animationCount > 0) {
                        node.playAnimation(
                            animationIndex = 0,
                            loop = true
                        )
                    }

                    binding.tvInstruction.text = getString(R.string.status_model_placed, currentSelectedItem.displayName)
                }
            )
        }
    }

    /**
     * Xóa mô hình hiện tại (Reset Scene) và giải phóng bộ nhớ để người dùng chọn vị trí đặt mới
     */
    private fun resetARScene() {
        modelLoadingJob?.cancel()

        // Giải phóng triệt để node mô hình và điểm neo
        currentModelNode?.let { modelNode ->
            currentAnchorNode?.removeChild(modelNode)
            modelNode.destroy()
        }
        currentAnchorNode?.let { anchorNode ->
            binding.sceneView.removeChild(anchorNode)
            anchorNode.destroy()
        }
        currentAnchorNode = null
        currentModelNode = null
        isModelPlaced = false

        // Bật lại hiển thị lưới quét mặt phẳng
        binding.sceneView.planeRenderer.isVisible = true

        binding.loadingIndicator.visibility = View.VISIBLE
        binding.tvInstruction.text = getString(R.string.status_scan_plane)

        Toast.makeText(this, "Đã đặt lại không gian AR", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 2. QUẢN LÝ VÒNG ĐỜI VÀ BỘ NHỚ (LIFECYCLE & MEMORY CLEANUP)
    // =========================================================================

    /**
     * Tạm dừng session AR, dừng animation và giải phóng luồng camera khi app xuống background
     * nhằm tiết kiệm pin tối đa cho thiết bị di động.
     */
    override fun onPause() {
        super.onPause()
        // Dừng coroutine nạp model nếu đang chạy dang dở
        modelLoadingJob?.cancel()
        // Tạm dừng bộ dựng hình Filament và ARCore Session
        binding.sceneView.pause()
    }

    /**
     * Khôi phục phiên AR khi người dùng quay lại ứng dụng
     */
    override fun onResume() {
        super.onResume()
        binding.sceneView.resume()
    }

    /**
     * Dọn dẹp triệt để toàn bộ tài nguyên đồ họa 3D, nodes và engine khi Activity bị hủy,
     * ngăn ngừa hiện tượng rò rỉ bộ nhớ (Memory Leak / Out-Of-Memory).
     */
    override fun onDestroy() {
        super.onDestroy()
        modelLoadingJob?.cancel()

        // Hủy bỏ các nodes đang tham chiếu
        currentModelNode?.destroy()
        currentModelNode = null

        currentAnchorNode?.destroy()
        currentAnchorNode = null

        // Hủy toàn bộ engine Filament và ARCore Session
        binding.sceneView.destroy()
    }
}
