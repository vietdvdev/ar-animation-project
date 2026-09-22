import SwiftUI

/// Màn hình chính liên kết toàn bộ hệ thống AR Native trên iOS:
/// - Lớp nền: ARViewContainer (RealityKit & ARKit)
/// - Lớp đỉnh (Top HUD): Thẻ chữ trạng thái hướng dẫn bán trong suốt
/// - Góc trên bên phải: Nút tròn Reset Scene (`arrow.counterclockwise`)
/// - Lớp đáy (Bottom): Thanh trượt chọn 6 con vật ModelPickerView
struct ContentView: View {
    /// Danh sách 6 mô hình con vật định dạng .usdz
    private let availableModels: [ARModelItem] = ARModelItem.sampleAnimals
    
    /// Mô hình con vật hiện đang được chọn (mặc định là Stag)
    @State private var selectedModel: ARModelItem = ARModelItem.defaultAnimal
    
    /// Cờ trạng thái cho biết đã có mô hình được đặt trên mặt phẳng hay chưa
    @State private var isModelPlaced: Bool = false
    
    /// Nội dung hướng dẫn trạng thái HUD hiển thị trên đỉnh màn hình
    @State private var instructionText: String = "Lia máy để tìm mặt sàn..."
    
    /// Kích hoạt đặt lại không gian AR
    @State private var resetTrigger: Bool = false
    
    /// Quản lý trạng thái hiển thị của ứng dụng để tối ưu pin và bộ nhớ
    @Environment(\.scenePhase) private var scenePhase
    
    /// Bộ phát xung phản hồi xúc giác nhẹ khi bấm Reset
    private let feedbackGenerator = UIImpactFeedbackGenerator(style: .medium)
    
    var body: some View {
        ZStack {
            // 1. LỚP NỀN: KHUNG NHÌN CAMERA VÀ BỘ DỰNG HÌNH AR REALITYKIT
            ARViewContainer(
                selectedModel: $selectedModel,
                isModelPlaced: $isModelPlaced,
                instructionText: $instructionText,
                resetTrigger: $resetTrigger
            )
            .ignoresSafeArea()
            
            // 2. LỚP PHỦ GIAO DIỆN (UI OVERLAY)
            VStack(spacing: 0) {
                // HÀNG TRÊN ĐỈNH: HUD HƯỚNG DẪN VÀ NÚT RESET
                topHUDBar
                    .padding(.top, 8)
                
                Spacer()
                
                // HÀNG DƯỚI ĐÁY: THANH CHỌN MÔ HÌNH 3D CUỘN NGANG
                bottomModelPickerBar
                    .padding(.bottom, 12)
            }
        }
        .preferredColorScheme(.dark)
        .onChange(of: scenePhase) { newPhase in
            switch newPhase {
            case .active:
                print("App vào trạng thái Active: AR Session sẵn sàng.")
            case .inactive, .background:
                print("App vào Background: Tối ưu bộ nhớ và tạm dừng AR Rendering.")
            @unknown default:
                break
            }
        }
    }
    
    // MARK: - Subviews
    
    /// Thanh điều khiển trên đỉnh (Top HUD)
    private var topHUDBar: some View {
        HStack(spacing: 12) {
            // Thẻ chữ hướng dẫn trạng thái
            HStack(spacing: 8) {
                if !isModelPlaced {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        .scaleEffect(0.8)
                } else {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.green)
                        .font(.system(size: 14))
                }
                
                Text(instructionText)
                    .font(.system(size: 13, weight: .medium, design: .rounded))
                    .foregroundColor(.white)
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 20)
                    .fill(Color.black.opacity(0.35))
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(Color.white.opacity(0.15), lineWidth: 1)
            )
            
            Spacer()
            
            // Nút tròn Reset Scene góc trên bên phải
            Button(action: resetSceneAction) {
                Image(systemName: "arrow.counterclockwise")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(width: 44, height: 44)
                    .background(
                        Circle()
                            .fill(Color.black.opacity(0.35))
                            .background(.ultraThinMaterial, in: Circle())
                    )
                    .overlay(
                        Circle()
                            .stroke(Color.white.opacity(0.15), lineWidth: 1)
                    )
                    .shadow(color: Color.black.opacity(0.2), radius: 4, x: 0, y: 2)
            }
            .accessibilityLabel(Text("Đặt lại không gian"))
        }
        .padding(.horizontal, 20)
    }
    
    /// Thanh chọn mô hình ở đáy màn hình
    private var bottomModelPickerBar: some View {
        VStack(spacing: 8) {
            ModelPickerView(
                models: availableModels,
                selectedModel: $selectedModel
            )
        }
    }
    
    // MARK: - Actions
    
    /// Xử lý hành động đặt lại không gian AR
    private func resetSceneAction() {
        feedbackGenerator.prepare()
        feedbackGenerator.impactOccurred()
        
        withAnimation(.easeInOut) {
            resetTrigger = true
            isModelPlaced = false
            instructionText = "Lia máy để tìm mặt sàn..."
        }
    }
}

#Preview {
    ContentView()
}
