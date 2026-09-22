package com.example.aranimation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.aranimation.R
import com.example.aranimation.databinding.ItemModelSelectorBinding
import com.example.aranimation.model.ARModelItem

/**
 * Adapter quản lý danh sách chọn mô hình 3D AR bằng ViewBinding.
 * Hỗ trợ chuyển đổi trạng thái chọn (Single-selection) kèm hiệu ứng đổi màu viền, nền và chữ.
 *
 * @param itemList Danh sách các mô hình 3D
 * @param onModelSelected Callback kích hoạt khi người dùng chọn một mô hình
 */
class ModelPickerAdapter(
    private val itemList: List<ARModelItem>,
    private val onModelSelected: (ARModelItem) -> Unit
) : RecyclerView.Adapter<ModelPickerAdapter.ModelViewHolder>() {

    // Theo dõi vị trí đang được chọn trong danh sách
    private var selectedPosition: Int = itemList.indexOfFirst { it.isSelected }.let {
        if (it != -1) it else 0
    }

    init {
        // Đảm bảo dữ liệu ban đầu đồng bộ với selectedPosition
        if (itemList.isNotEmpty()) {
            itemList.forEachIndexed { index, item ->
                item.isSelected = (index == selectedPosition)
            }
        }
    }

    inner class ModelViewHolder(
        private val binding: ItemModelSelectorBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ARModelItem, position: Int) {
            val context = binding.root.context
            binding.tvModelName.text = item.displayName

            if (item.isSelected) {
                // Trạng thái ĐÃ CHỌN (Selected)
                binding.cardContainer.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.item_card_bg_selected)
                )
                binding.cardContainer.strokeColor =
                    ContextCompat.getColor(context, R.color.item_stroke_selected)
                binding.cardContainer.strokeWidth = dpToPx(2f)

                binding.tvModelName.setTextColor(
                    ContextCompat.getColor(context, R.color.text_selected)
                )
            } else {
                // Trạng thái CHƯA CHỌN (Unselected)
                binding.cardContainer.setCardBackgroundColor(
                    ContextCompat.getColor(context, R.color.item_card_bg_default)
                )
                binding.cardContainer.strokeColor =
                    ContextCompat.getColor(context, R.color.item_stroke_default)
                binding.cardContainer.strokeWidth = dpToPx(1f)

                binding.tvModelName.setTextColor(
                    ContextCompat.getColor(context, R.color.text_default)
                )
            }

            // Xử lý sự kiện click chuyển đổi item
            binding.root.setOnClickListener {
                val currentPos = bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos != selectedPosition) {
                    val previousPos = selectedPosition

                    // Cập nhật model state
                    itemList[previousPos].isSelected = false
                    itemList[currentPos].isSelected = true
                    selectedPosition = currentPos

                    // Cập nhật giao diện chỉ cho 2 item bị ảnh hưởng để tối ưu hiệu năng
                    notifyItemChanged(previousPos)
                    notifyItemChanged(currentPos)

                    // Bắn callback ra ngoài Activity
                    onModelSelected(itemList[currentPos])
                }
            }
        }

        private fun dpToPx(dp: Float): Int {
            val density = binding.root.context.resources.displayMetrics.density
            return (dp * density).toInt()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val binding = ItemModelSelectorBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ModelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(itemList[position], position)
    }

    override fun getItemCount(): Int = itemList.size

    /**
     * Lấy phần tử mô hình hiện tại đang được chọn
     */
    fun getSelectedItem(): ARModelItem? {
        return if (selectedPosition in itemList.indices) itemList[selectedPosition] else null
    }
}
