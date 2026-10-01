package com.sleepysoong.hoard.skills

import android.content.Context
import android.util.AtomicFile
import com.sleepysoong.hoard.tools.readCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

data class InstalledSkill(
    val id: String,
    val command: String,
    val directory: File,
    val document: SkillDocument,
    val enabled: Boolean = true,
    val trustedCode: Boolean = false,
    val source: String? = null,
    val sourcePath: String? = null,
    val revision: String? = null,
    val pluginRoot: File? = null,
) {
    // A legacy workspace command remains a .md file; imported commands become SKILL.md.
    val skillFile: File get() = legacyFile ?: File(directory, "SKILL.md")
    internal var legacyFile: File? = null
}

/** Registry and trust decisions live outside the workspace accessible to file tools. */
class SkillStore(
    val workspaceRoot: File,
    internal val stateRoot: File = File(workspaceRoot.parentFile, "agent-skills"),
) {
    internal val installedRoot = File(workspaceRoot, ".claude/skills")
    private val lock = Any()
    private val registryFile = AtomicFile(File(stateRoot, "registry.json"))
    private var records = linkedMapOf<String, SkillRecord>()
    private var flags = linkedMapOf<String, SkillFlags>()
    private val mutableSkills = MutableStateFlow<List<InstalledSkill>>(emptyList())
    val skills: StateFlow<List<InstalledSkill>> = mutableSkills.asStateFlow()
    private val mutableErrors = MutableStateFlow<List<String>>(emptyList())
    val refreshErrors: StateFlow<List<String>> = mutableErrors.asStateFlow()

    init {
        require(workspaceRoot.mkdirs() || workspaceRoot.isDirectory) { "Cannot create skills workspace" }
        require(stateRoot.mkdirs() || stateRoot.isDirectory) { "Cannot create skills registry" }
        readRegistry()
        refresh()
    }

    fun refresh(): List<InstalledSkill> = synchronized(lock) {
        val found = mutableListOf<InstalledSkill>()
        val errors = mutableListOf<String>()
        var flagsChanged = false
        val digestCache = mutableMapOf<String, String>()
        fun revoke(id: String) {
            val previous = flags[id] ?: return
            if (previous.trusted || previous.trustedHash != null) {
                flags[id] = previous.copy(trusted = false, trustedHash = null)
                flagsChanged = true
            }
        }
        fun settingsFor(id: String, codeRoot: File): SkillFlags {
            val settings = flags[id] ?: SkillFlags()
            if (!settings.trusted) return settings
            try {
                val digest = digestCache.getOrPut(codeRoot.canonicalPath) { skillTreeDigest(codeRoot) }
                if (settings.trustedHash == null || settings.trustedHash != digest) revoke(id)
            } catch (e: Exception) {
                revoke(id)
                errors += "Code trust revoked for $id: ${e.message}"
            }
            return flags[id] ?: settings
        }
        val managedRoots = records.keys.map { File(installedRoot, it).absoluteFile }.toSet()
        records.values.forEach { record ->
            try {
                val root = safeSkillChild(workspaceRoot, ".claude/skills/${record.id}")
                val file = safeSkillChild(root, record.skillPath)
                if (!file.isFile) { revoke(record.id); return@forEach }
                val settings = settingsFor(record.id, root)
                val document = readSkillDocument(file, record.command.substringAfterLast(':'))
                val command = if (file.name != "SKILL.md") record.command else if (record.plugin) {
                    val manifest = safeSkillChild(root, ".claude-plugin/plugin.json")
                    val namespace = if (manifest.isFile) JSONObject(readSkillText(manifest, 128 * 1024)).getString("name")
                        else record.command.substringBefore(':')
                    validateSkillCommand(namespace)
                    require(':' !in namespace && '/' !in namespace) { "Invalid plugin namespace" }
                    if (document.name.startsWith("$namespace:")) document.name else "$namespace:${document.name}"
                } else document.name
                validateSkillCommand(command)
                found += InstalledSkill(record.id, command, file.parentFile!!, document,
                    settings.enabled, settings.trusted, record.source, record.sourcePath, record.revision,
                    if (record.plugin) root else null).also {
                    if (file.name != "SKILL.md") it.legacyFile = file
                }
            } catch (e: Exception) {
                revoke(record.id)
                errors += "${record.command}: ${e.message}"
            }
        }
        val localCandidates = mutableListOf<SkillCandidate>()
        val commands = mutableListOf<SkillCandidate>()
        for ((project, qualifier) in workspaceSkillProjects(workspaceRoot, onError = { errors += it })) {
            val skillRoot = File(project, ".claude/skills")
            val localFiles = walkSkillFiles(skillRoot, excludedRoots = managedRoots, onError = { errors += it })
            for (file in localFiles.filter { it.name == "SKILL.md" }) {
                try {
                    val candidate = candidateFor(file, skillRoot)
                    localCandidates += if (qualifier.isEmpty()) candidate else candidate.copy(command = "$qualifier:${candidate.command}")
                } catch (e: Exception) {
                    revoke("local-${skillDigest(file.canonicalPath).take(24)}")
                    errors += "${file.relativeTo(workspaceRoot)}: ${e.message}"
                }
            }
            val commandRoot = File(project, ".claude/commands")
            for (file in walkSkillFiles(commandRoot, onError = { errors += it }).filter { it.extension.equals("md", true) }) {
                try {
                    val candidate = legacyCandidate(file, commandRoot, null)
                    commands += if (qualifier.isEmpty()) candidate else candidate.copy(command = "$qualifier:${candidate.command}")
                } catch (e: Exception) {
                    revoke("local-${skillDigest(file.canonicalPath).take(24)}")
                    errors += "${file.relativeTo(workspaceRoot)}: ${e.message}"
                }
            }
        }
        // Like Claude, a skill wins over the old command file with the same command.
        (localCandidates + commands).forEach { candidate ->
            if (found.any { it.command.equals(candidate.command, true) }) return@forEach
            val id = "local-${skillDigest(candidate.file.canonicalPath).take(24)}"
            try { validateSkillCommand(candidate.command) } catch (e: Exception) {
                revoke(id)
                errors += "${candidate.command}: ${e.message}"
                return@forEach
            }
            val settings = settingsFor(id, candidate.pluginRoot ?: candidate.file.parentFile!!)
            found += InstalledSkill(id, candidate.command, candidate.file.parentFile!!, candidate.document,
                settings.enabled, settings.trusted, pluginRoot = candidate.pluginRoot).also {
                if (candidate.file.name != "SKILL.md") it.legacyFile = candidate.file
            }
        }
        // Missing or shadowed skills cannot retain a stale code grant for later reappearance.
        val visibleIds = found.map { it.id }.toSet()
        flags.keys.toList().filter { it !in visibleIds }.forEach(::revoke)
        if (flagsChanged) {
            try { writeRegistry() } catch (e: Exception) {
                // Fail closed in memory; the next refresh/restart will compare the old hash again.
                errors += "Cannot persist revoked skill trust: ${e.message}"
            }
        }
        mutableErrors.value = errors
        mutableSkills.value = found.sortedBy { it.command.lowercase() }
        mutableSkills.value
    }

    fun find(name: String): InstalledSkill? {
        val query = name.removePrefix("/")
        return skills.value.firstOrNull { it.id == query || it.command.equals(query, true) }
            ?: skills.value.filter { it.document.name.equals(query, true) || it.directory.name.equals(query, true) }
                .singleOrNull()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = changeFlags(id) { it.copy(enabled = enabled) }
    suspend fun setTrustedCode(id: String, trusted: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val skill = refresh().firstOrNull { it.id == id } ?: error("Skill '$id' is no longer installed")
            val before = flags.toMap()
            val digest = if (trusted) skillTreeDigest(skill.pluginRoot ?: skill.directory) else null
            flags[id] = (flags[id] ?: SkillFlags()).copy(trusted = trusted, trustedHash = digest)
            try { writeRegistry() } catch (e: Exception) {
                flags = LinkedHashMap(before)
                throw e
            }
            refresh()
            Unit
        }
    }

    private suspend fun changeFlags(id: String, change: (SkillFlags) -> SkillFlags) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            require(refresh().any { it.id == id }) { "Skill '$id' is no longer installed" }
            val before = flags.toMap()
            flags[id] = change(flags[id] ?: SkillFlags())
            try { writeRegistry() } catch (e: Exception) {
                flags = LinkedHashMap(before)
                throw e
            }
            refresh()
            Unit
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val skill = refresh().firstOrNull { it.id == id } ?: return@synchronized
            val record = records[id]
            val target = if (record != null) File(installedRoot, id)
                else if (skill.skillFile.name == "SKILL.md") skill.directory else skill.skillFile
            require(isWithin(target, workspaceRoot) && !Files.isSymbolicLink(target.toPath())) { "Unsafe skill removal path" }
            val trash = File(stateRoot, "removed-${UUID.randomUUID()}")
            require(target.renameTo(trash)) { "Cannot move '${skill.command}' out of the workspace" }
            val oldFlags = flags.remove(id)
            records.remove(id)
            try { writeRegistry() } catch (e: Exception) {
                if (record != null) records[id] = record
                if (oldFlags != null) flags[id] = oldFlags
                if (!trash.renameTo(target)) e.addSuppressed(IllegalStateException("Restore failed; files remain at $trash"))
                throw e
            }
            trash.deleteRecursively()
            refresh()
            Unit
        }
    }

    /** All plans have already been copied/validated. No network or code execution under this lock. */
    internal fun commitInstall(plans: List<SkillInstallPlan>, updateId: String? = null): List<InstalledSkill> = synchronized(lock) {
        require(plans.isNotEmpty()) { "No skills were found in this source" }
        val current = refresh()
        require(plans.map { it.record.id }.distinct().size == plans.size) { "Duplicate skill IDs in source" }
        require(plans.map { it.record.command.lowercase() }.distinct().size == plans.size) { "Source contains conflicting command names" }
        if (updateId != null) {
            val previous = records[updateId] ?: error("Only installed GitHub skills can be updated")
            require(plans.size == 1 && plans.single().record.id == updateId && plans.single().record.source == previous.source) {
                "Update must replace only the selected skill from the same source"
            }
        }
        plans.forEach { plan ->
            validateSkillCommand(plan.record.command)
            val collision = current.any { it.command.equals(plan.record.command, true) && it.id != updateId } ||
                records.values.any { it.command.equals(plan.record.command, true) && it.id != updateId }
            require(!collision) { "/${plan.record.command} is already installed. Remove or rename it first; imports never overwrite skills." }
            val destination = File(installedRoot, plan.record.id)
            require(!destination.exists() || plan.record.id == updateId) {
                "Skill '${plan.record.command}' is already installed. Use Update explicitly to replace the same source."
            }
        }
        require(installedRoot.mkdirs() || installedRoot.isDirectory) { "Cannot create installed skills directory" }
        require(isWithin(installedRoot, workspaceRoot) && !Files.isSymbolicLink(installedRoot.toPath())) { "Unsafe installed skills directory" }
        val oldRecords = LinkedHashMap(records)
        val oldFlags = LinkedHashMap(flags)
        val moved = mutableListOf<Pair<File, File>>()
        val backups = mutableListOf<Pair<File, File>>()
        try {
            plans.forEach { plan ->
                val destination = File(installedRoot, plan.record.id)
                if (destination.exists()) {
                    val backup = File(plan.stagedDirectory.parentFile, "backup-${plan.record.id}")
                    require(destination.renameTo(backup)) { "Cannot back up ${plan.record.command}" }
                    backups += backup to destination
                }
                require(plan.stagedDirectory.renameTo(destination)) { "Cannot install ${plan.record.command}" }
                moved += destination to plan.stagedDirectory
                records[plan.record.id] = plan.record
                // New downloaded code must be reviewed again, even if the previous revision was trusted.
                flags[plan.record.id] = SkillFlags(flags[plan.record.id]?.enabled ?: true, false)
            }
            writeRegistry()
        } catch (e: Exception) {
            records = oldRecords
            flags = oldFlags
            moved.asReversed().forEach { (destination, staging) ->
                if (!destination.renameTo(staging)) e.addSuppressed(IllegalStateException("Rollback failed at $destination"))
            }
            backups.asReversed().forEach { (backup, destination) ->
                if (!backup.renameTo(destination)) e.addSuppressed(IllegalStateException("Backup remains at $backup"))
            }
            throw e
        }
        backups.forEach { it.first.deleteRecursively() }
        val ids = plans.map { it.record.id }.toSet()
        refresh().filter { it.id in ids }
    }

    private fun readRegistry() {
        if (!registryFile.baseFile.exists()) return
        // Do not turn a corrupt private registry into an empty one and overwrite trust/source records.
        val (bytes, truncated) = registryFile.openRead().use { it.readCapped(4 * 1024 * 1024) }
        require(!truncated) { "Skill registry exceeds 4 MiB" }
        val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
        val entries = json.optJSONArray("skills") ?: JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val record = SkillRecord.fromJson(entry)
            require(record.id.matches(Regex("skill-[a-f0-9]{24}"))) { "Invalid skill registry ID" }
            records[record.id] = record
        }
        val settings = json.optJSONObject("flags") ?: JSONObject()
        settings.keys().forEach { id ->
            val entry = settings.getJSONObject(id)
            flags[id] = SkillFlags(entry.optBoolean("enabled", true), entry.optBoolean("trusted", false), entry.nullableString("trustedHash"))
        }
    }

    private fun writeRegistry() {
        val json = JSONObject().put("version", 1)
            .put("skills", JSONArray(records.values.map { it.toJson() }))
            .put("flags", JSONObject().apply {
                flags.forEach { (id, flag) -> put(id, JSONObject().put("enabled", flag.enabled).put("trusted", flag.trusted)
                    .put("trustedHash", flag.trustedHash ?: JSONObject.NULL)) }
            })
        val stream = registryFile.startWrite()
        try {
            stream.write(json.toString(2).toByteArray(Charsets.UTF_8))
            registryFile.finishWrite(stream)
        } catch (e: Exception) {
            registryFile.failWrite(stream)
            throw e
        }
    }

    companion object {
        @Volatile private var singleton: SkillStore? = null
        fun get(context: Context): SkillStore = singleton ?: synchronized(this) {
            singleton ?: SkillStore(File(context.applicationContext.filesDir, "workspace"),
                File(context.applicationContext.filesDir, "agent-skills")).also { singleton = it }
        }
    }
}

