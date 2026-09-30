package com.flutterrtmp.broadcaster.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EndpointRedactorTest {
    @Test
    fun `drops stream key keeps host and app`() {
        val out = EndpointRedactor.redact("rtmp://a.rtmp.youtube.com/live2/abcd-efgh-ijkl")
        assertEquals("rtmp://a.rtmp.youtube.com/live2/***", out)
    }

    @Test
    fun `drops query and credentials`() {
        val out = EndpointRedactor.redact("rtmps://user:pw@live.example.com:443/app/key?token=secret")
        assertEquals("rtmps://live.example.com:443/app/***", out)
        assertFalse(out.contains("secret") || out.contains("pw"))
    }

    @Test
    fun `single segment is treated as key`() {
        assertEquals("rtmp://host/***", EndpointRedactor.redact("rtmp://host/key"))
    }

    @Test
    fun `garbage and empty`() {
        assertEquals("***", EndpointRedactor.redact("not a url"))
        assertEquals("(empty)", EndpointRedactor.redact(""))
    }
}
