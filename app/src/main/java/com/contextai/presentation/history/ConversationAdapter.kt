package com.contextai.presentation.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.contextai.databinding.ItemConversationBinding
import com.contextai.domain.model.ConversationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationAdapter(
    private val onDelete: (ConversationEntity) -> Unit
) : ListAdapter<ConversationEntity, ConversationAdapter.ViewHolder>(DIFF_CALLBACK) {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault())
    private val expandedIds = mutableSetOf<Long>()

    inner class ViewHolder(val binding: ItemConversationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        val isExpanded = expandedIds.contains(item.id)

        b.tvAppName.text = item.appName
        b.tvContextType.text = item.contextType.displayName
        b.tvTimestamp.text = dateFormat.format(Date(item.timestamp))
        b.tvUserQuery.text = item.userQuery
        b.tvAiResponse.text = item.aiResponse
        b.tvAiResponse.visibility = if (isExpanded) View.VISIBLE else View.GONE
        b.ivExpand.rotation = if (isExpanded) 180f else 0f

        b.root.setOnClickListener {
            if (isExpanded) expandedIds.remove(item.id) else expandedIds.add(item.id)
            notifyItemChanged(position)
        }
    }

    fun attachSwipeToDelete(recyclerView: RecyclerView) {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val item = getItem(viewHolder.bindingAdapterPosition)
                onDelete(item)
            }
        }).attachToRecyclerView(recyclerView)
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ConversationEntity>() {
            override fun areItemsTheSame(old: ConversationEntity, new: ConversationEntity) = old.id == new.id
            override fun areContentsTheSame(old: ConversationEntity, new: ConversationEntity) = old == new
        }
    }
}
