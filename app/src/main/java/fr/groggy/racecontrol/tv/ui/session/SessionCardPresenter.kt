package fr.groggy.racecontrol.tv.ui.session

import android.net.Uri
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.ImageCardView.CARD_TYPE_FLAG_CONTENT
import androidx.leanback.widget.ImageCardView.CARD_TYPE_FLAG_TITLE
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.f1tv.F1TvClient
import fr.groggy.racecontrol.tv.f1tv.RacingSeries
import javax.inject.Inject

class SessionCardPresenter @Inject constructor(
    private val settingsRepository: SettingsRepository
) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val imageCardView = ImageCardView(parent.context)

        imageCardView.setMainImageDimensions(
            F1TvClient.MAIN_IMAGE_WIDTH,
            F1TvClient.MAIN_IMAGE_HEIGHT
        )
        imageCardView.cardType = CARD_TYPE_FLAG_TITLE or CARD_TYPE_FLAG_CONTENT
        imageCardView.setInfoAreaBackgroundColor(
            ContextCompat.getColor(parent.context, R.color.f1_black_elevated)
        )
        imageCardView.setBackgroundColor(
            ContextCompat.getColor(parent.context, R.color.f1_black_elevated)
        )

        imageCardView.findViewById<TextView>(R.id.title_text)?.apply {
            setLines(2)
            setTextColor(ContextCompat.getColor(parent.context, R.color.f1_white))
        }
        imageCardView.findViewById<TextView>(R.id.content_text)?.apply {
            setTextColor(ContextCompat.getColor(parent.context, R.color.f1_silver))
        }

        return ViewHolder(imageCardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val imageCardView = viewHolder.view as ImageCardView
        val session = item as SessionCard

        imageCardView.titleText = session.name
        val seriesBadge = session.series.takeIf { it != RacingSeries.ALL }?.badge
        imageCardView.contentText = listOfNotNull(seriesBadge, session.contentSubtype)
            .joinToString(" · ")

        if (settingsRepository.getCurrent().displayThumbnailsEnabled) {
            imageCardView.mainImageView?.let { imageView ->
                Glide.with(viewHolder.view.context)
                    .load(session.thumbnail?.url)
                    .into(imageView)
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val imageCardView = viewHolder.view as ImageCardView
        imageCardView.badgeImage = null
        imageCardView.mainImage = null
    }
}

interface SessionCard {
    val name: String
    val contentSubtype: String
    val series: RacingSeries
    val thumbnail: Image?

    interface Image {
        val url: Uri
    }
}
