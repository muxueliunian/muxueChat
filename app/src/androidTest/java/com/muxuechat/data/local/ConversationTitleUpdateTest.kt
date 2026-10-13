package com.muxuechat.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 验证迟到的标题只能在原快照仍匹配时提交，实际 Room/SQLite 行为需在 API 36 设备运行。 */
@RunWith(AndroidJUnit4::class)
class ConversationTitleUpdateTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao
    private val original = ConversationEntity("conversation", "原标题", 10L, 10L)

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ChatDatabase::class.java,
        ).build()
        dao = database.chatDao()
        dao.upsertConversation(original)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun unchangedSnapshot_updatesTitle() = runBlocking {
        assertEquals(1, applyAiTitle())
        assertEquals(original.copy(title = "AI 标题", updatedAtMillis = 30L), dao.getConversation(original.id))
    }

    @Test
    fun manualTitleChangedAtSameTimestamp_isPreserved() = runBlocking {
        dao.updateConversationTitle(original.id, "手动标题", original.updatedAtMillis)

        assertEquals(0, applyAiTitle())
        assertEquals(original.copy(title = "手动标题"), dao.getConversation(original.id))
    }

    @Test
    fun conversationTouchedWithSameTitle_isPreserved() = runBlocking {
        dao.touchConversation(original.id, 20L)

        assertEquals(0, applyAiTitle())
        assertEquals(original.copy(updatedAtMillis = 20L), dao.getConversation(original.id))
    }

    @Test
    fun deletedConversation_isNotRecreated() = runBlocking {
        dao.deleteConversation(original.id)

        assertEquals(0, applyAiTitle())
        assertNull(dao.getConversation(original.id))
    }

    private suspend fun applyAiTitle(): Int = dao.updateConversationTitleIfUnchanged(
        conversationId = original.id,
        expectedTitle = original.title,
        expectedUpdatedAtMillis = original.updatedAtMillis,
        title = "AI 标题",
        updatedAtMillis = 30L,
    )
}
