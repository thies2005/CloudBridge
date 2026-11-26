package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.widget.ArrayAdapter
import ca.pkay.rcloneexplorer.R
import com.google.android.material.textfield.MaterialAutoCompleteTextView

/** A non-editable exposed menu that retains the selected adapter position. */
class ExposedChoice @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MaterialAutoCompleteTextView(context, attrs) {
    var selectedItemPosition: Int = -1
        private set
    val count: Int get() = adapter?.count ?: 0
    val selectedItem: Any? get() = if (selectedItemPosition in 0 until count) adapter.getItem(selectedItemPosition) else null
    var onChoiceChanged: ((Int) -> Unit)? = null

    init {
        keyListener = null
        setDropDownBackgroundResource(R.drawable.bg_cloudbridge_popup)
        setOnItemClickListener { _, _, position, _ -> selectPosition(position) }
    }

    fun <T> setChoices(choices: ArrayAdapter<T>) {
        setAdapter(choices)
        selectPosition(if (choices.count > 0) 0 else -1)
    }

    fun selectPosition(position: Int) {
        selectedItemPosition = if (position in 0 until count) position else -1
        setText(selectedItem?.toString().orEmpty(), false)
        if (selectedItemPosition >= 0) onChoiceChanged?.invoke(selectedItemPosition)
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putParcelable("super", super@ExposedChoice.onSaveInstanceState())
        putInt("position", selectedItemPosition)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable("super"))
            val callback = onChoiceChanged
            onChoiceChanged = null
            selectPosition(state.getInt("position", -1))
            onChoiceChanged = callback
        } else super.onRestoreInstanceState(state)
    }
}
