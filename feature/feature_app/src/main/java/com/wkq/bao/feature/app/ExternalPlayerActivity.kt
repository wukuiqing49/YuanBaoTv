package com.wkq.bao.feature.app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.media.external.ExternalPlaybackProvider
import com.wkq.bao.core.media.external.TelevisionPlayerAdapter
import com.wkq.bao.core.media.resolver.MediaResolver
import com.wkq.bao.core.media.resolver.PlaybackSource
import com.wkq.bao.feature.app.databinding.ActivityExternalPlayerBinding
import com.wkq.bao.feature.res.R as Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 外部播放的授权、返回与内置播放器兜底入口，不创建第二个 ExoPlayer。 */
class ExternalPlayerActivity : BaseActivity<ActivityExternalPlayerBinding>() {
    private var ticket: Uri? = null
    private var started = false
    private var prepareJob: Job? = null
    private val externalPlayer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        releaseTicket()
        // 当前小米入口没有可靠完成回传，返回即退出；不能据此推断播完并自动续集。
        Log.d("ExternalPlayer", "Returned code=${result.resultCode}, hasData=${result.data != null}")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        started = savedInstanceState?.getBoolean("external_started") ?: false
        ticket = savedInstanceState?.getString("external_ticket")?.let(Uri::parse)
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        // 唯一 Insets owner；基础布局不再叠加系统安全区。
        val root = binding.root
        val left = root.paddingLeft; val top = root.paddingTop
        val right = root.paddingRight; val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        listOf(binding.btnRetry, binding.btnInternal, binding.btnBack).forEach { button ->
            button.isFocusable = true
            button.isFocusableInTouchMode = true
            button.backgroundTintList = null
            button.supportBackgroundTintList = null
            com.wkq.bao.feature.app.utils.TvFocusHelper.applyFocusScale(button)
        }
        binding.tvTitle.text = intent.getStringExtra("extra_title").orEmpty()
        binding.btnRetry.setOnClickListener { launchSystemPlayer() }
        binding.btnInternal.setOnClickListener { useInternalPlayer() }
        binding.btnBack.setOnClickListener { finish() }
        if (!started) launchSystemPlayer() else {
            // 重建后保留 ActivityResult 注册，等待已有播放器返回，不重复拉起视频。
            binding.layoutActions.visibility = View.GONE
        }
    }

    override fun initData() = Unit

    private fun launchSystemPlayer() {
        if (prepareJob?.isActive == true) return
        val component = TelevisionPlayerAdapter.resolve(this)
        if (component == null) { useInternalPlayer(); return }
        binding.progressPreparing.visibility = View.VISIBLE
        binding.layoutActions.visibility = View.GONE
        binding.tvStatus.setText(Res.string.player_external_preparing)
        binding.btnRetry.isEnabled = false
        prepareJob = lifecycleScope.launch {
            try {
                releaseTicket()
                val source = MediaResolver(this@ExternalPlayerActivity).resolve(intent.getLongExtra("extra_episode_id", 0))
                val uri = when (source) {
                    is PlaybackSource.Local -> source.uri
                    is PlaybackSource.NasStream -> source.uri
                    is PlaybackSource.Unavailable -> error("Media unavailable")
                }
                val shared = ExternalPlaybackProvider.prepare(this@ExternalPlayerActivity, uri,
                    intent.getStringExtra("extra_title").orEmpty())
                ticket = shared.uri
                started = true
                externalPlayer.launch(TelevisionPlayerAdapter.createIntent(component, shared.uri, shared.mimeType,
                    intent.getStringExtra("extra_title").orEmpty()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                releaseTicket()
                started = false
                binding.tvStatus.setText(Res.string.player_external_failed)
                binding.layoutActions.visibility = View.VISIBLE
                binding.btnInternal.requestFocus()
            } finally {
                binding.progressPreparing.visibility = View.GONE
                binding.btnRetry.isEnabled = true
            }
        }
    }

    private fun useInternalPlayer() {
        prepareJob?.cancel()
        releaseTicket()
        PlayerActivity.startInternal(this, intent.getLongExtra("extra_series_id", 0),
            intent.getLongExtra("extra_season_id", 0), intent.getLongExtra("extra_episode_id", 0),
            intent.getStringExtra("extra_title").orEmpty())
        finish()
    }

    private fun releaseTicket() { ticket?.let { ExternalPlaybackProvider.release(this, it) }; ticket = null }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("external_started", started)
        outState.putString("external_ticket", ticket?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) releaseTicket()
        super.onDestroy()
    }
}
