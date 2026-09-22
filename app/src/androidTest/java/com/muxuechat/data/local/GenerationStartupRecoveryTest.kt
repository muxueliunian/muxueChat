package com.muxuechat.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muxuechat.domain.model.ChatRole
import com.muxuechat.domain.model.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenerationStartupRecoveryTest {
    private lateinit var context: Context
    private var database: ChatDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun reopen_recoversOrdinaryGenerationAndDeletesIncompleteReplacementStaging() = runBlocking {
        val seedDatabase = Room.databaseBuilder(context, ChatDatabase::class.java, DATABASE_NAME).build()
        val dao = seedDatabase.chatDao()
        dao.upsertConversation(
            ConversationEntity(
                id = CONVERSATION_ID,
                title = "test",
                createdAtMillis = 1L,
                updatedAtMillis = 1L,
            ),
        )
        dao.upsertMessage(message("ordinary-streaming", MessageStatus.STREAMING))
        dao.upsertMessage(message("ordinary-pending", MessageStatus.PENDING))
        dao.upsertMessage(message("history-replacement:unfinished", MessageStatus.STREAMING))
        dao.upsertMessage(message("history-replacement:committed", MessageStatus.COMPLETED))
        seedDatabase.close()

        database = Room.databaseBuilder(context, ChatDatabase::class.java, DATABASE_NAME)
            .addCallback(ChatDatabase.STARTUP_GENERATION_RECOVERY_CALLBACK)
            .build()
        val recovered = database!!.chatDao().getMessages(CONVERSATION_ID).associateBy { it.id }

        assertEquals(MessageStatus.CANCELLED, recovered.getValue("ordinary-streaming").status)
        assertEquals(MessageStatus.CANCELLED, recovered.getValue("ordinary-pending").status)
        assertNull(recovered["history-replacement:unfinished"])
        assertEquals(MessageStatus.COMPLETED, recovered.getValue("history-replacement:committed").status)
    }

    private fun message(id: String, status: MessageStatus) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        role = ChatRole.ASSISTANT,
        content = "test",
        reasoningContent = null,
        status = status,
        errorMessage = null,
        createdAtMillis = 1L,
        updatedAtMillis = 1L,
    )

    private companion object {
        const val DATABASE_NAME = "generation-startup-recovery-test.db"
        const val CONVERSATION_ID = "conversation"
    }
}
