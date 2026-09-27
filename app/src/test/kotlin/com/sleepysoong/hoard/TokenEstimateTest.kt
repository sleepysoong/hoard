package com.sleepysoong.hoard

import com.sleepysoong.hoard.data.estimateTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM: token estimates close to real tokenizers for Korean and English. */
class TokenEstimateTest {
    @Test fun koreanCountsAboutOnePerSyllable() {
        // Real GPT-4o/Claude tokenizers: roughly 1 token per Hangul syllable.
        assertEquals(5, estimateTokens("안녕하세요"))
        val para = "오늘 회의에서 출시 일정을 다음 주로 미루기로 결정했습니다"
        val syllables = para.count { it in '\uAC00'..'\uD7A3' }
        assertTrue(estimateTokens(para) >= syllables)
    }

    @Test fun englishStaysAboutFourCharsPerToken() {
        assertEquals(3, estimateTokens("hello world!")) // 12 chars
        assertEquals(1, estimateTokens(""))
    }

    @Test fun mixedAndEmojiDoNotCrash() {
        assertTrue(estimateTokens("Kotlin으로 앱 만들기 🚀") in 8..12)
    }
}
