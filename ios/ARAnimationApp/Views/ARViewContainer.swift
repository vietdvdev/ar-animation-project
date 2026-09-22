import SwiftUI
import RealityKit
import ARKit
import Combine

/// Lớp ARView tùy biến mở rộng từ RealityKit ARView
/// Quản lý cấu hình ARWorldTrackingSession và bộ điều khiển cử chỉ.
class CustomARView: ARView {
    
    required init(frame frameRect: CGRect) {
        super.init(frame: frameRect)
    }
    
    dynamic required init?(coder decoder: NSCoder) {
        fatalError("init(coder:) chưa được triển khai")
    }
    
    /// 1. Cấu hình phiên làm việc ARKit an toàn, kiểm tra hỗ trợ phần cứng trước khi chạy
    func setupARSession() {
        guard ARWorldTrackingConfiguration.isSupported else {
            print("Cảnh báo: Thiết bị không hỗ trợ ARWorldTrackingConfiguration (cần chip A12 Bionic trở lên).")
            return
        }
        
        let configuration = ARWorldTrackingConfiguration()
        // Bật tính năng dò tìm mặt phẳng nằm ngang
        configuration.planeDetection = [.horizontal]
        // Bật tính năng tự động phản chiếu ánh sáng và khử nhiễu môi trường
        configuration.environmentTexturing = .automatic
        
        self.session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
    }
    
    /// Tạm dừng phiên AR để giải phóng cảm biến và tiết kiệm pin
    func pauseSession() {
        self.session.pause()
    }
    
    /// Tiếp tục phiên AR khi view xuất hiện lại
    func resumeSession() {
        guard ARWorldTrackingConfiguration.isSupported else { return }
        let configuration = ARWorldTrackingConfiguration()
        configuration.planeDetection = [.horizontal]
        configuration.environmentTexturing = .automatic
        self.session.run(configuration)
    }
}

/// Cầu nối hiển thị RealityKit ARView trên nền tảng giao diện SwiftUI.
/// Tối ưu hóa render loop, ngăn ngừa re-render thừa, quản lý Gesture & Collision
/// và hoán đổi mô hình bảo toàn Transform và Looping Animation an toàn tuyệt đối.
struct ARViewContainer: UIViewRepresentable {
    /// 1. ĐỒNG BỘ TRẠNG THÁI: Liên kết hai chiều với SwiftUI View cha
    @Binding var selectedModel: ARModelItem
    @Binding var isModelPlaced: Bool
    @Binding var instructionText: String
    @Binding var resetTrigger: Bool
    /// Trạng thái hoạt động của AR Session — liên kết với scenePhase từ ContentView
    @Binding var isARSessionActive: Bool
    
    func makeUIView(context: Context) -> CustomARView {
        let arView = CustomARView(frame: .zero)
        
        // Cấu hình ARSession an toàn
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
        // Cho phép các gesture cử chỉ tương tác của RealityKit và SwiftUI hoạt động đồng thời
        tapGesture.cancelsTouchesInView = false
        arView.addGestureRecognizer(tapGesture)
        
        return arView
    }
    
    /// 4. TỐI ƯU GIAO DIỆN SWIFTUI: Tránh re-render toàn bộ ARView liên tục
    func updateUIView(_ uiView: CustomARView, context: Context) {
        context.coordinator.parent = self
        
        // Kiểm tra nếu có tín hiệu Reset Scene
        if resetTrigger {
            DispatchQueue.main.async {
                self.resetTrigger = false
            }
            context.coordinator.resetScene()
            return
        }
        
        // Xử lý Pause / Resume AR Session khi scenePhase thay đổi
        if context.coordinator.lastARActiveState != isARSessionActive {
            context.coordinator.lastARActiveState = isARSessionActive
            if isARSessionActive {
                uiView.resumeSession()
            } else {
                uiView.pauseSession()
            }
        }
        
        // Chỉ xử lý khi người dùng thực sự chọn sang một con vật khác
        if context.coordinator.lastHandledModelId != selectedModel.id {
            let previousId = context.coordinator.lastHandledModelId
            context.coordinator.lastHandledModelId = selectedModel.id
            context.coordinator.handleModelSelectionChanged(from: previousId, to: selectedModel)
        }
    }
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    static func dismantleUIView(_ uiView: CustomARView, coordinator: Coordinator) {
        coordinator.cleanup()
        uiView.pauseSession()
    }
    
    // MARK: - Coordinator
    
    /// Lớp Coordinator quản lý logic Raycast, nạp model, hoán đổi linh hoạt và cử chỉ tương tác
    class Coordinator: NSObject {
        var parent: ARViewContainer
        weak var arView: CustomARView?
        
        var lastHandledModelId: String = ""
        var currentAnchor: AnchorEntity?
        var currentModelEntity: (Entity & HasCollision)?
        
        /// Theo dõi trạng thái active/paused của AR Session để tránh gọi lại không cần thiết
        var lastARActiveState: Bool = true
        
        /// Lưu trữ subscription Combine khi nạp USDZ bất đồng bộ
        var cancellables = Set<AnyCancellable>()
        
