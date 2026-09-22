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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.aranimation.adapter.ModelPickerAdapter
import com.example.aranimation.databinding.ActivityMainBinding
import com.example.aranimation.model.ARModelItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * Màn hình AR chính cấp độ Production (Kiểm thử & QA hoàn thiện):
 * 1. Quản lý quyền Camera Runtime chặt chẽ:
 *    - Hỗ trợ cả trường hợp từ chối thông thường (Denied) và từ chối vĩnh viễn (Don't ask again).
 *    - Dialog giải thích minh bạch điều hướng người dùng mở App Settings mà không làm crash app.
 * 2. Nạp tài nguyên 3D & Xử lý ngoại lệ (Assets Loading & Exception Handling):
 *    - Kiểm tra tính hợp lệ của tệp assets trước khi nạp.
 *    - Bọc khối xử lý try-catch và callback onError rõ ràng để tránh crash nếu file 3D hỏng hoặc thiếu.
 * 3. AR Lifecycle & Dọn dẹp bộ nhớ (Zero Memory Leak & No Model Overlap):
 *    - Khi đổi mô hình trên sân: Hủy triệt để và gỡ node cũ khỏi Scene/Anchor trước khi nạp model mới.
 *    - onPause(), onResume(), onDestroy() giải phóng engine, luồng camera và coroutine.
 * 4. Tương tác cử chỉ & Animation:
 *    - Cấu hình minScale = 0.2f, maxScale = 2.5f tránh co giật hoặc biến mất.
 *    - Animation Controller kích hoạt loop vô tận và bảo toàn liên tục trong lúc Pinch/Rotate.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Danh sách 6 mô hình động vật mặc định từ assets
    private val modelList: List<ARModelItem> = ARModelItem.getDefaultList()
    private lateinit var modelPickerAdapter: ModelPickerAdapter

    // Mô hình 3D đang được chọn (khởi tạo mặc định: "stag.glb")
    private var currentSelectedItem: ARModelItem = modelList.first { it.isSelected }

    // Quản lý các Node trong không gian AR
    private var currentAnchorNode: AnchorNode? = null
    private var currentModelNode: ArModelNode? = null

    // Quản lý Coroutine Job nạp 3D Model để tránh xung đột
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
     * Thay thế mô hình cũ bằng mô hình mới tại đúng vị trí và góc xoay,
     * gỡ bỏ và hủy triệt để node cũ tránh tình trạng mô hình mới bị đè chồng lên mô hình cũ.
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

        // 2. GIẢI PHÓNG BỘ NHỚ VÀ HỦY TRIỆT ĐỂ NODE CŨ (Ngăn xếp chồng mô hình & giật lag FPS)
        anchorNode.removeChild(oldModelNode)
        oldModelNode.destroy()

        // 3. Khởi tạo node mới kế thừa lại vị trí và góc xoay an toàn
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
     * 1. KIỂM TRA QUYỀN VÀ CẤU HÌNH MÔI TRƯỜNG
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

    /**
     * Xử lý khi bị từ chối quyền Camera: Phân biệt từ chối thường và từ chối vĩnh viễn (Don't ask again)
     */
    private fun handlePermissionDenied() {
        val showRationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)

        if (showRationale) {
            // Người dùng vừa từ chối lần đầu: Giải thích và hỏi lại
            MaterialAlertDialogBuilder(this)
                .setTitle("Yêu cầu quyền máy ảnh")
                .setMessage("Ứng dụng cần quyền Camera để quét không gian và hiển thị vật thể thực tế ảo (AR).")
                .setPositiveButton("Cấp quyền") { _, _ ->
                    cameraPermissionRequest.launch(Manifest.permission.CAMERA)
                }
                .setNegativeButton("Thoát ứng dụng") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        } else {
            // Người dùng chọn "Don't ask again" hoặc bị từ chối vĩnh viễn: Hiển thị Dialog hướng dẫn mở Settings
            MaterialAlertDialogBuilder(this)
                .setTitle("Quyền Camera bị vô hiệu hóa")
                .setMessage("Bạn đã từ chối quyền truy cập máy ảnh. Vui lòng vào Cài đặt ứng dụng để bật quyền Camera thủ công.")
                .setPositiveButton("Mở Cài đặt") { _, _ ->
                    openAppSettings()
                }
                .setNegativeButton("Đóng") { _, _ ->
                    finish()
                }
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

        // 4. KIỂM TRA TƯƠNG TÁC CỬ CHỈ VÀ GIỚI HẠN SCALE
        val modelNode = ArModelNode(
            engine = engine,
            placementMode = PlacementMode.PLANE_HORIZONTAL
        ).apply {
            isPositionEditable = false
            isRotationEditable = true
            isScaleEditable = true
            minScale = 0.2f // Giới hạn scale tối thiểu 0.2x để con vật không bị co lại thành điểm vô hình
            maxScale = 2.5f // Giới hạn scale tối đa 2.5x để tránh tràn tầm nhìn hoặc clipping camera
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
     * 2. NẠP TÀI NGUYÊN 3D & XỬ LÝ NGOẠI LỆ (ASSETS LOADING & EXCEPTION HANDLING)
     */
    private fun loadAndAnimateModel(node: ArModelNode, assetPath: String) {
        modelLoadingJob?.cancel()

        modelLoadingJob = lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE

            try {
                // Kiểm tra sự tồn tại của file trong assets trước khi nạp
                val assetExists = assets.list("")?.contains(assetPath) == true
                if (!assetExists) {
                    binding.loadingIndicator.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Không tìm thấy file: $assetPath trong assets",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                node.loadModelGlbAsync(
                    glbFileLocation = assetPath,
                    autoAnimate = true,
                    scaleToUnits = 0.5f,
                    centerOrigin = null,
                    onError = { exception ->
                        binding.loadingIndicator.visibility = View.GONE
                        Toast.makeText(
                            this@MainActivity,
                            "Lỗi nạp ${currentSelectedItem.displayName}: ${exception.localizedMessage ?: "File hỏng"}",
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

    /**
     * 3. Xóa mô hình hiện tại (Reset Scene) và giải phóng bộ nhớ để người dùng chọn vị trí đặt mới
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
    // 3. QUẢN LÝ VÒNG ĐỜI VÀ BỘ NHỚ (LIFECYCLE & MEMORY CLEANUP)
    // =========================================================================

    /**
     * Tạm dừng session AR, dừng animation và giải phóng luồng camera khi app xuống background
     * nhằm tiết kiệm pin tối đa cho thiết bị di động.
     */
    override fun onPause() {
        super.onPause()
        modelLoadingJob?.cancel()
        binding.sceneView.pause()
    }

    /**
     * Khôi phục phiên AR khi người dùng quay lại ứng dụng
     */
    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.sceneView.resume()
        }
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
