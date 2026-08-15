package com.campuslink.mobile

import android.app.Application
import com.campuslink.mobile.core.network.AuthApi
import com.campuslink.mobile.core.network.ChatSseClient
import com.campuslink.mobile.core.security.CryptoManager
import com.campuslink.mobile.core.security.DatabaseKeyStore
import com.campuslink.mobile.core.security.SessionStore
import com.campuslink.mobile.core.settings.AppSettings
import com.campuslink.mobile.core.storage.CampusDatabase
import com.campuslink.mobile.core.storage.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CampusLinkApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            container.chatRepository.markInterruptedStreams()
        }
    }
}

class AppContainer(application: Application) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val crypto = CryptoManager()
    val sessionStore = SessionStore(application, crypto)
    val settings = AppSettings(application)
    private val database = CampusDatabase.create(application, DatabaseKeyStore(application, crypto))
    val chatRepository = ChatRepository(database.chatDao(), json)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    val authApi = AuthApi(httpClient, BuildConfig.API_BASE_URL, json)
    val chatClient = ChatSseClient(httpClient, BuildConfig.API_BASE_URL, sessionStore, json)
}
