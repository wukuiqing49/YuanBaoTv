package com.wkq.bao.feature.app

import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.wkq.base.activity.BaseActivity
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.database.entity.MediaSeriesEntity
import com.wkq.bao.feature.app.adapter.PosterCardAdapter
import com.wkq.bao.feature.app.databinding.ActivityDownloadsBinding
import com.wkq.bao.feature.app.utils.TvFocusHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 圆宝TV 下载管理页
 */
class DownloadsActivity : BaseActivity<ActivityDownloadsBinding>() {

    private lateinit var downloadedAdapter: PosterCardAdapter
    private val database by lazy { AppDatabase.getInstance(this) }

    private val openStorageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
                val storageManager = com.wkq.bao.core.media.storage.TvStorageManager(this)
                storageManager.saveStorageRoot(uri)
                val stat = storageManager.getStorageInfo(uri.toString())
                binding.tvStorageLabel.text = "USB 外置存储"
                binding.tvStorageCapacity.text = stat.formattedUsage
                val progress = if (stat.totalBytes > 0) {
                    (((stat.totalBytes - stat.freeBytes).toFloat() / stat.totalBytes) * 100).toInt()
                } else 0
                binding.pbStorage.progress = progress
                Toast.makeText(this, "已授权外置存储下载路径", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "授权失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun initView() {
        TvFocusHelper.applyFocusScale(binding.cardDownloadTask)
        TvFocusHelper.applyFocusScale(binding.btnSelectStorage)
        TvFocusHelper.applyFocusScale(binding.btnTaskPause)
        TvFocusHelper.applyFocusScale(binding.btnTaskCancel)

        binding.btnSelectStorage.setOnClickListener {
            openStorageLauncher.launch(null)
        }

        downloadedAdapter = PosterCardAdapter { series ->
            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra("seriesId", series.id)
            }
            startActivity(intent)
        }
        binding.rvDownloaded.adapter = downloadedAdapter

        binding.btnTaskPause.setOnClickListener {
            Toast.makeText(this, "下载已暂停", Toast.LENGTH_SHORT).show()
        }

        binding.btnTaskCancel.setOnClickListener {
            Toast.makeText(this, "下载任务已取消", Toast.LENGTH_SHORT).show()
        }
    }

    override fun initData() {
        // 观察真实离线下载任务队列
        lifecycleScope.launch {
            database.downloadDao().getAllTasks().collectLatest { tasks ->
                if (tasks.isNotEmpty()) {
                    val currentTask = tasks.first()
                    binding.tvTaskTitle.text = "剧集下载 Episode ${currentTask.episodeId}"
                    val progress = if (currentTask.totalBytes > 0) {
                        ((currentTask.downloadedBytes.toFloat() / currentTask.totalBytes) * 100).toInt()
                    } else 0
                    binding.pbTask.progress = progress
                    binding.tvTaskSpeed.text = "${currentTask.status} • %d MB / %d MB".format(
                        currentTask.downloadedBytes / 1024 / 1024,
                        currentTask.totalBytes / 1024 / 1024
                    )
                }
            }
        }

        lifecycleScope.launch {
            database.mediaDao().getAllSeries().collectLatest { list ->
                if (list.isEmpty()) {
                    downloadedAdapter.submitList(
                        listOf(
                            MediaSeriesEntity(1, "汪汪队立大功 S01", type = "CARTOON", genre = "已缓存", totalSeasons = 1),
                            MediaSeriesEntity(2, "小猪佩奇 S01", type = "CARTOON", genre = "已缓存", totalSeasons = 1),
                            MediaSeriesEntity(3, "星际穿越", type = "MOVIE", genre = "已缓存 4K", totalSeasons = 1)
                        )
                    )
                } else {
                    downloadedAdapter.submitList(list)
                }
            }
        }
    }
}