private data class SkillFlags(val enabled: Boolean = true, val trusted: Boolean = false, val trustedHash: String? = null)

internal data class SkillRecord(
    val id: String,
    val command: String,
    val skillPath: String,
    val source: String?,
    val sourcePath: String?,
    val revision: String?,
    val plugin: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("command", command).put("skillPath", skillPath)
        .put("source", source ?: JSONObject.NULL).put("sourcePath", sourcePath ?: JSONObject.NULL)
        .put("revision", revision ?: JSONObject.NULL).put("plugin", plugin)

    companion object {
        fun fromJson(json: JSONObject) = SkillRecord(json.getString("id"), json.getString("command"),
            json.getString("skillPath"), json.nullableString("source"), json.nullableString("sourcePath"),
            json.nullableString("revision"), json.optBoolean("plugin"))
    }
}

internal data class SkillInstallPlan(val stagedDirectory: File, val record: SkillRecord)
internal data class SkillCandidate(val file: File, val document: SkillDocument, val command: String, val pluginRoot: File?)

internal fun JSONObject.nullableString(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)
internal fun skillDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun fileDigest(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Hash all files, not just SKILL.md: hooks and dynamically executed helpers share one trust boundary. */
internal fun skillTreeDigest(root: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var entries = 0
    var bytes = 0L
    fun frame(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.size).array())
        digest.update(encoded)
    }
    fun visit(file: File, depth: Int) {
        require(++entries <= 10_000 && depth <= 32 && !Files.isSymbolicLink(file.toPath()) && isWithin(file, root)) {
            "Skill code tree contains a symlink or exceeds discovery bounds"
        }
        frame(if (file == root) "" else file.relativeTo(root).invariantSeparatorsPath)
        if (file.isDirectory) {
            frame("directory")
            val children = boundedSkillChildren(file, 10_000)
            children.sortedBy { it.name }.forEach { visit(it, depth + 1) }
        } else {
            require(file.isFile && file.length() <= 16L * 1024 * 1024) { "Unsupported or oversized skill resource: $file" }
            frame("file:${file.length()}:${file.canExecute()}")
            val fileHash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var fileBytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                    fileBytes += read
                    require(bytes <= 128L * 1024 * 1024 && fileBytes <= 16L * 1024 * 1024) { "Skill code exceeds hashing bounds" }
                    fileHash.update(buffer, 0, read)
                }
                require(fileBytes == file.length()) { "Skill resource changed while checking code trust" }
            }
            digest.update(fileHash.digest())
        }
    }
    require(root.isDirectory) { "Skill code root is missing" }
    visit(root, 0)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun workspaceSkillProjects(root: File, onError: (String) -> Unit): List<Pair<File, String>> {
    val projects = mutableListOf<Pair<File, String>>()
    var entries = 0
    fun visit(directory: File, depth: Int) {
        if (entries >= 20_000) return
        if (++entries > 20_000 || depth > 10) { onError("Nested workspace skill discovery limit reached"); return }
        if (Files.isSymbolicLink(directory.toPath()) || !isWithin(directory, root)) return
        val config = File(directory, ".claude")
        if (config.isDirectory && !Files.isSymbolicLink(config.toPath())) {
            val qualifier = if (directory == root) "" else directory.relativeTo(root).invariantSeparatorsPath
            projects += directory to qualifier
        }
        val children = try { boundedSkillChildren(directory, 20_000 - entries) } catch (e: Exception) {
            onError("Nested workspace discovery: ${e.message}")
            return
        }
        entries += children.count { !it.isDirectory }
        children.filter { it.isDirectory && it.name !in setOf(".claude", ".git", ".openclaw", "node_modules") }
            .sortedBy { it.name }.forEach { visit(it, depth + 1) }
    }
    visit(root, 0)
    return projects
}

