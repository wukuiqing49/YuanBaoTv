package com.wkq.bao.core.media.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import com.wkq.bao.core.database.dao.MediaDao
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class StorageAvailabilityObserver(private val context: Context, private val dao: MediaDao) {
    private val changes = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { trySend(Unit) }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(UsbStorageReceiver.ACTION_USB_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(Unit)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    val offlineSeries = combine(dao.observeLocalStorageLocations(), dao.observeRemoteSeriesIds(), changes) { locations, remote, _ ->
        withContext(Dispatchers.IO) {
            val storage = TvStorageManager(context)
            // 同一卷只查询一次，避免几千部影片重复访问 DocumentsProvider。
            val availability = mutableMapOf<String, Boolean>()
            val reachable = locations.groupBy { it.seriesId }.mapValues { (_, copies) ->
                copies.any { copy ->
                    val uri = Uri.parse(copy.uri)
                    val key = runCatching { DocumentsContract.getTreeDocumentId(uri).substringBefore(':') }.getOrNull()
                        ?: uri.toString()
                    availability.getOrPut(key) {
                        if (uri.scheme == "file") File(uri.path.orEmpty()).exists()
                        else storage.isStorageTargetAvailable(uri)
                    }
                }
            }
            val remoteIds = remote.toSet()
            reachable.filter { (id, available) -> !available && id !in remoteIds }.keys
        }
    }
}
