package com.simpletavern.core.llm

import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response

/** One application HTTP exchange per Call, including GET. Library follow-ups are not new user requests. */
class NoHttpReplayInterceptor : Interceptor {
    // Call stays strongly reachable throughout its own follow-ups; completed calls are not retained.
    private val attempted = Collections.synchronizedMap(WeakHashMap<Call, Boolean>())

    override fun intercept(chain: Interceptor.Chain): Response {
        if (attempted.put(chain.call(), true) == true) throw IOException("automatic HTTP request replay refused")
        return chain.proceed(chain.request())
    }
}
