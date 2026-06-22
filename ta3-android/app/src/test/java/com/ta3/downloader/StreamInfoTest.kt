package com.ta3.downloader

import org.junit.Test
import org.schabi.newpipe.extractor.stream.AudioStream

class StreamInfoTest {
    @Test
    fun testAudioStreamMethods() {
        val methods = AudioStream::class.java.methods
        for (m in methods) {
            if (m.name == "getAudioLocale") {
                println("RETURN_TYPE: " + m.returnType.name)
            }
        }
    }
}
