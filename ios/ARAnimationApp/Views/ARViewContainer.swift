import SwiftUI
import RealityKit
import ARKit
import Combine

/// Lớp ARView tùy biến mở rộng từ RealityKit ARView
/// Quản lý cấu hình ARWorldTrackingSession và các tương tác cử chỉ.
class CustomARView: ARView {
    
    required init(frame frameRect: CGRect) {
        super.init(frame: frameRect)
    }
    
    dynamic required init?(coder decoder: NSCoder) {
        fatalError("init(coder:) chưa được triển khai")
    }
    
    /// Cấu hình phiên làm việc ARKit với phát hiện mặt phẳng nằm ngang
    func setupARSession() {
        guard ARWorldTrackingConfiguration.isSupported else {
            print("Cảnh báo: Thiết bị không hỗ trợ ARWorldTrackingConfiguration.")
            return
        }
        
        let configuration = ARWorldTrackingConfiguration()
        // Bật tính năng dò tìm mặt phẳng nằm ngang
        configuration.planeDetection = [.horizontal]
        // Bật tính năng tự động phản chiếu ánh sáng và khử nhiễu môi trường
        configuration.environmentTexturing = .automatic
        
        self.session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
    }
    
    /// Tạm dừng phiên AR để tiết kiệm pin
    func pauseSession() {
        self.session.pause()
    }
}

/// Cầu nối hiển thị RealityKit ARView trên nền tảng giao diện SwiftUI.
/// Đồng bộ trạng thái @Binding selectedModel, tự động nhận diện thay đổi mô hình trong updateUIView,
/// hoán đổi con vật mới tại chỗ, bảo toàn Scale & Orientation và kích hoạt Looping Animation.
struct ARViewContainer: UIViewRepresentable {
    /// 1. ĐỒNG BỘ TRẠNG THÁI: Biến Binding hai chiều liên kết với SwiftUI View cha
    @Binding var selectedModel: ARModelItem
    
    /// Trạng thái đã đặt mô hình hay chưa
    @Binding var isModelPlaced: Bool
    
    /// Thông báo trạng thái tới người dùng (HUD Feedback)
    @Binding var instructionText: String
    
    /// Cờ yêu cầu đặt lại toàn bộ không gian (Reset Trigger)
    @Binding var resetTrigger: Bool
    
    func makeUIView(context: Context) -> CustomARView {
        let arView = CustomARView(frame: .zero)
        
        // Cấu hình ARSession
        arView.setupARSession()
        
        // Gán tham chiếu cho Coordinator
        context.coordinator.arView = arView
        context.coordinator.parent = self
        context.coordinator.lastHandledModelId = selectedModel.id
        
        // Cài đặt bộ nhận diện cử chỉ chạm (UITapGestureRecognizer) để đặt mô hình
        let tapGesture = UITapGestureRecognizer(
            target: context.coordinator,
            action: #selector(Coordinator.handleTap(_:))
        )
        arView.addGestureRecognizer(tapGesture)
        
        return arView
    }
    
    /// 1. PHÁT HIỆN THAY ĐỔI TRONG UPDATEUIVIEW
    func updateUIView(_ uiView: CustomARView, context: Context) {
        context.coordinator.parent = self
        
        // Kiểm tra nếu người dùng bấm nút Reset Scene
        if resetTrigger {
            DispatchQueue.main.async {
                self.resetTrigger = false
            }
            context.coordinator.resetScene()
            return
        }
        
        // Phát hiện khi selectedModel bị thay đổi giá trị từ thanh chọn SwiftUI
        if context.coordinator.lastHandledModelId != selectedModel.id {
            let previousId = context.coordinator.lastHandledModelId
            context.coordinator.lastHandledModelId = selectedModel.id
            
            // Xử lý bài toán thay thế mô hình linh hoạt
            context.coordinator.handleModelSelectionChanged(from: previousId, to: selectedModel)
        }
    }
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    static func dismantleUIView(_ uiView: CustomARView, coordinator: Coordinator) {
        coordinator.cancellables.removeAll()
        uiView.pauseSession()
    }
    
    // MARK: - Coordinator
    
    /// Lớp Coordinator quản lý logic Raycast, nạp model, hoán đổi linh hoạt và cử chỉ tương tác
    class Coordinator: NSObject {
        var parent: ARViewContainer
        weak var arView: CustomARView?
        
        /// Lưu trữ model ID xử lý gần nhất để so sánh trong updateUIView
        var lastHandledModelId: String = ""
        
        /// 2. LƯU TRỮ THAM CHIẾU ANCHOR VÀ MODEL ENTITY
        var currentAnchor: AnchorEntity?
        var currentModelEntity: (Entity & HasCollision)?
        
        /// Lưu trữ subscription Combine khi nạp USDZ bất đồng bộ
        var cancellables = Set<AnyCancellable>()
        
        init(_ parent: ARViewContainer) {
            self.parent = parent
        }
        
        /// Bắt sự kiện Tap trên màn hình để đặt mô hình lần đầu tiên
        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard let arView = arView else { return }
            let tapLocation = recognizer.location(in: arView)
            
            // Nếu chạm trúng mô hình đang có sẵn thì nhường quyền xử lý cho Gestures
            if let hitEntity = arView.entity(at: tapLocation), hitEntity is HasCollision {
                return
            }
            
            // Thực hiện Raycast tìm bề mặt sàn thực tế
            let raycastResults = arView.raycast(
                from: tapLocation,
                allowing: .estimatedPlane,
                alignment: .horizontal
            )
            
