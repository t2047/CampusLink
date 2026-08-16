package com.campuslink.mobile.core.security

import android.content.Context
import com.campuslink.mobile.core.model.AuthSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionStore(context: Context, private val crypto: CryptoManager) {
    private val preferences = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val mutableSession = MutableStateFlow(load())
    val session: StateFlow<AuthSession?> = mutableSession.asStateFlow()

    fun save(value: AuthSession) {
        preferences.edit()
            .putString(KEY_TOKEN, crypto.encrypt(TOKEN_ALIAS, value.token.encodeToByteArray()))
            .putString(KEY_EMAIL, value.email)
            .putString(KEY_ROLE, value.role)
            .apply()
        mutableSession.value = value
    }

    fun clear() {
        preferences.edit().clear().apply()
        mutableSession.value = null
    }

    private fun load(): AuthSession? = runCatching {
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return null
        val email = preferences.getString(KEY_EMAIL, null) ?: return null
        val role = preferences.getString(KEY_ROLE, null) ?: return null
        AuthSession(crypto.decrypt(TOKEN_ALIAS, encrypted).decodeToString(), email, role)
    }.getOrElse {
        preferences.edit().clear().apply()
        null
    }

    companion object {
        private const val TOKEN_ALIAS = "campuslink.jwt.v1"
        private const val KEY_TOKEN = "token"
        private const val KEY_EMAIL = "email"
        private const val KEY_ROLE = "role"
    }
}
