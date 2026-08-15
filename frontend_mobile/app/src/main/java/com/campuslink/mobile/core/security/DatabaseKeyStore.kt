package com.campuslink.mobile.core.security

import android.content.Context
import android.annotation.SuppressLint
import java.security.SecureRandom

class DatabaseKeyStore(context: Context, private val crypto: CryptoManager) {
    private val preferences = context.getSharedPreferences("database_key", Context.MODE_PRIVATE)

    @SuppressLint("ApplySharedPref") // 数据库打开前必须确认包装密钥已经落盘，不能异步 apply。
    fun getOrCreate(): ByteArray {
        val stored = preferences.getString(KEY_VALUE, null)
        if (stored != null) return crypto.decrypt(KEY_ALIAS, stored)
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        preferences.edit().putString(KEY_VALUE, crypto.encrypt(KEY_ALIAS, generated)).commit()
        return generated
    }

    companion object {
        private const val KEY_ALIAS = "campuslink.database.v1"
        private const val KEY_VALUE = "wrapped_key"
    }
}
