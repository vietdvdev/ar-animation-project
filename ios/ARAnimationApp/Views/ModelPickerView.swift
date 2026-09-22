import SwiftUI

/// Thanh công cụ cuộn ngang cho phép người dùng chọn mô hình AR động vật trong SwiftUI.
/// Tích hợp hiệu ứng Glassmorphism (`.ultraThinMaterial`), viền Indigo nổi bật khi active
/// và phản hồi xúc giác (Haptic Feedback) khi chạm chuyển đổi.
struct ModelPickerView: View {
    /// Danh sách các mô hình 3D AR
    let models: [ARModelItem]
    
    /// Binding liên kết với mô hình đang được chọn trong View cha
    @Binding var selectedModel: ARModelItem
    
    /// Closure callback kích hoạt khi người dùng thay đổi lựa chọn
    var onSelect: ((ARModelItem) -> Void)? = nil
    
    /// Bộ phát xung phản hồi xúc giác (UIImpactFeedbackGenerator)
    private let feedbackGenerator = UIImpactFeedbackGenerator(style: .medium)
    
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 12) {
                ForEach(models) { model in
                    let isSelected = (model.id == selectedModel.id)
                    
                    Button(action: {
                        selectModel(model)
                    }) {
                        Text(model.displayName)
                            .font(.system(size: 15, weight: isSelected ? .bold : .medium, design: .rounded))
                            .foregroundColor(isSelected ? .white : Color.white.opacity(0.8))
                            .padding(.horizontal, 18)
                            .padding(.vertical, 12)
                            .frame(minWidth: 72)
                            .background(
                                RoundedRectangle(cornerRadius: 10)
                                    .fill(isSelected ? Color.indigo.opacity(0.4) : Color.black.opacity(0.3))
                                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(
                                        isSelected ? Color.indigo : Color.white.opacity(0.15),
                                        lineWidth: isSelected ? 2 : 1
                                    )
                            )
                            .shadow(color: isSelected ? Color.indigo.opacity(0.4) : Color.clear, radius: 6, x: 0, y: 2)
                    }
                    .buttonStyle(ScaleButtonStyle())
                    .accessibilityLabel(Text(model.displayName))
                    .accessibilityAddTraits(isSelected ? [.isSelected] : [])
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
        }
    }
    
    /// Xử lý cập nhật model, rung xúc giác và gọi closure
    private func selectModel(_ model: ARModelItem) {
        guard model.id != selectedModel.id else { return }
        
        // Kích hoạt hiệu ứng xúc giác (Haptic Feedback)
        feedbackGenerator.prepare()
        feedbackGenerator.impactOccurred()
        
        withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
            selectedModel = model
        }
        
        onSelect?(model)
    }
}

/// Button style tùy biến tạo hiệu ứng nhún nhẹ khi chạm vào Card
private struct ScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: configuration.isPressed)
    }
}

#Preview {
    ZStack {
        Color.gray.ignoresSafeArea()
        
        VStack {
            Spacer()
            ModelPickerView(
                models: ARModelItem.sampleAnimals,
                selectedModel: .constant(ARModelItem.defaultAnimal)
            ) { selected in
                print("Selected: \(selected.displayName)")
            }
        }
    }
}
