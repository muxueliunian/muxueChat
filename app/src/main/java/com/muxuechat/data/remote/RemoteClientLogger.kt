package com.muxuechat.data.remote

import android.util.Log

/** 让 remote client 的网络行为可以在纯 JVM 测试中运行，生产环境仍统一写入 muxueChat tag。 */
internal interface RemoteClientLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
}

internal class AndroidRemoteClientLogger(
    private val tag: String,
) : RemoteClientLogger {
    override fun debug(message: String) {
        Log.d(tag, message)
    }

    override fun info(message: String) {
        Log.i(tag, message)
    }

    override fun warn(message: String) {
        Log.w(tag, message)
    }
}