            if let firstResult = raycastResults.first {
                // Tạo một AnchorEntity cố định vào tọa độ thế giới thực
                let anchorEntity = AnchorEntity(world: firstResult.worldTransform)
                
                // Gỡ bỏ anchor cũ nếu có trước đó
                if let oldAnchor = currentAnchor {
                    arView.scene.removeAnchor(oldAnchor)
                }
                
                arView.scene.addAnchor(anchorEntity)
                self.currentAnchor = anchorEntity
                
                // Nạp mô hình USDZ của con vật đang chọn
                loadModelIntoAnchor(
                    anchor: anchorEntity,
                    modelItem: parent.selectedModel,
                    initialTransform: nil
                )
            } else {
                DispatchQueue.main.async {
                    self.parent.instructionText = "Chưa phát hiện mặt phẳng. Hãy di chuyển camera quét xung quanh!"
                }
            }
        }
        
        /// 2. XỬ LÝ BÀI TOÁN THAY ĐỔI MÔ HÌNH (MODEL SELECTION LOGIC)
        func handleModelSelectionChanged(from previousId: String, to newModel: ARModelItem) {
            guard let anchor = currentAnchor, let oldEntity = currentModelEntity else {
                // TRƯỜNG HỢP A: Chưa có mô hình nào trên sân
                // Chỉ ghi nhận con vật mới, lần chạm sàn kế tiếp sẽ nạp con vật này
                DispatchQueue.main.async {
                    self.parent.instructionText = "Đã chọn \(newModel.displayName). Chạm vào sàn để đặt!"
                }
                return
            }
            
            // TRƯỜNG HỢP B: Đã có mô hình đang hiển thị trên sân thực tế
            // 1. Lưu lại hướng xoay (Orientation/Rotation) và tỉ lệ phóng to/thu nhỏ (Scale) hiện tại
            let savedTransform = oldEntity.transform
            
            // 2. Gỡ bỏ currentModelEntity cũ khỏi currentAnchor và dọn dẹp bộ nhớ
            anchor.removeChild(oldEntity)
            self.currentModelEntity = nil
            
            // 3. Tải bất đồng bộ file USDZ mới của con vật vừa chọn
            // 4. Áp dụng lại scale/orientation cũ, gắn vào currentAnchor và kích hoạt Looping Animation
            loadModelIntoAnchor(
                anchor: anchor,
                modelItem: newModel,
                initialTransform: savedTransform
            )
        }
        
        /// Tải mô hình USDZ bất đồng bộ, gán transform, va chạm, cử chỉ và chạy animation lặp lại
        private func loadModelIntoAnchor(
            anchor: AnchorEntity,
            modelItem: ARModelItem,
            initialTransform: Transform?
        ) {
            guard let arView = arView else { return }
            
            DispatchQueue.main.async {
                self.parent.instructionText = "Đang đổi sang \(modelItem.displayName)..."
            }
            
            // Hủy subscription nạp cũ nếu có
            cancellables.removeAll()
            
            Entity.loadAsync(named: modelItem.fileName)
                .receive(on: DispatchQueue.main)
                .sink(receiveCompletion: { [weak self] completion in
                    if case .failure(let error) = completion {
                        print("Lỗi nạp mô hình (\(modelItem.fileName)): \(error.localizedDescription)")
                        self?.parent.instructionText = "Không thể tải mô hình: \(error.localizedDescription)"
                    }
                }, receiveValue: { [weak self, weak arView] entity in
                    guard let self = self, let arView = arView else { return }
                    
                    // Áp dụng lại Transform cũ hoặc thiết lập mặc định (scale 0.5)
                    if let preservedTransform = initialTransform {
                        entity.transform = preservedTransform
                    } else {
                        entity.setScale(SIMD3<Float>(repeating: 0.5), relativeTo: nil)
                    }
                    
                    // Cấu hình va chạm (Collision)
                    entity.generateCollisionShapes(recursive: true)
                    
                    // Bật cử chỉ thao tác vật lý trực tiếp của RealityKit
                    if let hasCollisionEntity = entity as? (Entity & HasCollision) {
                        arView.installGestures([.scale, .rotation], for: hasCollisionEntity)
                        self.currentModelEntity = hasCollisionEntity
                    }
                    
                    // Tự động chạy Skeletal Animation lặp vô tận (Looping Animation)
                    let availableAnimations = entity.availableAnimations
                    if let firstAnimation = availableAnimations.first {
                        entity.playAnimation(firstAnimation.repeat())
                    }
                    
                    // Gắn vào AnchorEntity
                    anchor.children.removeAll()
                    anchor.addChild(entity)
                    
                    // Cập nhật giao diện SwiftUI
                    self.parent.isModelPlaced = true
                    self.parent.instructionText = "Đang hiển thị \(modelItem.displayName). Dùng 2 ngón tay để Phóng to/Xoay."
                })
                .store(in: &cancellables)
        }
        
        /// Xóa sạch Anchor và mô hình hiện tại (Reset Scene)
        func resetScene() {
            cancellables.removeAll()
            if let oldAnchor = currentAnchor {
                arView?.scene.removeAnchor(oldAnchor)
            }
            currentAnchor = nil
            currentModelEntity = nil
            parent.isModelPlaced = false
            parent.instructionText = "Di chuyển máy để tìm sàn..."
        }
    }
}
