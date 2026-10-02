package com.sleepysoong.hoard

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.skills.SkillInstaller
import com.sleepysoong.hoard.skills.SkillStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Archive → discovery → persisted installation. Failure paths: metadata hiding a
 * wrapper, multiple authored roots, losing helpers, granting code trust, and unsafe paths.
 * Import must remain data-only. Evidence: build/test-artifacts/skill-archive/.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SkillArchiveFlowTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(vararg files: Pair<String, String>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray().inputStream()

    private fun skill(name: String) = "---\nname: $name\ndescription: Imported archive test\n---\nRun scripts/helper.sh only when asked.\n"
    private fun store() = SkillStore(temp.newFolder("workspace"), temp.newFolder("state"))
    private fun evidence(name: String, text: String) {
        val out = File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "skill-archive").apply { mkdirs() }
        File(out, "$name.txt").writeText(text)
    }

    @Test fun metadataDoesNotHideWrappedBundleAndHelpersRemainUntrustedData() = runBlocking {
        val store = store()
        val installed = SkillInstaller(store).importArchive(archive(
            ".archive-index" to "ignored sidecar",
            "__archive_metadata/index" to "ignored metadata",
            "wrapper/review/SKILL.md" to skill("review"),
            "wrapper/review/scripts/helper.sh" to "exit 99\n"
        ), "review.zip").single()
        assertEquals("review", installed.command)
        assertEquals("SKILL.md", installed.sourcePath)
        assertEquals("exit 99\n", File(installed.directory, "scripts/helper.sh").readText())
        assertFalse(installed.trustedCode)
        assertFalse(File(installed.directory, "__archive_metadata").exists())
        val restored = SkillStore(store.workspaceRoot, store.stateRoot).find("review")!!
        assertEquals(installed.id, restored.id)
        assertEquals(installed.document.body, restored.document.body)
        assertFalse(restored.trustedCode)
        evidence("wrapped-bundle", "command=${restored.command}\nsourcePath=${restored.sourcePath}\ntrusted=${restored.trustedCode}\n${restored.document.body}")
    }

    @Test fun multipleAuthoredRootsAreNotMistakenForOneWrapper() = runBlocking {
        val store = store()
        val installed = SkillInstaller(store).importArchive(archive(
            "first/SKILL.md" to skill("first"), "second/SKILL.md" to skill("second"),
            "__archive_metadata/index" to "ignored metadata"
        ), "skills.zip")
        assertEquals(setOf("first", "second"), installed.map { it.command }.toSet())
        assertEquals(setOf("first/SKILL.md", "second/SKILL.md"), installed.map { it.sourcePath }.toSet())
        assertTrue(installed.none { it.trustedCode })
        evidence("multiple-roots", installed.joinToString("\n") { "${it.command}: ${it.sourcePath}" })
    }

    @Test fun unsafeArchiveDoesNotInstallOrEscapeTheStagingDirectory() = runBlocking {
        val store = store()
        try {
            SkillInstaller(store).importArchive(archive("../escaped" to "unsafe", "SKILL.md" to skill("unsafe")), "unsafe.zip")
            fail("path traversal must fail before installation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("Unsafe ZIP entry"))
            evidence("rejected-path", e.message.orEmpty())
        }
        assertTrue(store.skills.value.isEmpty())
        assertFalse(File(store.stateRoot, "escaped").exists())
    }
}
