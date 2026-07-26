package fr.groggy.racecontrol.tv.ui.info

import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import fr.groggy.racecontrol.tv.R

class InfoCardPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 24, 32, 24)
            setBackgroundColor(ContextCompat.getColor(parent.context, R.color.f1_black_elevated))
            setTextColor(ContextCompat.getColor(parent.context, R.color.f1_white))
            textSize = 18f
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundResource(R.drawable.item_background)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val row = item as InfoRowItem
        val tv = viewHolder.view as TextView
        tv.text = buildString {
            append(row.title)
            if (row.subtitle.isNotBlank()) {
                append('\n')
                append(row.subtitle)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
