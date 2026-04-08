package com.example.stalker2ai

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

class FilesAdapter(
    private var files: List<File>,
    private val onDescribeClick: (File) -> Unit,
    private val onSendClick: (File) -> Unit
) : RecyclerView.Adapter<FilesAdapter.FileViewHolder>() {

    private val savingFiles = mutableSetOf<String>()
    private val sendingFiles = mutableSetOf<String>()

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val btnDescribe: Button = view.findViewById(R.id.btnDescribe)
        val btnSend: Button = view.findViewById(R.id.btnSend)
        val pbSaving: ProgressBar = view.findViewById(R.id.pbSaving)
        val pbSending: ProgressBar = view.findViewById(R.id.pbSending)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val file = files[position]
        val name = file.name
        
        val halfLength = name.length / 2
        val displayedName = if (halfLength > 0) {
            name.substring(0, halfLength) + "..."
        } else {
            name
        }
        holder.tvFileName.text = displayedName

        val isSaving = savingFiles.contains(file.absolutePath)
        val isSending = sendingFiles.contains(file.absolutePath)
        
        if (isSaving) {
            holder.btnDescribe.visibility = View.INVISIBLE
            holder.pbSaving.visibility = View.VISIBLE
        } else {
            holder.btnDescribe.visibility = View.VISIBLE
            holder.pbSaving.visibility = View.GONE
            
            val isFilled = checkIfFilled(file)
            if (isFilled) {
                holder.btnDescribe.setText(R.string.btn_filled)
                holder.btnDescribe.backgroundTintList = ColorStateList.valueOf("#4CAF50".toColorInt())
            } else {
                holder.btnDescribe.setText(R.string.btn_describe)
                holder.btnDescribe.backgroundTintList = ColorStateList.valueOf("#FF0000".toColorInt())
            }
        }

        if (isSending) {
            holder.btnSend.visibility = View.INVISIBLE
            holder.pbSending.visibility = View.VISIBLE
        } else {
            holder.btnSend.visibility = View.VISIBLE
            holder.pbSending.visibility = View.GONE
            
            val isFilled = checkIfFilled(file)
            val isSent = checkIfSent(file)

            if (isSent) {
                holder.btnSend.setText(R.string.btn_sent)
                holder.btnSend.backgroundTintList = ColorStateList.valueOf("#4CAF50".toColorInt())
                holder.btnSend.isEnabled = true
                holder.btnSend.alpha = 1.0f
            } else {
                holder.btnSend.setText(R.string.btn_send)
                holder.btnSend.backgroundTintList = ColorStateList.valueOf("#FF0000".toColorInt())
                holder.btnSend.isEnabled = isFilled
                holder.btnSend.alpha = if (isFilled) 1.0f else 0.4f
            }
        }

        holder.btnDescribe.setOnClickListener { onDescribeClick(file) }
        holder.btnSend.setOnClickListener { onSendClick(file) }
    }

    fun setSaving(file: File, isSaving: Boolean) {
        if (isSaving) savingFiles.add(file.absolutePath) else savingFiles.remove(file.absolutePath)
        notifyItemChangedByFile(file)
    }

    fun setSending(file: File, isSending: Boolean) {
        if (isSending) sendingFiles.add(file.absolutePath) else sendingFiles.remove(file.absolutePath)
        notifyItemChangedByFile(file)
    }

    private fun notifyItemChangedByFile(file: File) {
        val index = files.indexOfFirst { it.absolutePath == file.absolutePath }
        if (index != -1) notifyItemChanged(index)
    }

    private fun checkIfFilled(file: File): Boolean {
        return try {
            val jsonString = FileInputStream(file).use { stream ->
                stream.bufferedReader().use { it.readText() }
            }
            val jsonObject = JSONObject(jsonString)
            
            val info = jsonObject.optString("finding_info", "")
            val isUseful = jsonObject.optString("is_useful", "")
            val location = jsonObject.optString("search_location", "")
            val water = jsonObject.optString("search_water", "")
            val soil = jsonObject.optString("search_soil", "")
            
            info.isNotEmpty() && isUseful.isNotEmpty() && 
            location.isNotEmpty() && water.isNotEmpty() && soil.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun checkIfSent(file: File): Boolean {
        return try {
            val jsonString = FileInputStream(file).use { stream ->
                stream.bufferedReader().use { it.readText() }
            }
            val jsonObject = JSONObject(jsonString)
            jsonObject.optBoolean("is_sent", false)
        } catch (_: Exception) {
            false
        }
    }

    override fun getItemCount() = files.size

    fun updateFiles(newFiles: List<File>) {
        val diffResult = DiffResultWrapper(DiffUtil.calculateDiff(FileDiffCallback(files, newFiles)))
        files = newFiles
        diffResult.dispatchUpdatesTo(this)
    }

    private class DiffResultWrapper(private val result: DiffUtil.DiffResult) {
        fun dispatchUpdatesTo(adapter: RecyclerView.Adapter<*>) {
            result.dispatchUpdatesTo(adapter)
        }
    }

    private class FileDiffCallback(
        private val oldList: List<File>,
        private val newList: List<File>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition].absolutePath == newList[newItemPosition].absolutePath
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            oldList[oldItemPosition].absolutePath == newList[newItemPosition].absolutePath &&
            oldList[oldItemPosition].lastModified() == newList[newItemPosition].lastModified()
    }
}
