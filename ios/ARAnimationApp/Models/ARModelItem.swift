import Foundation

/// Đại diện cho một mô hình 3D AR trên hệ điều hành iOS (RealityKit / QuickLook).
///
/// Tuân thủ `Identifiable` để dễ dàng duyệt trong SwiftUI `ForEach` hoặc `List`,
/// và `Equatable` để kiểm tra trạng thái item đang được chọn.
struct ARModelItem: Identifiable, Equatable {
    /// Định danh duy nhất của mô hình
    let id: String
    
    /// Tên hiển thị thân thiện trên giao diện người dùng
    let displayName: String
    
    /// Tên tệp .usdz được đóng gói trong Main Bundle của ứng dụng
    let fileName: String
    
    /// Khởi tạo một phần tử mô hình AR
    init(id: String, displayName: String, fileName: String) {
        self.id = id
        self.displayName = displayName
        self.fileName = fileName
    }
}

extension ARModelItem {
    /// Danh sách mẫu 6 mô hình động vật 3D định dạng .usdz
    static let sampleAnimals: [ARModelItem] = [
        ARModelItem(id: "stag", displayName: "Stag", fileName: "stag.usdz"),
        ARModelItem(id: "wolf", displayName: "Wolf", fileName: "wolf.usdz"),
        ARModelItem(id: "bull", displayName: "Bull", fileName: "bull.usdz"),
        ARModelItem(id: "cow", displayName: "Cow", fileName: "cow.usdz"),
        ARModelItem(id: "deer", displayName: "Deer", fileName: "deer.usdz"),
        ARModelItem(id: "shiba", displayName: "Shiba", fileName: "shiba.usdz")
    ]
    
    /// Mô hình con vật được chọn mặc định ban đầu ("stag.usdz")
    static var defaultAnimal: ARModelItem {
        sampleAnimals.first ?? ARModelItem(id: "stag", displayName: "Stag", fileName: "stag.usdz")
    }
}