        init(_ parent: ARViewContainer) {
            self.parent = parent
        }
        
        /// Bắt sự kiện Tap trên màn hình để đặt mô hình lần đầu tiên
        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard let arView = arView else { return }
            let tapLocation = recognizer.location(in: arView)
            
            // Nếu người dùng chạm vào thanh chọn dưới đáy (vùng ngoài ARView), bỏ qua
            if tapLocation.y > arView.bounds.height - 120 {
                return
            }
            
            // 3. Tránh xung đột cử chỉ: Nếu chạm trúng mô hình đang hiển thị thì nhường cho RealityKit Gestures
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
        
        /// 2. XỬ LÝ LOGIC THAY ĐỔI MÔ HÌNH: Bảo toàn Scale & Orientation, giải phóng bộ nhớ cũ
        func handleModelSelectionChanged(from previousId: String, to newModel: ARModelItem) {
            guard let anchor = currentAnchor, let oldEntity = currentModelEntity else {
                // Chưa đặt mô hình trên sân: Ghi nhận con vật mới
                DispatchQueue.main.async {
                    self.parent.instructionText = "Đã chọn \(newModel.displayName). Chạm vào sàn để đặt!"
                }
                return
            }
            
            // 1. Lưu lại hướng xoay (Orientation/Rotation) và tỉ lệ phóng to/thu nhỏ (Scale) hiện tại
            let savedTransform = oldEntity.transform
            
            // 2. Gỡ bỏ entity cũ và giải phóng hoàn toàn bộ nhớ đồ họa
            anchor.removeChild(oldEntity)
            self.currentModelEntity = nil
            
            // 3. Tải bất đồng bộ file USDZ mới, áp dụng savedTransform và phát Looping Animation
            loadModelIntoAnchor(
                anchor: anchor,
                modelItem: newModel,
                initialTransform: savedTransform
            )
        }
        
        /// Tải mô hình USDZ bất đồng bộ, gán va chạm, cử chỉ và chạy animation an toàn
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
            
            // Nạp file .usdz từ Main Bundle an toàn
            Entity.loadAsync(named: modelItem.fileName)
                .receive(on: DispatchQueue.main)
                .sink(receiveCompletion: { [weak self] completion in
                    if case .failure(let error) = completion {
                        print("Lỗi nạp mô hình (\(modelItem.fileName)): \(error.localizedDescription)")
                        DispatchQueue.main.async {
                            self?.parent.instructionText = "Không tìm thấy hoặc lỗi file \(modelItem.fileName)"
                        }
                    }
                }, receiveValue: { [weak self, weak arView] entity in
                    guard let self = self, let arView = arView else { return }
                    
                    // 2. Áp dụng lại Transform cũ hoặc thiết lập mặc định (scale 0.5)
                    if let preservedTransform = initialTransform {
                        entity.transform = preservedTransform
                    } else {
                        entity.setScale(SIMD3<Float>(repeating: 0.5), relativeTo: nil)
                    }
                    
                    // 3. CẤU HÌNH VA CHẠM ĐỆ QUY (COLLISION)
                    entity.generateCollisionShapes(recursive: true)
                    
                    // BẬT CỬ CHỈ THAO TÁC VẬT LÝ TRỰC TIẾP CỦA REALITYKIT (.scale, .rotation)
                    if let hasCollisionEntity = entity as? (Entity & HasCollision) {
                        arView.installGestures([.scale, .rotation], for: hasCollisionEntity)
                        self.currentModelEntity = hasCollisionEntity
                    }
                    
                    // 3. KIỂM TRA ANIMATION AN TOÀN: Tránh crash nếu model không có animation
                    let availableAnimations = entity.availableAnimations
                    if !availableAnimations.isEmpty, let firstAnimation = availableAnimations.first {
                        entity.playAnimation(firstAnimation.repeat())
                    } else {
                        print("Thông tin: Mô hình \(modelItem.displayName) không chứa track animation.")
                    }
                    
                    // Dọn dẹp các entity con trong Anchor một cách an toàn
                    // (gọi removeFromParent() để đảm bảo engine giải phóng bộ nhớ GPU đúng cách)
                    for child in anchor.children {
                        child.removeFromParent()
                    }
                    anchor.addChild(entity)
                    
                    // Cập nhật giao diện người dùng
                    self.parent.isModelPlaced = true
                    self.parent.instructionText = "Đang hiển thị \(modelItem.displayName). Dùng 2 ngón tay để Phóng to/Xoay."
                })
                .store(in: &cancellables)
        }
        
        /// Xóa sạch Anchor và mô hình hiện tại (Reset Scene)
        func resetScene() {
            cleanup()
            parent.isModelPlaced = false
            parent.instructionText = "Lia máy để tìm mặt sàn..."
        }
        
        /// Dọn dẹp tài nguyên triệt để
        func cleanup() {
            cancellables.removeAll()
            if let oldAnchor = currentAnchor {
                arView?.scene.removeAnchor(oldAnchor)
            }
            currentAnchor = nil
            currentModelEntity = nil
        }
    }
}
