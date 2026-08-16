package com.campuslink.mobile.core.network

class ApiException(
    val statusCode: Int,
    override val message: String,
    val errorCode: String? = null,
    val validationErrors: Map<String, String> = emptyMap(),
    val authUrl: String? = null,
) : Exception(message)
