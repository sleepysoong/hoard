package com.sleepysoong.hoard

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.RouteAttempt
import com.sleepysoong.hoard.data.RoutingInfo
import com.sleepysoong.hoard.data.UiAttachment
import com.sleepysoong.hoard.testing.ChatHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Conversations survive process death. Failure modes: nothing written, changes in the
 * debounce window lost, routing trace / attachments / edits / branches / deletions not
 * round-tripped, a reply that was streaming restored as an eternal spinner, a corrupt
 * file crashing every launch (or being silently overwritten), a crash mid-write
 * destroying the previous version.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PersistenceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file by lazy { File(tmp.root, "hoard-store.json") }

    /** Process death + relaunch: memory gone, the same file is loaded again. */
    private fun restart(): HoardRepository {
        HoardRepository.resetForTests()
        HoardRepository.initForTests(file)
        return HoardRepository.get()
    }

    /** A store written by an older version: its canned welcome session/bubble and mock tool data are dropped. */
    @Test fun legacySampleDataIsDroppedOnLoad() {
        file.writeText("""
            {"version":1,
             "sessions":[
               {"id":"session-welcome","name":"Hoard에 오신 것을 환영합니다","systemPrompt":"s","modelId":"hoard-1-pro","contextLimit":32000,"createdAt":1,"updatedAt":1},
               {"id":"session-mine","name":"내 대화","systemPrompt":"s","modelId":"coding","contextLimit":32000,"createdAt":2,"updatedAt":2}
             ],
             "messages":{
               "session-welcome":[{"id":"msg-welcome-1","role":"Assistant","text":"안녕하세요, Hoard입니다 (목업)"}],
               "session-mine":[{"id":"msg-welcome-1","role":"Assistant","text":"안녕하세요"},{"id":"m1","role":"User","text":"진짜 질문"}]
             },
             "mcpServers":[{"id":"x","name":"GitHub","url":"https://mcp.mock/github","enabled":true,"toolCount":1,"status":"연결됨"}],
             "pluginEnabled":{"web-search":false}}
        """.trimIndent())
        val r = restart()
        assertEquals("welcome-only session dropped", listOf("session-mine"), r.sessions.value.map { it.id })
        assertEquals("welcome bubble dropped, real messages kept", listOf("m1"), r.messagesOf("session-mine").map { it.id })
    }

    @Test fun freshInstallHasNoSampleSessions() {
        val r = restart()
        assertTrue(r.sessions.value.isEmpty())
        assertTrue(r.modelCatalog().isEmpty())
    }

    @Test fun conversationSurvivesRestart() {
        val h = ChatHarness(storeFile = file)
        val sid = h.vm.newSession("여행 계획")
        h.idle()
        h.vm.setSystemPrompt("짧게 답해")
        h.vm.setContextLimit(8_000)
        val photo = UiAttachment("att-1", "map.png", "image/png", 1234, Uri.parse("content://media/42"))
        h.vm.send("제주 3일 코스", listOf(photo), "hoard-1-pro")
        h.awaitReplies()
        h.vm.send("둘째 날만 자세히", emptyList(), "hoard-1-pro")
        h.awaitReplies()
        val routing = RoutingInfo("coding", "model-group", listOf("zen/a", "or/c"), "or/c", "or",
            listOf(RouteAttempt(1, "zen/a", "zen", "a", "failed", "rate_limit", 429, "slow down", true, 12),
                RouteAttempt(2, "or/c", "or", "c", "succeeded", durationMs = 300)))
        val lastReply = h.repo.messagesOf(sid).last()
        h.repo.updateMessage(sid, lastReply.id) { it.copy(routing = routing) }
        val branch = h.vm.branchFrom(h.repo.messagesOf(sid)[1].id, "브랜치")!!
        val before = h.repo.messagesOf(sid)
        h.repo.flush()

        val r = restart()
        assertEquals(h.repo.sessions.value.size, r.sessions.value.size)
        val s = r.sessionOf(sid)!!
        assertEquals("여행 계획", s.name)
        assertEquals("짧게 답해", s.systemPrompt)
        assertEquals(8_000, s.contextLimit)
        val msgs = r.messagesOf(sid)
        assertEquals(before.map { it.id }, msgs.map { it.id })
        assertEquals(before.map { it.text }, msgs.map { it.text })
        assertEquals(MessageRole.User, msgs[0].role)
        assertEquals("content://media/42", msgs[0].attachments.single().uri.toString())
        assertEquals(routing, msgs.last().routing)
        assertTrue(msgs.last().thinking.isNotEmpty())
        assertEquals(before.last().completionTokens, msgs.last().completionTokens)
        assertNotNull(r.sessionOf(branch))
        assertEquals("sess branch keeps its origin", sid, r.sessionOf(branch)!!.branchedFrom)
    }

    @Test fun changesAreSavedWithoutAnExplicitFlush() {
        val h = ChatHarness(storeFile = file)
        h.vm.renameSession("자동 저장")
        val sid = h.vm.uiState.value.session!!.id
        val deadline = System.currentTimeMillis() + 5_000
        while (!(file.exists() && file.readText().contains("자동 저장"))) {
            h.idle(); Thread.sleep(20)
            check(System.currentTimeMillis() < deadline) { "debounced save never happened" }
        }
        assertEquals("자동 저장", restart().sessionOf(sid)!!.name)
    }

    @Test fun deletionsPersist() {
        val h = ChatHarness(storeFile = file)
        val keep = h.vm.uiState.value.session!!.id
        val gone = h.vm.newSession("지울 세션")
        h.idle()
        h.vm.send("남길 질문", emptyList(), "hoard-1-pro")
        h.awaitReplies()
        val reply = h.repo.messagesOf(gone).last()
        h.vm.deleteMessage(reply.id)
        h.vm.selectSession(keep); h.idle()
        h.vm.deleteSession(gone)
        h.idle()
        h.repo.flush()
        val r = restart()
        assertEquals(null, r.sessionOf(gone))
        assertTrue(r.messages.value[gone] == null)
    }

    /** A failed save is surfaced (and the old file is kept), never silently swallowed. */
    @Test fun saveFailureIsVisibleAndKeepsThePreviousStore() {
        // The store target is a directory: the atomic replace must fail loudly, not delete data.
        val blockedFile = File(tmp.root, "as-directory.json").apply { mkdirs() }
        HoardRepository.resetForTests()
        HoardRepository.initForTests(blockedFile)
        val broken = HoardRepository.get()
        broken.createSession("저장 안 되는 세션")
        broken.flush()
        assertTrue("save failure is observable", broken.saveError.value != null)
        assertTrue("target directory untouched", blockedFile.isDirectory)
        assertTrue(broken.sessions.value.isNotEmpty()) // in-memory state keeps working
        HoardRepository.resetForTests(); HoardRepository.initForTests(null)
    }

    @Test fun replyStreamingAtDeathIsNotAnEternalSpinner() {
        val h = ChatHarness(pace = 50f, storeFile = file)
        val sid = h.vm.uiState.value.session!!.id
        h.vm.send("죽기 직전 질문", emptyList(), "hoard-1-pro")
        val deadline = System.currentTimeMillis() + 5_000
        while (h.repo.messagesOf(sid).none { it.isStreaming }) { h.idle(); Thread.sleep(10); check(System.currentTimeMillis() < deadline) }
        h.repo.flush()
        h.workManager.cancelAllWork() // the process (and its worker) die
        val r = restart()
        val m = r.messagesOf(sid).last()
        assertFalse("restored as finished, not spinning", m.isStreaming)
        assertTrue(m.errorText!!, m.errorText!!.contains("중단"))
        assertEquals("죽기 직전 질문", r.messagesOf(sid)[r.messagesOf(sid).size - 2].text)
    }

    @Test fun corruptFileIsKeptAsideAndAppStarts() {
        file.writeText("{ this is not json")
        val r = restart()
        assertTrue("starts empty (no sample data), no crash", r.sessions.value.isEmpty())
        assertFalse("corrupt file not overwritten in place", file.exists() && file.readText().startsWith("{ this is not json"))
        val kept = tmp.root.listFiles()!!.filter { it.name.startsWith("hoard-store.json.corrupt-") }
        assertEquals("original bytes preserved for recovery", "{ this is not json", kept.single().readText())
    }

    @Test fun interruptedWriteLeavesPreviousVersion() {
        val h = ChatHarness(storeFile = file)
        h.vm.renameSession("안전한 버전")
        h.repo.flush()
        val good = file.readText()
        // A crash mid-write only ever leaves a partial temp file.
        File(tmp.root, "hoard-store.json.tmp").writeText("{\"sessions\":[{\"id\":")
        val r = restart()
        assertEquals(good, file.readText())
        assertEquals("안전한 버전", r.sessions.value.first { it.id == h.vm.uiState.value.session!!.id }.name)
    }
}
