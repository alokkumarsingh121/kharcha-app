package com.alok.kharcha

import org.junit.Assert.assertTrue
import org.junit.Test

class ParserTest {
    @Test
    fun allBankMessageCases() {
        val fails = ParserCases.run()
        assertTrue("Parser failures:\n" + fails.joinToString("\n"), fails.isEmpty())
    }
}
