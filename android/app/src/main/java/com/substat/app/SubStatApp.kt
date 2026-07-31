package com.substat.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.substat.app.data.Repo
import com.substat.app.data.SettingsStore
import com.substat.app.data.SubStatApi

class SubStatApp : Application() {

    lateinit var store: SettingsStore
        private set
    lateinit var api: SubStatApi
        private set
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        api = SubStatApi(
            baseUrlProvider = { store.baseUrlCache },
            cookieProvider = { store.cookieCache },
            cookieSetter = { store.setCookie(it) },
        )
        repo = Repo(api, store)
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_DUE, "订阅到期提醒", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "订阅即将扣费时提醒" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    companion object {
        const val CHANNEL_DUE = "due"
    }
}
