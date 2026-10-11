package com.cold04.inkreadermgr

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.inkreaderlink_uniffi.SdkOperationException

class ConnectionErrorTest {
    @Test
    fun remoteFailureShowsSdkDetail() {
        val detail = "设备拒绝连接：正在执行其他操作"

        assertEquals(detail, connectionError(SdkOperationException.RemoteFailure(detail)))
    }

    @Test
    fun wrappedRemoteFailureShowsSdkDetail() {
        val detail = "设备拒绝连接：正在执行其他操作"
        val wrapped = IllegalStateException("连接失败", SdkOperationException.RemoteFailure(detail))

        assertEquals(detail, connectionError(wrapped))
    }
}