internal fun isWithin(file: File, root: File): Boolean = file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())

internal fun safeSkillChild(root: File, relative: String): File {
    require(relative.isNotEmpty() && !relative.startsWith('/') && '\\' !in relative && ':' !in relative &&
        relative.split('/').none { it == ".." || it.isEmpty() || it.any { char -> char.code < 32 } }) { "Unsafe skill path '$relative'" }
    val child = File(root, relative)
    require(isWithin(child, root)) { "Skill path escapes its root" }
    var parent: File? = child
    while (parent != null && parent.absoluteFile != root.absoluteFile) {
        require(!Files.isSymbolicLink(parent.toPath())) { "Skill symlinks are not supported: $relative" }
        parent = parent.parentFile
    }
    require(!Files.isSymbolicLink(root.toPath())) { "Skill root cannot be a symlink" }
    return child
}

internal fun readSkillDocument(file: File, fallback: String): SkillDocument {
    return SkillDocument.parse(readSkillText(file, SkillDocument.MAX_DOCUMENT_BYTES), fallback)
}

internal fun readSkillText(file: File, limit: Int): String {
    require(file.isFile && file.length() <= limit) { "${file.name} is missing or exceeds its $limit byte limit" }
    val (bytes, truncated) = file.inputStream().use { it.readCapped(limit) }
    require(!truncated) { "${file.name} exceeds its $limit byte limit" }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

internal fun boundedSkillChildren(directory: File, limit: Int): List<File> {
    val children = mutableListOf<File>()
    Files.newDirectoryStream(directory.toPath()).use { stream ->
        for (path in stream) {
            require(children.size < limit) { "Too many entries in skill directory: $directory" }
            children += path.toFile()
        }
    }
    return children
}

/** Bounded scans ignore generated cross-agent copies, hidden transaction dirs, and symlinks. */
internal fun walkSkillFiles(root: File, excludedRoots: Set<File> = emptySet(), onError: (String) -> Unit = {}): List<File> {
    val files = mutableListOf<File>()
    var entries = 0
    fun visit(directory: File, depth: Int) {
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) || directory.absoluteFile in excludedRoots || entries > 20_000) return
        if (depth > 10) { onError("Skill discovery depth limit reached at $directory"); return }
        val children = try { boundedSkillChildren(directory, 20_000 - entries) } catch (e: Exception) {
            onError("Skill discovery: ${e.message}")
            return
        }
        for (file in children.sortedBy { it.name }) {
            if (++entries > 20_000) { onError("Skill discovery entry limit reached"); return }
            if (Files.isSymbolicLink(file.toPath()) || !isWithin(file, root)) continue
            if (file.isDirectory) {
                if (file.name in setOf(".git", ".openclaw", "node_modules", ".trash") || file.name.startsWith(".stage-")) continue
                visit(file, depth + 1)
            } else if (file.isFile && (file.name == "SKILL.md" || file.extension.equals("md", true) ||
                    (file.name == "plugin.json" && file.parentFile?.name == ".claude-plugin"))) files += file
        }
    }
    visit(root, 0)
    return files
}

