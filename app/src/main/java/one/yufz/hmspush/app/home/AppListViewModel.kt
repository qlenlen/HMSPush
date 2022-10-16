package one.yufz.hmspush.app.home

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import one.yufz.hmspush.common.BridgeUri
import one.yufz.hmspush.common.BridgeWrap
import one.yufz.hmspush.common.model.PushHistoryModel
import one.yufz.hmspush.common.model.PushSignModel

class AppListViewModel(val context: Application) : AndroidViewModel(context) {
    companion object {
        private const val TAG = "AppListViewModel"
    }

    private val supportedAppListFlow = MutableStateFlow<List<String>>(emptyList())

    private val filterKeywords = MutableStateFlow<String>("")

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "onReceive() called with: context = $context, intent = $intent")
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_CHANGED -> viewModelScope.launch { loadSupportedAppList() }
            }
        }
    }

    private val registeredListFlow = MutableStateFlow<Set<PushSignModel>>(emptySet())
    private val pushRegisterObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            Log.d(TAG, "pushRegisterObserver onChange() called with: selfChange = $selfChange")
            loadRegisteredList()
        }
    }

    private val historyListFlow = MutableStateFlow<Set<PushHistoryModel>>(emptySet())
    private val pushHistoryObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            Log.d(TAG, "pushHistoryObserver onChange() called with: selfChange = $selfChange")
            loadPushHistory()
        }
    }

    init {
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(packageReceiver, intentFilter)

        viewModelScope.launch {
            loadSupportedAppList()
            loadRegisteredList()
            loadPushHistory()
        }
        context.contentResolver.registerContentObserver(BridgeUri.PUSH_REGISTERED.toUri(), false, pushRegisterObserver)
        context.contentResolver.registerContentObserver(BridgeUri.PUSH_HISTORY.toUri(), false, pushHistoryObserver)
    }

    private suspend fun loadSupportedAppList() {
        val intent = Intent("com.huawei.push.msg.NOTIFY_MSG")
        val list = context.packageManager.queryIntentServices(
            intent,
            PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or
                    PackageManager.MATCH_DISABLED_COMPONENTS
        )
        Log.d(TAG, "loadAppList() called, list = ${list.size}")
        list.map { it.serviceInfo.packageName }
            .let { supportedAppListFlow.emit(it) }
    }

    private fun loadRegisteredList() {
        viewModelScope.launch {
            val registered = BridgeWrap.getRegistered(context)
            registeredListFlow.emit(registered)
        }
    }

    private fun loadPushHistory() {
        viewModelScope.launch {
            val history = BridgeWrap.getPushHistory(context)
            historyListFlow.emit(history)
        }
    }

    fun observeAppList(): Flow<List<AppInfo>> {
        return combine(supportedAppListFlow, registeredListFlow, historyListFlow, ::mergeSource)
            .combine(filterKeywords, ::filterAppList)
    }

    private fun filterAppList(list: List<AppInfo>, keywords: String): List<AppInfo> {
        if (keywords.isEmpty()) return list

        return list.filter {
            it.name.contains(keywords, true) || it.packageName.contains(keywords, true)
        }
    }

    private fun mergeSource(appList: List<String>, registered: Set<PushSignModel>, history: Set<PushHistoryModel>): List<AppInfo> {
        val pm = context.packageManager
        val registeredSet = registered.map { it.packageName }
        val historyMap = history.associateBy { it.packageName }
        return appList.map { packageName ->
            AppInfo(
                packageName = packageName,
                name = pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString(),
                registered = registeredSet.contains(packageName),
                lastPushTime = historyMap[packageName]?.pushTime
            )
        }
            .sortedWith(compareBy({ !it.registered }, { Long.MAX_VALUE - (it.lastPushTime ?: 0L) }))
    }

    fun filter(keywords: String) {
        viewModelScope.launch {
            filterKeywords.emit(keywords)
        }
    }

    fun unregisterPush(packageName: String) {
        Util.unregisterPush(context, packageName)
    }

    override fun onCleared() {
        super.onCleared()
        context.unregisterReceiver(packageReceiver)
        context.contentResolver.unregisterContentObserver(pushRegisterObserver)
        context.contentResolver.unregisterContentObserver(pushHistoryObserver)
    }
}