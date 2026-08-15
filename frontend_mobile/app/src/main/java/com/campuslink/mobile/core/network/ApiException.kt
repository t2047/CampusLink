package com.campuslink.mobile.core.network

class ApiException(
    val statusCode: Int,
    override val message: String,
    val errorCode: String? = null,
) : Exception(message)