internal fun candidateFor(file: File, boundary: File): SkillCandidate {
    val document = readSkillDocument(file, file.parentFile!!.name)
    var directory: File? = file.parentFile
    var pluginRoot: File? = null
    var namespace: String? = null
    while (directory != null && isWithin(directory, boundary)) {
        val manifest = File(directory, ".claude-plugin/plugin.json")
        if (manifest.isFile) {
            require(manifest.length() <= 128 * 1024) { "Plugin manifest exceeds 128 KiB" }
            val json = JSONObject(readSkillText(safeSkillChild(directory, ".claude-plugin/plugin.json"), 128 * 1024))
            namespace = json.getString("name")
            validateSkillCommand(namespace)
            require(':' !in namespace) { "Plugin names cannot contain namespace colons" }
            pluginRoot = directory
            break
        }
        if (directory.canonicalFile == boundary.canonicalFile) break
        directory = directory.parentFile
    }
    val command = if (namespace != null && !document.name.startsWith("$namespace:")) "$namespace:${document.name}" else document.name
    validateSkillCommand(command)
    return SkillCandidate(file, document, command, pluginRoot)
}

internal fun legacyCandidate(file: File, commandsRoot: File, pluginRoot: File?): SkillCandidate {
    val name = file.relativeTo(commandsRoot).invariantSeparatorsPath.removeSuffix(".${file.extension}").replace('/', ':')
    val namespace = pluginRoot?.let {
        JSONObject(readSkillText(safeSkillChild(it, ".claude-plugin/plugin.json"), 128 * 1024)).getString("name")
    }
    val command = if (namespace != null) "$namespace:$name" else name
    validateSkillCommand(command)
    val document = readSkillDocument(file, file.nameWithoutExtension).copy(name = name)
    return SkillCandidate(file, document, command, pluginRoot)
}
