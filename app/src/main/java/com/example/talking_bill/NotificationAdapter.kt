package com.example.talking_bill

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.os.Handler
import android.os.Looper

/**
 * Adapter for displaying notifications in a RecyclerView.
 * Handles notification list updates and item interactions.
 */
class NotificationAdapter : ListAdapter<String, NotificationAdapter.ViewHolder>(NotificationDiffCallback()) {
    private val mainHandler = Handler(Looper.getMainLooper())
    var onItemLongClick: ((Int) -> Unit)? = null
    private val TAG = "NotificationAdapter"
    private var selectedPosition: Int = RecyclerView.NO_POSITION

    /**
     * ViewHolder for notification items.
     */
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.notificationText)
        private val editableViewId: Int =
            view.resources.getIdentifier("notificationEditText", "id", view.context.packageName)
        val editText: EditText? = if (editableViewId != 0) view.findViewById(editableViewId) else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return try {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification, parent, false)
            ViewHolder(view)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating view holder", e)
            // Return a basic view holder with an empty view as fallback
            ViewHolder(View(parent.context))
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            if (position in 0 until itemCount) {
                val itemText = getItem(position)
                val isSelected = position == selectedPosition

                holder.textView.text = itemText
                holder.editText?.setText(itemText)
                holder.editText?.keyListener = null
                holder.editText?.setTextIsSelectable(true)
                holder.editText?.showSoftInputOnFocus = false

                val hasEditableView = holder.editText != null
                holder.textView.visibility = if (isSelected && hasEditableView) View.GONE else View.VISIBLE
                holder.editText?.visibility = if (isSelected) View.VISIBLE else View.GONE

                holder.itemView.setOnClickListener {
                    val adapterPos = holder.adapterPosition
                    if (adapterPos == RecyclerView.NO_POSITION) return@setOnClickListener

                    val previous = selectedPosition
                    selectedPosition = if (previous == adapterPos) RecyclerView.NO_POSITION else adapterPos

                    if (previous != RecyclerView.NO_POSITION) {
                        notifyItemChanged(previous)
                    }
                    notifyItemChanged(adapterPos)
                }

                holder.itemView.setOnLongClickListener {
                    onItemLongClick?.invoke(holder.adapterPosition)
                    true
                }
            } else {
                Log.e(TAG, "Invalid position: $position")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding view holder", e)
        }
    }

    /**
     * Add a new notification to the list.
     * @param notification The notification text to add
     */
    fun addNotification(notification: String) {
        mainHandler.post {
            try {
                val currentList = currentList.toMutableList()
                currentList.add(0, notification)
                selectedPosition = RecyclerView.NO_POSITION
                submitList(currentList)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding notification", e)
            }
        }
    }

    /**
     * Update the entire notification list.
     * @param newNotifications The new list of notifications
     */
    fun updateNotifications(newNotifications: List<String>) {
        mainHandler.post {
            try {
                selectedPosition = RecyclerView.NO_POSITION
                submitList(newNotifications)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating notifications", e)
            }
        }
    }

    /**
     * Remove a notification at the specified position.
     * @param position The position of the notification to remove
     */
    fun removeItem(position: Int) {
        mainHandler.post {
            try {
                if (position in 0 until itemCount) {
                    val currentList = currentList.toMutableList()
                    currentList.removeAt(position)
                    selectedPosition = RecyclerView.NO_POSITION
                    submitList(currentList)
                } else {
                    Log.e(TAG, "Invalid position for removal: $position")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing item", e)
            }
        }
    }

    /**
     * Clear all notifications from the list.
     */
    fun clearNotifications() {
        mainHandler.post {
            try {
                selectedPosition = RecyclerView.NO_POSITION
                submitList(emptyList())
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing notifications", e)
            }
        }
    }
}

/**
 * DiffUtil callback for efficient list updates.
 */
private class NotificationDiffCallback : DiffUtil.ItemCallback<String>() {
    override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
        return oldItem == newItem
    }
} 