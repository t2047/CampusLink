package com.campuslink.mobile.ui

import com.campuslink.mobile.core.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class UiPolishTest {
    private val english = strings(AppLanguage.ENGLISH)

    @Test
    fun `home greeting follows local time periods`() {
        assertEquals("Good morning", greetingForHour(8, english.home))
        assertEquals("Good afternoon", greetingForHour(14, english.home))
        assertEquals("Good evening", greetingForHour(22, english.home))
    }

    @Test
    fun `profile initials come from email prefix without inventing a name`() {
        assertEquals("ST", initialsFromEmail("student@nus.edu.sg"))
        assertEquals("?", initialsFromEmail("@nus.edu.sg"))
    }

    @Test
    fun `polished root screens have matching Chinese labels`() {
        val chinese = strings(AppLanguage.CHINESE)

        assertEquals("校园服务", chinese.home.campusServices)
        assertEquals("快捷入口", chinese.home.quickAccess)
        assertEquals("个人资料", chinese.shell.profile)
        assertEquals("清除聊天记录", chinese.profile.clearHistory)
    }
}
