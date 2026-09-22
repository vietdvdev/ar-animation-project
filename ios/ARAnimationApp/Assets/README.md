# Assets — iOS USDZ Models

Thư mục này chứa 6 file mô hình 3D định dạng `.usdz` dành riêng cho nền tảng iOS (RealityKit).

## File cần có

| Tên file | Con vật |
|---|---|
| `stag.usdz` | Hươu đực |
| `wolf.usdz` | Sói |
| `bull.usdz` | Bò đực |
| `cow.usdz` | Bò cái |
| `deer.usdz` | Hươu |
| `shiba.usdz` | Chó Shiba |

## Cách chuyển đổi từ .glb sang .usdz (macOS)

### Cách 1 — Reality Converter (Khuyến nghị, giao diện đồ họa)
1. Tải **Reality Converter** miễn phí tại [developer.apple.com/augmented-reality/tools](https://developer.apple.com/augmented-reality/tools/).
2. Kéo thả từng file `.glb` từ thư mục `android/app/src/main/assets/` vào ứng dụng.
3. Xuất ra `.usdz` và đặt vào thư mục này.

### Cách 2 — Command Line (macOS Ventura+)
```bash
# Ví dụ chuyển stag.glb → stag.usdz
xcrun usdz_converter path/to/stag.glb stag.usdz
```

### Cách 3 — Blender + Blender USDZ Exporter
1. Mở file `.glb` trong Blender.
2. File → Export → Universal Scene Description (.usdz).

## Sau khi có file .usdz

Các file đã được khai báo trong `project.pbxproj` (Resources build phase).
Chỉ cần đặt đúng vào thư mục `ios/ARAnimationApp/Assets/` là Xcode sẽ tự đóng gói vào `.app` bundle khi build.
