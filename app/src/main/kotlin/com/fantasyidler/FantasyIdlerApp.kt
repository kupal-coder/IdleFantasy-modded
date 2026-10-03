package com.fantasyidler

import android.app.Application
import com.fantasyidler.notification.SessionNotificationManager
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.SessionRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FantasyIdlerApp : Application() {

    @Inject lateinit var notificationManager: SessionNotificationManager
    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var sessionRepository: SessionRepository

    override fun onCreate() {
        super.onCreate()
        notificationManager.createChannels()
        ModInit.init(this, playerRepository, sessionRepository)
    }
}
