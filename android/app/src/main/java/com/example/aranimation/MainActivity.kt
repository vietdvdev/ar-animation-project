package com.example.aranimation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aranimation.adapter.ModelPickerAdapter
import com.example.aranimation.databinding.ActivityMainBinding
import com.example.aranimation.model.ARModelItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.Config
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Màn hình AR chính cấp độ Production (Kiểm thử & QA hoàn thiện):
 * 1. Quản lý quyền Camera Runtime chặt chẽ:
 *    - Hỗ trợ cả trường hợp từ chối thông thường (Denied) và từ chối vĩnh viễn (Don't ask again).
 *    - Dialog giải thích minh bạch điều hướng người dùng mở App Settings mà không làm crash app.
 * 2. Nạp tài nguyên 3D & Xử lý ngoại lệ (Assets Loading & Exception Handling):
 *    - Kiểm tra tính hợp lệ của tệp assets trước khi nạp.
 *    - Bọc khối xử lý try-catch rõ ràng để tránh crash nếu file 3D hỏng hoặc thiếu.
 * 3. AR Lifecycle & Dọn dẹp bộ nhớ (Zero Memory Leak & No Model Overlap):
 *    - Khi đổi mô hình trên sân: Hủy triệt để và gỡ node cũ khỏi Scene/Anchor trước khi nạp model mới.
 *    - onPause(), onResume(), onDestroy() giải phóng engine, luồng camera và coroutine.
 * 4. Tương tác cử chỉ & Animation:
 *    - scaleToUnits = 0.5f chuẩn hóa kích thước vừa vặn trong phòng.
 *    - Animation Controller kích hoạt loop vô tận và bảo toàn liên tục trong lúc Pinch/Rotate.
 *
 * API: io.github.sceneview:arsceneview:2.2.1
 *   - AnchorNode(engine, anchor)               — neo mô hình vào thế giới thực
 *   - ModelNode(modelInstance, scaleToUnits)   — hiển thị mô hình 3D
 *   - sceneView.modelLoader.loadModelInstance  — nạp .glb bất đồng bộ (suspend)
 *   - modelNode.playAnimation(index, loop)     — phát skeleton animation
 *   - addChildNode / removeChildNode           — API v2.x (KHÔNG dùng addChild/removeChild)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Danh sách 6 mô hình động vật mặc định từ assets
    private val modelList: List<ARModelItem> = ARModelItem.getDefaultList()
    private lateinit var modelPickerAdapter: ModelPickerAdapter

    // Mô hình 3D đang được chọn (khởi tạo mặc định: "stag.glb")
    private var currentSelectedItem: ARModelItem = modelList.first { it.isSelected }

    // ─── Sceneview v2.2.1 Node Graph ─────────────────────────────────────────
    // AnchorNode : neo toạ độ thế giới thực — cha của ModelNode
    // ModelNode  : giữ instance .glb đã được nạp vào Filament Engine
    private var currentAnchorNode: AnchorNode? = null
    private var currentModelNode: ModelNode? = null

    // Quản lý Coroutine Job nạp 3D Model để tránh xung đột
    private var modelLoadingJob: Job? = null

    // Cờ trạng thái đã neo mô hình trong thế giới thực hay chưa
    private var isModelPlaced: Boolean = false

    // Cờ đánh dấu ARScene đã được cấu hình đầy đủ chưa (hỗ trợ luồng cấp quyền muộn)
    private var isARSceneSetup: Boolean = false

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

    // =========================================================================
    // 0. UI — RecyclerView chọn mô hình
    // =========================================================================

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

        // TRƯỜNG HỢP 1: Đã có mô hình hiển thị trên mặt phẳng AR → swap in-place
        if (isModelPlaced && anchorNode != null) {
            replaceModelOnCurrentAnchor(anchorNode, selectedModel)
        } else {
            // TRƯỜNG HỢP 2: Chưa đặt mô hình, lần chạm tới sẽ nạp con vật này
            binding.tvInstruction.text =
                "Đã chọn ${selectedModel.displayName}. ${getString(R.string.status_plane_found)}"
        }
    }

    // =========================================================================
    // 1. QUẢN LÝ QUYỀN CAMERA
    // =========================================================================

    private fun checkCameraPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupARScene()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * Xử lý khi bị từ chối quyền Camera: Phân biệt từ chối thường và từ chối vĩnh viễn
     */
    private fun handlePermissionDenied() {
        val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.CAMERA
        )

        if (showRationale) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Yêu cầu quyền máy ảnh")
                .setMessage(
                    "Ứng dụng cần quyền Camera để quét không gian " +
                    "và hiển thị vật thể thực tế ảo (AR)."
                )
                .setPositiveButton("Cấp quyền") { _, _ ->
                    cameraPermissionRequest.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton("Thoát ứng dụng") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("Quyền Camera bị vô hiệu hóa")
                .setMessage(
                    "Bạn đã từ chối quyền truy cập máy ảnh. " +
                    "Vui lòng vào Cài đặt ứng dụng để bật quyền Camera thủ công."
                )
                .setPositiveButton("Mở Cài đặt") { _, _ -> openAppSettings() }
                .setNegativeButton("Đóng") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    // =========================================================================
    // 2. CÀI ĐẶT AR SCENE (Sceneview v2.2.1)
    // =========================================================================

    /**
     * Cài đặt các sự kiện nút bấm giao diện
     */
    private fun setupListeners() {
        binding.fabReset.setOnClickListener { resetARScene() }
    }

    /**
     * Cấu hình ARSceneView, session AR và bắt sự kiện chạm Tap-to-Place.
     */
    private fun setupARScene() {
        isARSceneSetup = true
        binding.sceneView.apply {
            planeRenderer.isVisible = true

            configureSession { _, config ->
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.focusMode = Config.FocusMode.AUTO
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            }

            // HUD OVERLAY: Cập nhật thông báo hướng dẫn theo trạng thái quét mặt phẳng
            // Guard isDestroyed để tránh crash khi Activity bị hủy trước khi GL callback kết thúc
            onSessionUpdated = { _, frame ->
                if (!isModelPlaced && !isDestroyed) {
                    val hasTrackingPlane = frame.getUpdatedTrackables(Plane::class.java).any {
                        it.trackingState == TrackingState.TRACKING
                    }
                    runOnUiThread {
                        if (!isDestroyed) {
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

            // Bắt sự kiện Tap-to-Place qua setOnGestureListener named-parameter API
            // SceneView v2.2.1: setOnGestureListener nhận từng lambda riêng qua named params,
            // KHÔNG nhận object : OnGestureListener — kiểu đúng là (MotionEvent, Node?) -> Boolean
            val arSceneView = this
            setOnGestureListener(
                onSingleTapConfirmed = { e: MotionEvent, node: Node? ->
                    if (!isModelPlaced) {
                        // hitTestAR(x, y): method của ARSceneView — thực hiện ARCore hitTest
                        // và trả về HitResult? của plane / feature point gần nhất
                        val hitResult: HitResult? = arSceneView.hitTestAR(e.x, e.y)
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
    // 3. TAP-TO-PLACE — Đặt mô hình lần đầu
    // =========================================================================

    /**
     * Đặt mô hình tại tọa độ va chạm (HitResult) của thế giới thực.
     *
     * Flow (Sceneview v2.2.1):
     *   HitResult → Anchor → AnchorNode → loadModelInstance() → ModelNode → addChildNode
     */
    private fun handleTapToPlace(hitResult: HitResult) {
        // Tạo Anchor gắn vào mặt phẳng thực tế đã phát hiện
        val anchor = hitResult.createAnchor()

        // AnchorNode: quản lý vòng đời của Anchor trong Filament Engine
        val anchorNode = AnchorNode(
            engine = binding.sceneView.engine,
            anchor = anchor
        )

        // Ghi nhận node và trạng thái trước khi load bất đồng bộ
        currentAnchorNode = anchorNode
        isModelPlaced = true

        // Thêm AnchorNode vào scene (v2.2.1 dùng addChildNode thay vì addChild)
        binding.sceneView.addChildNode(anchorNode)

        // Ẩn lưới quét mặt phẳng
        binding.sceneView.planeRenderer.isVisible = false
        binding.tvInstruction.text =
            getString(R.string.status_model_placed, currentSelectedItem.displayName)
        binding.loadingIndicator.visibility = View.VISIBLE

        // Nạp và gắn ModelNode bất đồng bộ
        loadModelAndAttach(anchorNode, currentSelectedItem)
    }

    // =========================================================================
    // 4. MODEL SWAPPING — Đổi mô hình tại đúng vị trí đã neo
    // =========================================================================

    /**
     * Thay thế mô hình cũ bằng mô hình mới tại đúng AnchorNode đang có.
     *
     * Quy trình:
     *   1. Hủy Job nạp cũ (nếu đang chạy) để tránh race-condition.
     *   2. Gỡ và destroy ModelNode cũ — tránh memory leak & model overlap.
     *   3. Nạp ModelNode mới gắn vào cùng AnchorNode.
     */
    private fun replaceModelOnCurrentAnchor(
        anchorNode: AnchorNode,
        newModelItem: ARModelItem
    ) {
        // 1. Hủy job nạp đang chạy (nếu có)
        modelLoadingJob?.cancel()

        // 2. Gỡ và giải phóng triệt để ModelNode cũ
        val oldModelNode = currentModelNode
        if (oldModelNode != null) {
            anchorNode.removeChildNode(oldModelNode)
            oldModelNode.destroy()
            currentModelNode = null
        }

        binding.tvInstruction.text = "Đang đổi sang ${newModelItem.displayName}..."
        binding.loadingIndicator.visibility = View.VISIBLE

        // 3. Nạp ModelNode mới gắn vào cùng AnchorNode
        loadModelAndAttach(anchorNode, newModelItem)
    }

    // =========================================================================
    // 5. NẠP MODEL BẤT ĐỒNG BỘ (Sceneview v2.2.1 modelLoader API)
    // =========================================================================

    /**
     * Nạp file .glb từ assets bằng [sceneView.modelLoader.loadModelInstance] (suspend fun),
     * tạo [ModelNode] và gắn vào [anchorNode].
     *
     * Sceneview v2.2.1 API:
     *   - [modelLoader.loadModelInstance]   → trả về [ModelInstance?] (null nếu lỗi)
     *   - [ModelNode]                       → nhận (modelInstance, scaleToUnits)
     *   - [ModelNode.isEditable] = true     → bật Pinch-to-scale & Drag-to-rotate
     *   - [ModelNode.playAnimation]         → (animationIndex, loop) phát skeleton animation
     *   - [anchorNode.addChildNode]         → API v2.x (KHÔNG dùng addChild)
     */
    private fun loadModelAndAttach(anchorNode: AnchorNode, modelItem: ARModelItem) {
        modelLoadingJob?.cancel()

        modelLoadingJob = lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE

            try {
                // Kiểm tra sự tồn tại của file trong assets trước khi nạp
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

                // Nạp ModelInstance bất đồng bộ — suspend, trả null nếu file lỗi
                val modelInstance = binding.sceneView.modelLoader.loadModelInstance(
                    fileLocation = modelItem.assetPath
                )

                if (modelInstance == null) {
                    binding.loadingIndicator.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Lỗi nạp ${modelItem.displayName}: ModelInstance trả về null",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                // Tạo ModelNode với scale chuẩn hóa 0.5 m theo trục dài nhất
                val modelNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = 0.5f   // Con vật hiển thị vừa vặn trong phòng
                ).apply {
                    // Bật thao tác cử chỉ: Pinch-to-scale & Drag-to-rotate
                    isEditable = true
                }

                // Kích hoạt Skeleton Animation lặp tuần hoàn vô tận (index 0)
                if (modelInstance.animator.animationCount > 0) {
                    modelNode.playAnimation(animationIndex = 0, loop = true)
                }

                // Gắn ModelNode vào AnchorNode (v2.2.1: addChildNode, không phải addChild)
                anchorNode.addChildNode(modelNode)
                currentModelNode = modelNode

                // Cập nhật HUD
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
    // 6. RESET SCENE
    // =========================================================================

    /**
     * Xóa mô hình hiện tại và giải phóng bộ nhớ để người dùng chọn vị trí đặt mới.
     *
     * Thứ tự hủy bắt buộc:
     *   ModelNode.destroy() → anchorNode.removeChildNode → AnchorNode.destroy()
     *   → sceneView.removeChildNode(anchorNode)
     */
    private fun resetARScene() {
        modelLoadingJob?.cancel()

        val anchorNode = currentAnchorNode
        val modelNode = currentModelNode

        // Gỡ và hủy ModelNode trước
        if (modelNode != null && anchorNode != null) {
            anchorNode.removeChildNode(modelNode)
            modelNode.destroy()
        }

        // Gỡ và hủy AnchorNode khỏi scene
        if (anchorNode != null) {
            binding.sceneView.removeChildNode(anchorNode)
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
    // 7. VÒNG ĐỜI VÀ BỘ NHỚ (LIFECYCLE & MEMORY CLEANUP)
    // =========================================================================

    /**
     * Hủy coroutine đang tải model khi app xuống background.
     * ARSceneView v2.2.1 tự quản lý lifecycle Camera/Session qua LifecycleObserver
     * (gắn vào ComponentActivity) — KHÔNG cần gọi sceneView.pause() thủ công.
     */
    override fun onPause() {
        super.onPause()
        modelLoadingJob?.cancel()
        // ARSceneView tự pause ARCore session qua internal LifeCycleObserver
    }

    /**
     * Xử lý luồng cấp quyền muộn: người dùng vừa vào App Settings cấp Camera rồi quay lại.
     * ARSceneView v2.2.1 tự resume qua LifecycleObserver — KHÔNG cần gọi sceneView.resume().
     */
    override fun onResume() {
        super.onResume()
        // Luồng cấp quyền muộn: ARScene chưa setup (user vừa cấp quyền từ Settings)
        if (!isARSceneSetup &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupARScene()
        }
        // ARSceneView tự resume ARCore session qua internal LifeCycleObserver
    }

    /**
     * Dọn dẹp triệt để toàn bộ tài nguyên đồ họa 3D, nodes và engine khi Activity bị hủy,
     * ngăn ngừa hiện tượng rò rỉ bộ nhớ (Memory Leak / Out-Of-Memory).
     */
    override fun onDestroy() {
        super.onDestroy()
        modelLoadingJob?.cancel()

        // Hủy bỏ các node theo thứ tự: ModelNode → AnchorNode → Engine
        try { currentModelNode?.destroy() } catch (_: Exception) {}
        currentModelNode = null

        try { currentAnchorNode?.destroy() } catch (_: Exception) {}
        currentAnchorNode = null

        // Hủy Filament Engine và ARCore Session
        // Bọc try-catch vì ARSceneView v2.2.1 có thể tự cleanup qua LifecycleObserver trước
        try { binding.sceneView.destroy() } catch (_: Exception) {}
    }
}
