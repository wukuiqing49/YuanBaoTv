package com.wkq.bao.feature.app.utils

import android.content.Context
import com.wkq.bao.core.database.entity.MediaSeriesType

object MediaLabels {
    fun genreOrType(context: Context, genre: String, type: String): String = genre.ifBlank {
        context.getString(
            when (type) {
                MediaSeriesType.MOVIE -> com.wkq.bao.feature.res.R.string.nav_movie
                MediaSeriesType.TV -> com.wkq.bao.feature.res.R.string.nav_tv
                MediaSeriesType.CARTOON -> com.wkq.bao.feature.res.R.string.nav_cartoon
                else -> com.wkq.bao.feature.res.R.string.nav_all_media
            }
        )
    }
}
