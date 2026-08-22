package com.wkq.bao

import android.app.Application
import com.wkq.util.CoreUtils
import com.wkq.util.CoreUtilsConfig

class YuanBaoTvApplication : Application() {

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
    }
}
