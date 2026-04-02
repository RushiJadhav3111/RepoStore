package com.samyak.repostore.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.samyak.repostore.data.model.AppCategory
import com.samyak.repostore.databinding.ItemCategoryGridBinding

class CategoryGridAdapter(
    private val categories: List<AppCategory>,
    private val onCategoryClick: (AppCategory) -> Unit
) : RecyclerView.Adapter<CategoryGridAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryGridBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(categories[position])
    }

    override fun getItemCount() = categories.size

    inner class ViewHolder(
        private val binding: ItemCategoryGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onCategoryClick(categories[position])
                }
            }
        }

        fun bind(category: AppCategory) {
            binding.tvCategoryName.text = category.displayName
            binding.ivCategoryIcon.setImageResource(category.iconRes)
            
            val context = binding.root.context
            binding.viewIconBackground.backgroundTintList = 
                android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(context, category.colorRes)
                )
        }
    }
}
