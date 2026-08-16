package com.campuslink.mobile.ui.lostfound

import com.campuslink.mobile.BuildConfig
import com.campuslink.mobile.core.model.ItemCategory

internal fun ItemCategory.displayName(): String = name.lowercase()
    .split('_')
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }

internal fun resolveLostFoundImageUrl(url: String): String = when {
    url.startsWith("https://") || url.startsWith("http://") -> url
    else -> BuildConfig.API_BASE_URL.trimEnd('/') + "/" + url.trimStart('/')
}
