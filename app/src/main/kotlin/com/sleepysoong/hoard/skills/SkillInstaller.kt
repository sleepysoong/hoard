package com.sleepysoong.hoard.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Downloads data only: installation never runs hooks, scripts, dependency installers or shells. */
class SkillInstaller(private val store: SkillStore) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()

    suspend fun installGithub(source: String): List<InstalledSkill> = githubInstall(source, null)

    suspend fun update(skill: InstalledSkill): List<InstalledSkill> {
        val source = skill.source ?: error("This workspace skill has no remote source; edit its files instead")
        require(source.startsWith("https://github.com/")) { "Only GitHub installations support Update; import a new archive explicitly" }
        require(skill.sourcePath != null) { "Skill has no recorded source path" }
        return githubInstall(source, skill)
    }

    suspend fun importArchive(input: InputStream, sourceName: String): List<InstalledSkill> = withContext(Dispatchers.IO) {
        require(sourceName.isNotBlank() && sourceName.length <= 512 && sourceName.none { it.code < 32 }) { "Invalid archive name" }
        val stage = newStage()
        try {
            val zip = File(stage, "download.zip")
            input.use { stream -> zip.outputStream().use { copyBounded(stream, it, MAX_COMPRESSED, coroutineContext) } }
            val basename = sourceName.substringAfterLast('/').substringAfterLast('\\')
            val stem = basename.substringBeforeLast('.', basename)
                .replace(Regex("[^A-Za-z0-9._-]"), "-").trim('.', '-').take(80).ifEmpty { "imported-skill" }
            val unpacked = File(stage, stem)
            unpack(zip, unpacked, coroutineContext)
            val root = unwrapArchive(unpacked)
            val candidates = discover(root, root)
            val source = "archive:$sourceName"
            val plans = stagePlans(candidates, root, source, fileDigest(zip), stage, coroutineContext)
            coroutineContext.ensureActive()
            store.commitInstall(plans)
        } finally {
            cleanupStage(stage)
        }
    }

    private suspend fun githubInstall(source: String, update: InstalledSkill?): List<InstalledSkill> = withContext(Dispatchers.IO) {
        val requested = parseGithub(source)
        val resolved = resolveGithub(requested)
        val stage = newStage()
        try {
            val zip = File(stage, "download.zip")
            val download = "https://codeload.github.com".toHttpUrl().newBuilder()
                .addPathSegment(resolved.owner).addPathSegment(resolved.repo)
                .addPathSegment("zip").addPathSegment(resolved.sha).build()
            val context = coroutineContext
            githubRequest(download) { response ->
                require(response.isSuccessful) { githubError(response) }
                val body = response.body
                require(body.contentLength() <= MAX_COMPRESSED) { "GitHub archive exceeds 64 MiB compressed" }
                zip.outputStream().use { output -> copyBounded(body.byteStream(), output, MAX_COMPRESSED, context) }
            }
            val unpacked = File(stage, "repository")
            unpack(zip, unpacked, coroutineContext)
            val archiveRoot = unwrapArchive(unpacked)
            val checkout = File(stage, "checkout").apply { require(mkdir()) { "Cannot stage GitHub checkout" } }
            val repoRoot = File(checkout, resolved.repo)
            // Codeload's generated repo-SHA wrapper is not the authored skill directory name.
            require(archiveRoot.renameTo(repoRoot)) { "Cannot normalize GitHub archive wrapper" }
            val selected = if (resolved.subpath.isEmpty()) repoRoot else safeSkillChild(repoRoot, resolved.subpath)
            require(selected.isDirectory) { "GitHub path '${resolved.subpath}' is not a directory at ${resolved.sha.take(12)}" }
            var candidates = discover(selected, repoRoot)
            if (update != null) {
                candidates = candidates.filter { it.file.relativeTo(repoRoot).invariantSeparatorsPath == update.sourcePath }
                require(candidates.size == 1) { "The selected skill no longer exists at '${update.sourcePath}'. No other skills were changed." }
            }
            val plans = stagePlans(candidates, repoRoot, resolved.source, resolved.sha, stage, coroutineContext)
            coroutineContext.ensureActive()
            store.commitInstall(plans, update?.id)
        } finally {
            cleanupStage(stage)
        }
    }

    private fun newStage(): File = File(store.stateRoot, "install-${UUID.randomUUID()}").apply {
        require(mkdirs()) { "Cannot create skill installation staging directory" }
    }

    private fun cleanupStage(stage: File) {
        // If a filesystem failure prevented rollback, retain the old bundle at the error's backup path.
        if (stage.listFiles().orEmpty().none { it.name.startsWith("backup-") }) stage.deleteRecursively()
    }

    private fun stagePlans(
        candidates: List<SkillCandidate>, root: File, source: String, revision: String,
        stage: File, context: CoroutineContext,
    ): List<SkillInstallPlan> {
        require(candidates.isNotEmpty()) { "No SKILL.md files or Claude command files were found in this source" }
        require(candidates.size <= MAX_SKILLS) { "Source contains more than $MAX_SKILLS skills; import a narrower GitHub tree URL" }
        require(candidates.map { it.command.lowercase() }.distinct().size == candidates.size) {
            "Source defines the same command more than once; select a specific skill directory"
        }
        var stagedBytes = 0L
        val plans = candidates.map { candidate ->
            context.ensureActive()
            val sourcePath = candidate.file.relativeTo(root).invariantSeparatorsPath
            val id = "skill-${skillDigest("$source\n$sourcePath").take(24)}"
            val target = File(stage, id)
            require(target.mkdir()) { "Cannot stage /${candidate.command}" }
            val copyRoot = candidate.pluginRoot ?: candidate.file.parentFile!!
            // Retain the WHOLE plugin, including hooks, agents, scripts and resources outside skills/.
            copyTree(copyRoot, target, context) { bytes ->
                stagedBytes += bytes
                require(stagedBytes <= MAX_TOTAL) { "Installed bundles exceed 128 MiB; install fewer skills at once" }
            }
            var skillPath = candidate.file.relativeTo(copyRoot).invariantSeparatorsPath
            if (candidate.pluginRoot == null && candidate.file.name != "SKILL.md") {
                val legacy = File(target, skillPath)
                val destination = File(target, "SKILL.md")
                require(!destination.exists()) { "Command import conflicts with an existing SKILL.md" }
                require(legacy.renameTo(destination)) { "Cannot convert legacy command to SKILL.md" }
                skillPath = "SKILL.md"
            }
            val record = SkillRecord(id, candidate.command, skillPath, source, sourcePath, revision, candidate.pluginRoot != null)
            SkillInstallPlan(target, record)
        }
        return plans
    }

    private data class GithubRequest(val owner: String, val repo: String, val tree: List<String>)
    private data class GithubResolved(
        val owner: String, val repo: String, val sha: String, val subpath: String, val source: String,
    )

    private fun parseGithub(source: String): GithubRequest {
        val value = source.trim()
        val url = when {
            value.startsWith("https://") -> value.toHttpUrl()
            value.startsWith("github.com/") -> "https://$value".toHttpUrl()
            value.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) -> "https://github.com/$value".toHttpUrl()
            else -> error("Use owner/repo or an https://github.com/owner/repo/tree/ref/path URL")
        }
        require(url.scheme == "https" && url.host == "github.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) {
            "Skill sources must be HTTPS github.com repository URLs"
        }
        val parts = url.pathSegments.filter(String::isNotEmpty)
        require(parts.size >= 2) { "GitHub source must include an owner and repository" }
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        require(owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,99}")) && repo.matches(Regex("[A-Za-z0-9_.-]{1,100}")) &&
            repo != "." && repo != "..") { "Invalid GitHub repository name" }
        require(parts.size == 2 || (parts.size >= 4 && parts[2] == "tree")) { "Use a repository or /tree/ref/path URL, not a blob/download URL" }
        val tree = if (parts.size > 2) parts.drop(3).flatMap { it.split('/') } else emptyList()
        require(tree.size <= 24 && tree.none { it.isEmpty() || it == "." || it == ".." || ':' in it || '\\' in it || it.any { char -> char.code < 32 } }) {
            "Unsafe or overly deep GitHub tree path"
        }
        return GithubRequest(owner, repo, tree)
    }

    /** Resolve real refs first, longest prefix wins; a slash in a branch is not a subdirectory. */
    private suspend fun resolveGithub(requested: GithubRequest): GithubResolved {
        val owner = requested.owner
        val repo = requested.repo
        // apiText returns null only for an explicit 404 (used below); every other call must have a body.
        suspend fun api(url: HttpUrl) = apiText(url) ?: error("GitHub returned an empty response for $url")
        var ref: String
        var sha: String
        var subpath = ""
        if (requested.tree.isEmpty()) {
            val metadata = JSONObject(api(apiUrl(owner, repo)))
            ref = metadata.getString("default_branch")
            val result = JSONObject(api(apiUrl(owner, repo, "git", "ref", "heads", *ref.split('/').toTypedArray())))
            sha = result.getJSONObject("object").getString("sha")
        } else {
            val tail = requested.tree.joinToString("/")
            val matches = mutableListOf<Triple<String, String, String>>()
            // matching-refs avoids enumerating every branch/tag and handles slash-containing refs.
            for (kind in listOf("heads", "tags")) {
                val response = apiText(apiUrl(owner, repo, "git", "matching-refs", kind, requested.tree.first()), allow404 = true)
                if (response == null) continue
                val refs = JSONArray(response)
                require(refs.length() <= 10_000) { "Repository has too many matching refs" }
                for (i in 0 until refs.length()) {
                    val item = refs.getJSONObject(i)
                    val name = item.getString("ref").removePrefix("refs/$kind/")
                    if (tail == name || tail.startsWith("$name/")) {
                        val obj = item.getJSONObject("object")
                        matches += Triple(name, obj.getString("sha"), obj.getString("type"))
                    }
                }
            }
            val longest = matches.sortedWith(compareByDescending<Triple<String, String, String>> { it.first.length }
                .thenBy { if (it.third == "commit") 0 else 1 }).firstOrNull()
            if (longest != null) {
                ref = longest.first
                sha = longest.second
                var type = longest.third
                var dereferences = 0
                while (type == "tag") {
                    require(++dereferences <= 8) { "GitHub annotated tag chain is too deep" }
                    val tag = JSONObject(api(apiUrl(owner, repo, "git", "tags", sha))).getJSONObject("object")
                    sha = tag.getString("sha")
                    type = tag.getString("type")
                }
                require(type == "commit") { "GitHub ref does not point to a commit" }
                subpath = tail.removePrefix(ref).removePrefix("/")
            } else {
                ref = requested.tree.first()
                require(ref.matches(Regex("[a-fA-F0-9]{7,40}"))) { "No GitHub branch or tag matches this tree URL" }
                val commit = JSONObject(api(apiUrl(owner, repo, "commits", ref)))
                sha = commit.getString("sha")
                subpath = requested.tree.drop(1).joinToString("/")
            }
        }
        require(sha.matches(Regex("[a-fA-F0-9]{40}"))) { "GitHub returned an invalid commit ID" }
        val canonical = "https://github.com".toHttpUrl().newBuilder().addPathSegment(owner).addPathSegment(repo).apply {
            // Keep a default-branch source ref-free so Update follows the repository's current default.
            if (requested.tree.isNotEmpty()) {
                addPathSegment("tree")
                ref.split('/').forEach { addPathSegment(it) }
                if (subpath.isNotEmpty()) subpath.split('/').forEach { addPathSegment(it) }
            }
        }.build().toString()
        return GithubResolved(owner, repo, sha.lowercase(), subpath, canonical)
    }

    private fun apiUrl(owner: String, repo: String, vararg path: String): HttpUrl = "https://api.github.com".toHttpUrl()
        .newBuilder().addPathSegment("repos").addPathSegment(owner).addPathSegment(repo).apply {
            path.forEach { addPathSegment(it) }
        }.build()

    private suspend fun apiText(url: HttpUrl, allow404: Boolean = false): String? {
        val context = coroutineContext
        return githubRequest(url) { response ->
            if (allow404 && response.code == 404) null else {
                require(response.isSuccessful) { githubError(response) }
                val output = java.io.ByteArrayOutputStream()
                copyBounded(response.body.byteStream(), output, MAX_API_BYTES, context)
                output.toString("UTF-8")
            }
        }
    }

    /** Cancellation cancels the currently active socket, including a redirected download. */
    private suspend fun <T> githubRequest(url: HttpUrl, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<Call?>()
        continuation.invokeOnCancellation { active.get()?.cancel() }
        fun send(target: HttpUrl, redirects: Int) {
            try {
                require(target.scheme == "https" && target.host in GITHUB_HOSTS && target.port == 443 &&
                    target.username.isEmpty() && target.password.isEmpty()) { "Refused non-GitHub download redirect: $target" }
                require(redirects <= 5) { "Too many GitHub download redirects" }
                if (!continuation.isActive) return
                val request = Request.Builder().url(target).header("User-Agent", "Hoard-Agent-Skills")
                    .header("Accept", if (target.host == "api.github.com") "application/vnd.github+json" else "application/zip")
                    .header("Accept-Encoding", "identity").build()
                val call = client.newCall(request)
                active.set(call)
                if (!continuation.isActive) { call.cancel(); return }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!continuation.isActive) return
                                if (response.code in setOf(301, 302, 303, 307, 308)) {
                                    val next = response.header("Location")?.let(target::resolve)
                                        ?: error("GitHub redirect has no valid Location")
                                    send(next, redirects + 1)
                                } else {
                                    val value = consume(response)
                                    continuation.resume(value)
                                }
                            }
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                })
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
        send(url, 0)
    }

    private fun githubError(response: Response): String = when (response.code) {
        403, 429 -> "GitHub refused the request (HTTP ${response.code}); its unauthenticated API rate limit may be exhausted. Retry later or import a ZIP/.skill archive."
        404 -> "GitHub source/ref was not found (HTTP 404). Private repositories are not supported without an archive export."
        else -> "GitHub request failed (HTTP ${response.code})"
    }

    /** Selection happens before discovery; canonical roots win over generated agent copies. */
    private fun discover(selected: File, repoRoot: File): List<SkillCandidate> {
        val direct = File(selected, "SKILL.md")
        if (direct.isFile) return listOf(candidateFor(direct, repoRoot))
        val all = walkSkillFiles(selected)
        val skillFiles = linkedSetOf<File>()
        val commandCandidates = mutableListOf<SkillCandidate>()
        fun skillsAt(directory: File) {
            if (!directory.exists()) return
            require(isWithin(directory, repoRoot) && !Files.isSymbolicLink(directory.toPath())) { "Plugin skill path escapes repository" }
            if (directory.isFile) {
                require(directory.name == "SKILL.md") { "Plugin skill file must be named SKILL.md" }
                skillFiles += directory
            } else walkSkillFiles(directory).filterTo(skillFiles) { it.name == "SKILL.md" }
        }
        fun commandsAt(path: File, plugin: File?) {
            if (!path.exists()) return
            require(isWithin(path, repoRoot) && !Files.isSymbolicLink(path.toPath())) { "Plugin command path escapes repository" }
            if (path.isFile) {
                require(path.extension.equals("md", true)) { "Plugin command file must be Markdown" }
                commandCandidates += legacyCandidate(path, path.parentFile!!, plugin)
            } else walkSkillFiles(path).filter { it.extension.equals("md", true) && it.name != "SKILL.md" }.forEach {
                commandCandidates += legacyCandidate(it, path, plugin)
            }
        }
        skillsAt(File(selected, ".claude/skills"))
        skillsAt(File(selected, "skills"))
        // Include directly wrapped skill folders, without recursively discovering .openclaw copies.
        selected.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') }?.forEach {
            if (File(it, "SKILL.md").isFile) skillFiles += File(it, "SKILL.md")
        }
        commandsAt(File(selected, ".claude/commands"), null)
        for (manifest in all.filter { it.name == "plugin.json" && it.parentFile?.name == ".claude-plugin" }) {
            val plugin = manifest.parentFile!!.parentFile!!
            require(manifest.length() <= 128 * 1024) { "Plugin manifest exceeds 128 KiB" }
            val json = JSONObject(readSkillText(safeSkillChild(plugin, ".claude-plugin/plugin.json"), 128 * 1024))
            val name = json.getString("name")
            validateSkillCommand(name)
            require(':' !in name) { "Plugin name cannot contain namespace colons" }
            if (File(plugin, "SKILL.md").isFile) skillFiles += File(plugin, "SKILL.md")
            skillsAt(File(plugin, "skills"))
            commandsAt(File(plugin, "commands"), plugin)
            for (path in manifestPaths(json, "skills")) skillsAt(safeSkillChild(plugin, path.removePrefix("./")))
            for (path in manifestPaths(json, "commands")) commandsAt(safeSkillChild(plugin, path.removePrefix("./")), plugin)
        }
        // A tree URL can point to a category such as skills/binance-web3/.
        if (skillFiles.isEmpty() && commandCandidates.isEmpty()) all.filterTo(skillFiles) { it.name == "SKILL.md" }
        val candidates = skillFiles.map { candidateFor(it, repoRoot) }
        val existingCommands = candidates.map { it.command.lowercase() }.toSet()
        return candidates + commandCandidates.distinctBy { it.file.canonicalPath }
            .filter { it.command.lowercase() !in existingCommands }
    }

    private fun manifestPaths(json: JSONObject, key: String): List<String> = when (val value = json.opt(key)) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).map { value.getString(it) }
        // Plugin manifests can also use inline command definitions; they are not Markdown skills.
        is JSONObject -> if (key == "commands") emptyList() else error("Plugin '$key' must contain relative paths")
        // Absent or explicitly null: the manifest simply declares no extra path.
        else -> if (value == null || value === JSONObject.NULL) emptyList()
        else error("Plugin '$key' must be a relative path or an array of paths")
    }

    private fun unwrapArchive(directory: File): File {
        var root = directory
        repeat(4) {
            if (File(root, "SKILL.md").isFile || File(root, ".claude-plugin/plugin.json").isFile ||
                File(root, "skills").isDirectory || File(root, ".claude").isDirectory) return root
            // Ignore generic archive metadata and hidden sidecars when peeling wrappers.
            val children = root.listFiles().orEmpty().filter { !it.name.startsWith("__") && !it.name.startsWith('.') }
            if (children.size != 1 || !children[0].isDirectory) return root
            root = children[0]
        }
        return root
    }

    private data class ZipMode(val directory: Boolean, val executable: Boolean)

    /** Java's ZipEntry drops Unix mode. Inspect the central directory before extracting anything. */
    private fun zipModes(zip: File): Map<String, ZipMode> = RandomAccessFile(zip, "r").use { input ->
        require(input.length() in 22..MAX_COMPRESSED) { "Not a ZIP archive, or archive exceeds 64 MiB" }
        val searchStart = (input.length() - 65_557).coerceAtLeast(0)
        val tail = ByteArray((input.length() - searchStart).toInt())
        input.seek(searchStart)
        input.readFully(tail)
        fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (index * 8)) }
        val end = (tail.size - 22 downTo 0).firstOrNull {
            u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size
        } ?: error("ZIP end-of-directory record is missing")
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "Multi-volume ZIP archives are not supported" }
        val count = u16(tail, end + 10)
        require(count in 1..MAX_ENTRIES && u16(tail, end + 8) == count) { "ZIP contains too many entries, is empty, or requires ZIP64" }
        val size = u32(tail, end + 12)
        val offset = u32(tail, end + 16)
        require(offset != 0xffffffffL && size != 0xffffffffL && offset + size == searchStart + end) { "Invalid or unsupported ZIP64 central directory" }
        input.seek(offset)
        val modes = linkedMapOf<String, ZipMode>()
        var total = 0L
        var compressed = 0L
        repeat(count) {
            val header = ByteArray(46)
            input.readFully(header)
            require(u32(header, 0) == 0x02014b50L) { "Invalid ZIP central directory entry" }
            val flags = u16(header, 8)
            require(flags and 1 == 0 && flags and 64 == 0 && u16(header, 10) in listOf(0, 8)) { "Encrypted or unsupported ZIP compression" }
            val packed = u32(header, 20)
            val unpacked = u32(header, 24)
            require(packed <= MAX_COMPRESSED && unpacked <= MAX_ENTRY && u32(header, 42) < offset && u16(header, 34) == 0) {
                "ZIP entry exceeds limits or requires ZIP64"
            }
            total += unpacked
            compressed += packed
            require(total <= MAX_TOTAL && compressed <= MAX_COMPRESSED && (unpacked < 1024 * 1024 || unpacked <= packed.coerceAtLeast(1) * MAX_RATIO)) {
                "ZIP exceeds expansion/size limits (possible decompression bomb)"
            }
            val nameBytes = ByteArray(u16(header, 28))
            require(nameBytes.size in 1..1024) { "ZIP entry name is empty or too long" }
            input.readFully(nameBytes)
            // GitHub and .skill archives use UTF-8. Reject ambiguous legacy encodings.
            val name = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(nameBytes)).toString()
            validateZipName(name)
            val creator = u16(header, 4) ushr 8
            val mode = if (creator == 3 || creator == 19) (u32(header, 38) ushr 16).toInt() else 0
            val type = mode and 0xf000
            require(type == 0 || type == 0x8000 || type == 0x4000) { "ZIP symlinks and special files are forbidden: $name" }
            val directory = name.endsWith('/')
            require(type != 0x4000 || directory) { "ZIP directory mode/name mismatch: $name" }
            require(type != 0x8000 || !directory) { "ZIP file mode/name mismatch: $name" }
            require(!directory || unpacked == 0L) { "ZIP directory contains file data: $name" }
            require(modes.put(name, ZipMode(directory, mode and 73 != 0)) == null) { "Duplicate ZIP entry: $name" }
            input.seek(input.filePointer + u16(header, 30) + u16(header, 32))
            require(input.filePointer <= offset + size) { "ZIP central directory escapes its bounds" }
        }
        require(input.filePointer == offset + size) { "Unexpected data in ZIP central directory" }
        modes
    }

    private fun validateZipName(name: String) {
        val path = name.removeSuffix("/")
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
            path.none { it.code < 32 || it.code == 127 } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe ZIP entry path: $name"
        }
        require(path.split('/').size <= 32) { "ZIP entry path is too deep: $name" }
    }

    private fun unpack(zip: File, destination: File, context: CoroutineContext) {
        context.ensureActive()
        val modes = zipModes(zip)
        require(destination.mkdir()) { "Cannot create ZIP extraction directory" }
        var total = 0L
        ZipFile(zip, Charsets.UTF_8).use { archive ->
            require(archive.size() == modes.size) { "ZIP directory count mismatch" }
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                context.ensureActive()
                val entry = entries.nextElement()
                val mode = modes[entry.name] ?: error("ZIP entry was not validated: ${entry.name}")
                val target = safeSkillChild(destination, entry.name.removeSuffix("/"))
                if (mode.directory) {
                    require(target.mkdirs() || target.isDirectory) { "ZIP file/directory collision: ${entry.name}" }
                    continue
                }
                require(!target.exists()) { "ZIP file/directory collision: ${entry.name}" }
                require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory) { "Cannot create ZIP parent directory" }
                val crc = CRC32()
                var size = 0L
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            context.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            total += read
                            require(size <= MAX_ENTRY && total <= MAX_TOTAL) { "ZIP expanded past its size limit" }
                            crc.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                require(size == entry.size && crc.value == entry.crc) { "Corrupt ZIP entry: ${entry.name}" }
                if (mode.executable) require(target.setExecutable(true, true)) { "Cannot retain executable permission: ${entry.name}" }
            }
        }
    }

    private fun copyTree(root: File, target: File, context: CoroutineContext, account: (Long) -> Unit) {
        var entries = 0
        fun visit(from: File, to: File, depth: Int) {
            context.ensureActive()
            require(++entries <= MAX_ENTRIES && depth <= 32 && !Files.isSymbolicLink(from.toPath()) && isWithin(from, root)) {
                "Skill bundle is too complex or contains symlinks"
            }
            if (from.isDirectory) {
                require(to.mkdirs() || to.isDirectory) { "Cannot stage skill directory" }
                boundedSkillChildren(from, MAX_ENTRIES - entries).forEach { visit(it, File(to, it.name), depth + 1) }
            } else {
                require(from.isFile && from.length() <= MAX_ENTRY) { "Unsupported or oversized skill resource: $from" }
                account(from.length())
                from.inputStream().use { input -> to.outputStream().use { output -> copyBounded(input, output, MAX_ENTRY, context) } }
                if (from.canExecute()) require(to.setExecutable(true, true)) { "Cannot preserve script permissions" }
            }
        }
        visit(root, target, 0)
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, max: Long, context: CoroutineContext): Long {
        val buffer = ByteArray(32 * 1024)
        var bytes = 0L
        while (true) {
            context.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            bytes += read
            require(bytes <= max) { "Skill download/resource exceeds the ${max / (1024 * 1024)} MiB limit" }
            output.write(buffer, 0, read)
        }
        return bytes
    }

    companion object {
        private const val MAX_COMPRESSED = 64L * 1024 * 1024
        private const val MAX_TOTAL = 128L * 1024 * 1024
        private const val MAX_ENTRY = 16L * 1024 * 1024
        private const val MAX_API_BYTES = 4L * 1024 * 1024
        private const val MAX_ENTRIES = 10_000
        private const val MAX_SKILLS = 256
        private const val MAX_RATIO = 200L
        private val GITHUB_HOSTS = setOf("github.com", "api.github.com", "codeload.github.com")
    }
}
