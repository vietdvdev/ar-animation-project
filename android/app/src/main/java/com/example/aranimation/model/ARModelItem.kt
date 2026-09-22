package com.example.aranimation.model

/**
 * Đại diện cho một phần tử mô hình 3D trong danh sách lựa chọn AR.
 *
 * @property id Định danh duy nhất cho mô hình
 * @property displayName Tên hiển thị thân thiện với người dùng
 * @property assetPath Đường dẫn tệp .glb bên trong thư mục `src/main/assets/`
 * @property isSelected Trạng thái đang được chọn trong danh sách hay không
 */
data class ARModelItem(
    val id: String,
    val displayName: String,
    val assetPath: String,
    var isSelected: Boolean = false
) {
    companion object {
        /**
         * Danh sách 6 mô hình động vật 3D mặc định từ assets.
         * Mặc định chọn con vật đầu tiên ("stag.glb").
         */
        fun getDefaultList(): List<ARModelItem> = listOf(
            ARModelItem(
                id = "stag",
                displayName = "Stag",
                assetPath = "stag.glb",
                isSelected = true
            ),
            ARModelItem(
                id = "wolf",
                displayName = "Wolf",
                assetPath = "wolf.glb",
                isSelected = false
            ),
            ARModelItem(
                id = "bull",
                displayName = "Bull",
                assetPath = "bull.glb",
                isSelected = false
            ),
            ARModelItem(
                id = "cow",
                displayName = "Cow",
                assetPath = "cow.glb",
                isSelected = false
            ),
            ARModelItem(
                id = "deer",
                displayName = "Deer",
                assetPath = "deer.glb",
                isSelected = false
            ),
            ARModelItem(
                id = "shiba",
                displayName = "Shiba",
                assetPath = "shiba.glb",
                isSelected = false
            )
        )
    }
}
