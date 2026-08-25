package com.wkq.bao

import android.app.Application
import com.wkq.bao.core.base.diagnostics.AppDiagnostics
import com.wkq.bao.core.database.AppDatabase
import com.wkq.bao.core.media.artwork.NasArtworkLoader
import com.wkq.bao.core.media.download.DownloadWorkScheduler
import com.wkq.bao.core.nas.security.NasCredentialMigration
import com.wkq.util.CoreUtils
import com.wkq.util.CoreUtilsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YuanBaoTvApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CoreUtils.init(
            context = this,
            config = CoreUtilsConfig(
                debug = BuildConfig.DEBUG,
                initLog = false,
                logCaptureCrash = false
            )
        )
        NasArtworkLoader.install(this)
        AppDiagnostics.record(this, "app", "started")
        DownloadWorkScheduler.enqueue(this)
        applicationScope.launch {
            val database = AppDatabase.getInstance(this@YuanBaoTvApplication)
            NasCredentialMigration.migratePlaintextCredentials(database)
            database.mediaDao().clearLegacyStockArtwork(System.currentTimeMillis())
        }
    }
}
