package com.campuslink.mobile

import android.app.Application
import com.campuslink.mobile.core.network.AuthApi
import com.campuslink.mobile.core.network.AuthenticatedHttpClient
import com.campuslink.mobile.core.network.ChatSseClient
import com.campuslink.mobile.core.network.FacilitiesApi
import com.campuslink.mobile.core.network.HttpClientFactory
import com.campuslink.mobile.core.network.LostFoundApi
import com.campuslink.mobile.core.network.MailApi
import com.campuslink.mobile.core.security.CryptoManager
import com.campuslink.mobile.core.security.DatabaseKeyStore
import com.campuslink.mobile.core.security.SessionStore
import com.campuslink.mobile.core.settings.AppSettings
import com.campuslink.mobile.core.storage.CampusDatabase
import com.campuslink.mobile.core.storage.ChatRepository
import com.campuslink.mobile.facilities.FacilitiesRepository
import com.campuslink.mobile.lostfound.LostFoundRepository
import com.campuslink.mobile.mail.MailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
    private val httpClients = HttpClientFactory.create()
    val authApi = AuthApi(httpClients.rest, BuildConfig.API_BASE_URL, json)
    val chatClient = ChatSseClient(
        httpClients.sse,
        BuildConfig.API_BASE_URL,
        json,
        tokenProvider = { sessionStore.session.value?.token },
        onUnauthorized = sessionStore::clear,
    )
    private val authenticatedHttpClient = AuthenticatedHttpClient(
        httpClients.rest,
        BuildConfig.API_BASE_URL,
        json,
        tokenProvider = { sessionStore.session.value?.token },
        onUnauthorized = sessionStore::clear,
    )
    val facilitiesRepository = FacilitiesRepository(FacilitiesApi(authenticatedHttpClient, json))
    val lostFoundRepository = LostFoundRepository(LostFoundApi(authenticatedHttpClient, json))
    // Mail 服务本地运行在 5000 端口，云端则通过同源 HTTPS 代理访问。
    private val mailHttpClient = AuthenticatedHttpClient(
        httpClients.rest,
        BuildConfig.MAIL_API_BASE_URL,
        json,
        tokenProvider = { sessionStore.session.value?.token },
        onUnauthorized = sessionStore::clear,
    )
    val mailRepository = MailRepository(MailApi(mailHttpClient, json))
}
