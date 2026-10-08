package com.wkq.bao.feature.app

import android.content.Context
import android.widget.ArrayAdapter
import android.view.inputmethod.InputMethodManager
import com.lxj.xpopup.core.CenterPopupView
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.core.media.repository.LocalMetadataUpdate
import com.wkq.bao.feature.app.databinding.PopupMediaEditorBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import com.wkq.bao.feature.res.R as ResR

internal class MediaEditorPopup(context: Context, private val series: MediaSeriesEntity,
    private val onPoster: () -> Unit, private val onSave: (LocalMetadataUpdate) -> Boolean,
    private val initialDraft: LocalMetadataUpdate? = null
) : CenterPopupView(context) {
    private lateinit var binding: PopupMediaEditorBinding
    private var posterUri: String? = null
    private val types = listOf("MOVIE", "TV", "CARTOON", "LOCAL")
    override val implLayoutId get() = R.layout.popup_media_editor
    override fun onCreate() {
        super.onCreate()
        binding = PopupMediaEditorBinding.bind(checkNotNull(contentView))
        binding.etMediaTitle.setText(initialDraft?.title ?: series.title)
        binding.etMediaYear.setText(initialDraft?.year ?: series.year)
        binding.etMediaGenre.setText(initialDraft?.genre ?: series.genre)
        binding.etMediaDescription.setText(initialDraft?.description ?: series.description)
        binding.spinnerMediaType.adapter = ArrayAdapter(context, R.layout.item_library_sort,
            listOf(ResR.string.nav_movie, ResR.string.nav_tv, ResR.string.nav_cartoon, ResR.string.media_edit_type_local).map(context::getString))
        binding.spinnerMediaType.setSelection(types.indexOf(initialDraft?.type ?: series.type).coerceAtLeast(0))
        initialDraft?.posterUri?.let(::setPoster)
        listOf(binding.btnEditorSave, binding.btnEditorCancel, binding.btnMediaPoster).forEach {
            it.backgroundTintList = null
            TvFocusHelper.applyFocusScale(it, 1.03f)
        }
        binding.btnMediaPoster.setOnClickListener { onPoster() }
        binding.btnEditorCancel.setOnClickListener { dismiss() }
        binding.btnEditorSave.setOnClickListener {
            val title = binding.etMediaTitle.text.toString().trim()
            val year = binding.etMediaYear.text.toString().trim()
            if (title.isBlank()) {
                binding.etMediaTitle.error = context.getString(ResR.string.media_edit_title_required)
                binding.etMediaTitle.requestFocus()
            } else if (year.isNotEmpty() && (year.length != 4 || year.toIntOrNull() !in 1800..2999)) {
                binding.etMediaYear.error = context.getString(ResR.string.media_edit_year_invalid)
                binding.etMediaYear.requestFocus()
            } else if (onSave(LocalMetadataUpdate(title, year, binding.etMediaGenre.text.toString(),
                binding.etMediaDescription.text.toString(), types[binding.spinnerMediaType.selectedItemPosition], posterUri))) {
                setSaving(true)
            }
        }
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        binding.root.post {
            if (TvFocusHelper.isTelevision(binding.root)) binding.btnEditorSave.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
    }
    fun setPoster(uri: String) {
        posterUri = uri
        if (::binding.isInitialized) binding.btnMediaPoster.setText(ResR.string.media_edit_poster_selected)
    }
    fun draft(): LocalMetadataUpdate? = if (::binding.isInitialized) LocalMetadataUpdate(
        binding.etMediaTitle.text.toString(), binding.etMediaYear.text.toString(),
        binding.etMediaGenre.text.toString(), binding.etMediaDescription.text.toString(),
        types[binding.spinnerMediaType.selectedItemPosition], posterUri) else initialDraft
    fun setSaving(saving: Boolean) {
        if (::binding.isInitialized) {
            binding.btnEditorSave.isEnabled = !saving
            binding.btnMediaPoster.isEnabled = !saving
        }
    }
}
