package com.sleepysoong.hoard.skills

import android.content.Context
import android.util.Log
import com.sleepysoong.hoard.browser.RemoteBrowserConfig
import com.sleepysoong.hoard.data.ChatMessage
import com.sleepysoong.hoard.data.ChatSession
import com.sleepysoong.hoard.data.HoardRepository
import com.sleepysoong.hoard.data.MessageRole
import com.sleepysoong.hoard.data.PermissionProfile
import com.sleepysoong.hoard.data.SettingsStore
import com.sleepysoong.hoard.engine.AttachmentEncoder
import com.sleepysoong.hoard.engine.Engines
import com.sleepysoong.hoard.engine.ReplyRequest
import com.sleepysoong.hoard.engine.RouterConnection
import com.sleepysoong.hoard.engine.RouterException
import com.sleepysoong.hoard.tools.AndroidToolServices
import com.sleepysoong.hoard.tools.SkillToolPolicy
import com.sleepysoong.hoard.tools.Tool
import com.sleepysoong.hoard.tools.ToolContext
import com.sleepysoong.hoard.tools.ToolException
import com.sleepysoong.hoard.tools.ToolKit
import com.sleepysoong.hoard.tools.ToolRegistry
import com.sleepysoong.hoard.tools.readCapped
import com.sleepysoong.hoard.work.ChatResponseWorker
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** context:fork is a fresh conversation, not a branch containing the caller's history. */
class SkillForkExecutor(
    private val context: Context,
    private val cfg: SettingsStore.Settings,
    private val parentSession: ChatSession,
    private val permissions: PermissionProfile,
    private val repo: HoardRepository = HoardRepository.get(),
) {
    suspend fun execute(skill: InstalledSkill, instructions: String): JsonObject = withContext(Dispatchers.IO) {
        var child: ChatSession? = null
        var assistantId: String? = null

        fun failBubble(reason: String, routing: com.sleepysoong.hoard.data.RoutingInfo? = null) {
            val session = child ?: return
            // Adoption/enqueue can fail before the worker has created its bubble.
            val id = assistantId ?: newMessageId().also { assistantId = it }
            if (repo.messagesOf(session.id).none { it.id == id }) {
                repo.appendMessage(session.id, ChatMessage(id, MessageRole.Assistant, "", modelId = session.modelId))
            }
            repo.updateMessage(session.id, id) {
                it.copy(isStreaming = false, errorText = reason, routing = routing ?: it.routing)
            }
            repo.flush()
            if (skill.document.background) {
                runCatching {
                    SkillForkDelivery.completed(context, session.id, repo.messagesOf(session.id).firstOrNull { it.id == id })
                }.onFailure { Log.w("SkillForkExecutor", "Cannot persist fork failure delivery", it) }
            }
        }

        try {
            withTimeout(EXECUTION_TIMEOUT_MS) {
                require(skill.enabled) { "Skill /${skill.command} is disabled." }
                require(instructions.isNotBlank()) { "Skill /${skill.command} has no executable instructions." }
                val store = SkillStore.get(context)
                val agent = loadAgent(store, skill.document.agent)
                val model = resolveModel(skill.document.model ?: agent.model)
                val allowed = if (agent.readOnly) permissions.copy(termux = false, browser = false) else permissions
                val childId = "session-${UUID.randomUUID()}"
                val runtime = SkillRuntime(store, if (allowed.termux) com.sleepysoong.hoard.termux.TermuxBridge(context) else null,
                    childId, allowShell = allowed.termux && !agent.readOnly)
                val services = AndroidToolServices(context, cfg.braveApiKey, repo, RemoteBrowserConfig.from(cfg), skills = runtime)
                val assembled = ToolKit.registry(ToolContext(childId, model, services, allowed, scheduledRun = true))
                val available = assembled.tools.map { it.name }
                val effective = effectiveSkill(skill, agent, model, available)
                val session = ChatSession(
                    id = childId,
                    name = "스킬 · /${skill.command}",
                    systemPrompt = listOf(parentSession.systemPrompt, agent.prompt).filter(String::isNotBlank).joinToString("\n\n"),
                    modelId = model,
                    contextLimit = parentSession.contextLimit,
                    // Link for navigation only: addSession never copies messages, goals or todos.
                    branchedFrom = parentSession.id,
                )
                val userMessage = ChatMessage(
                    id = newMessageId(),
                    role = MessageRole.User,
                    text = "Execute /${skill.command} using its loaded skill instructions. " +
                        "Return the result and relevant artifact paths. This is an isolated skill session; " +
                        "no parent conversation is available.",
                    trigger = "skill",
                )
                child = session
                repo.addSession(session, listOf(userMessage))
                // The effective document (including agent restrictions) must be persisted by adopt;
                // a restored worker must not look up the original, less restricted document.
                runtime.adopt(effective, instructions)
                repo.flush()
                assistantId = newMessageId()

                if (skill.document.background) {
                    // The invoking message ID is not part of this executor's API; do not guess
                    // from the latest parent message, which may belong to a subsequently queued turn.
                    SkillForkDelivery.register(context, session.id, parentSession.id)
                    ChatResponseWorker.enqueue(
                        ctx = context,
                        sessionId = session.id,
                        modelId = model,
                        messageId = assistantId!!,
                        parentId = userMessage.id,
                        needsNetwork = cfg.routerUrl.isNotBlank(),
                        skillFork = true,
                        permissionOverride = allowed,
                        readOnly = agent.readOnly,
                    )
                    return@withTimeout result(skill, session, assistantId, "queued",
                        "Skill queued in a separate session. Its durable result will be available to the parent on the next turn.")
                }

                // Never rely on prompt wording to prohibit recursive forks or side effects.
                val registry = restrictRegistry(ToolRegistry(assembled.tools).also { it.skillRuntime = runtime }, agent.readOnly)
                val messageId = assistantId!!
                check(repo.appendMessage(session.id, ChatMessage(messageId, MessageRole.Assistant, "",
                    modelId = model, isStreaming = true))) { "Skill session was deleted before execution." }
                val engine = Engines.forRouter(
                    cfg.routerUrl,
                    AttachmentEncoder(AttachmentEncoder.contentReader(context.contentResolver)),
                    token = cfg.routerToken,
                    tools = registry,
                )
                val request = ReplyRequest.build(session, listOf(userMessage), modelId = model)
                    .withSkillContext(runtime.context())
                var completed = false
                // An inline fork runs inside the parent turn: child-session Stop maps here
                // (ChatResponseWorker.cancel) and must kill this stream, not just the bubble.
                activeInline[session.id] = parentSession.id
                try {
                    engine.streamReply(request) { event ->
                        completed = event.done
                        var stopped = false
                        val written = repo.updateMessage(session.id, messageId) {
                            stopped = !it.isStreaming && it.errorText == com.sleepysoong.hoard.work.ChatResponseWorker.USER_STOPPED
                            if (stopped) it else it.copy(
                                text = event.deltaText,
                                thinking = event.thinking,
                                elapsedMs = event.elapsedMs,
                                promptTokens = event.promptTokens,
                                completionTokens = event.completionTokens,
                                isStreaming = !event.done,
                                routing = event.routing ?: it.routing,
                                modelId = event.routing?.selectedModel ?: it.modelId,
                                errorText = null,
                            )
                        }
                        if (stopped) throw CancellationException(com.sleepysoong.hoard.work.ChatResponseWorker.USER_STOPPED)
                        if (!written) throw ToolException("Skill session or response was deleted during execution.")
                    }
                } finally {
                    activeInline.remove(session.id, parentSession.id)
                }
                check(completed) { "Skill stream ended without a completed response." }
                val reply = repo.messagesOf(session.id).firstOrNull { it.id == messageId }
                    ?: throw ToolException("Skill response no longer exists.")
                check(reply.text.isNotBlank()) { "Skill completed without a textual result; inspect the child session's tool artifacts." }
                repo.flush()
                result(skill, session, messageId, "completed", reply.text)
            }
        } catch (e: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) {
                failBubble("사용자가 중지함")
                throw e
            }
            val reason = "Skill execution exceeded the 10-minute time limit. Inspect the child session for partial results."
            failBubble(reason)
            result(skill, child, assistantId, "timed_out", error = reason)
        } catch (e: CancellationException) {
            failBubble("사용자가 중지함")
            throw e
        } catch (e: Exception) {
            // Do not convert an unrelated failure racing caller cancellation into a successful tool call.
            if (!currentCoroutineContext().isActive) failBubble("사용자가 중지함")
            currentCoroutineContext().ensureActive()
            val reason = (e.message ?: "${e::class.simpleName}: skill execution failed").take(ERROR_LIMIT)
            failBubble(reason, (e as? RouterException)?.routing)
            result(skill, child, assistantId, "failed", error = reason)
        }
    }

    private fun loadAgent(store: SkillStore, requested: String?): ForkAgent {
        val name = requested?.trim()?.takeIf(String::isNotEmpty) ?: "general-purpose"
        when (name.lowercase()) {
            "general-purpose" -> return ForkAgent("You are a general-purpose agent executing an isolated skill. Complete the skill and report its result.")
            "explore" -> return ForkAgent("You are Explore, a read-only research agent. Inspect files and public web information; do not change files, run shell commands, or create tasks, goals or future work.", readOnly = true)
            "plan" -> return ForkAgent("You are Plan, a read-only planning agent. Research the task and return an actionable plan; do not implement changes, run shell commands, or create tasks, goals or future work.", readOnly = true)
        }
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) && name != "." && name != "..") {
            "Unsafe skill agent '$name'. Use a simple agent name, not a path."
        }
        val file = safeSkillChild(store.workspaceRoot, ".claude/agents/$name.md")
        require(file.isFile) {
            "Unknown skill agent '$name'. Supported built-ins: general-purpose, Explore, Plan; " +
                "create workspace .claude/agents/$name.md for a custom agent."
        }
        val (bytes, truncated) = file.inputStream().use { it.readCapped(SkillDocument.MAX_DOCUMENT_BYTES) }
        require(!truncated) { "Agent '$name' exceeds 2 MiB." }
        val document = SkillDocument.parse(bytes.toString(Charsets.UTF_8), name)
        require(document.body.isNotBlank()) { "Agent '$name' has no system instructions." }
        fun rules(key: String, target: String): List<String>? {
            if (!document.frontmatter.containsKey(key)) return null
            val value = document.frontmatter[key]
            require(value is String || (value is List<*> && value.all { it is String })) {
                "Agent '$name' $key must be a tool-name string or a list of strings."
            }
            val parsed = document.copy(frontmatter = mapOf(target to value))
            return if (target == "allowed-tools") parsed.allowedTools else parsed.disallowedTools
        }
        return ForkAgent(document.body, document.model?.takeUnless { it.equals("inherit", true) },
            rules("tools", "allowed-tools") ?: rules("allowed-tools", "allowed-tools"),
            rules("disallowedTools", "disallowed-tools") ?: rules("disallowed-tools", "disallowed-tools").orEmpty())
    }

    private suspend fun resolveModel(requested: String?): String {
        if (requested.isNullOrBlank() || requested.equals("inherit", true)) return parentSession.modelId
        val catalog = repo.modelCatalog().map { it.id }.ifEmpty {
            require(cfg.routerUrl.isNotBlank()) { "Cannot resolve skill model '$requested': connect sleepyrouter first." }
            // Query without RouterConnection.refresh: refresh migrates parent sessions as a side effect.
            RouterConnection.clientFactory(cfg.routerUrl, cfg.routerToken).listModels().map { it.id }
        }
        if (requested in catalog) return requested
        val alias = requested.lowercase()
        if (alias in setOf("haiku", "sonnet", "opus")) {
            val family = Regex("(^|[-_])${Regex.escape(alias)}([-_]|$)")
            val matches = catalog.filter {
                val id = it.substringAfterLast('/').substringAfterLast(':').lowercase()
                id.startsWith("claude-") && family.containsMatchIn(id)
            }
            require(matches.size == 1) {
                if (matches.isEmpty()) "Claude alias '$requested' is unavailable in the router catalog. Set model to an available exact router ID."
                else "Claude alias '$requested' is ambiguous (${matches.take(8).joinToString()}). Set model to an exact router ID."
            }
            return matches.single()
        }
        throw ToolException("Skill model '$requested' is unavailable in the router catalog. Use an exact available router ID.")
    }

    /** Agent tools restrict access; skill allowed-tools is preapproval/advisory, not an allowlist. */
    private fun effectiveSkill(skill: InstalledSkill, agent: ForkAgent, model: String, names: List<String>): InstalledSkill {
        val agentAllowed = if (agent.readOnly) READ_ONLY_TOOLS.toList() else agent.allowed
        require(agentAllowed.orEmpty().none { '(' in it }) {
            "Custom agent tools must list tool names, not argument patterns. Use disallowedTools for argument-scoped denials."
        }
        val denied = skill.document.disallowedTools + agent.denied + FORBIDDEN_TOOLS +
            (if (agentAllowed != null && agentAllowed.isEmpty()) listOf("*")
                else if (agentAllowed != null) names.filter { name -> agentAllowed.none { SkillToolPolicy.matches(it, name) } }
                else emptyList())
        val fields = skill.document.frontmatter + mapOf(
            "disallowed-tools" to denied.distinct(),
            "model" to model,
        )
        return skill.copy(document = skill.document.copy(frontmatter = fields)).also { it.legacyFile = skill.legacyFile }
    }

    private fun result(skill: InstalledSkill, session: ChatSession?, messageId: String?, status: String,
        summary: String = "", error: String? = null): JsonObject = buildJsonObject {
        put("status", status)
        put("skill_id", skill.id)
        put("command", "/${skill.command}")
        put("background", skill.document.background)
        put("parent_session_id", parentSession.id)
        session?.let { put("session_id", it.id); put("model", it.modelId) }
        messageId?.let { put("assistant_message_id", it) }
        put("summary", summary.take(SUMMARY_LIMIT))
        put("truncated", summary.length > SUMMARY_LIMIT)
        if (summary.length > SUMMARY_LIMIT) put("truncation_notice", "Summary capped at $SUMMARY_LIMIT characters; full output remains in the child session.")
        error?.let { put("error", it.take(ERROR_LIMIT)) }
    }

    private data class ForkAgent(val prompt: String, val model: String? = null,
        val allowed: List<String>? = null, val denied: List<String> = emptyList(), val readOnly: Boolean = false)

    companion object {
        private const val EXECUTION_TIMEOUT_MS = 10 * 60 * 1000L
        private const val SUMMARY_LIMIT = 24_000
        private const val ERROR_LIMIT = 2_000
        /** childSessionId → parentSessionId while an inline fork runs (for child-stop→parent cancellation). */
        internal val activeInline = ConcurrentHashMap<String, String>()
        private val READ_ONLY_TOOLS = SkillToolPolicy.readOnlyTools
        private val FORBIDDEN_TOOLS = setOf("skill", "schedule", "schedule_wakeup")
        private fun newMessageId() = "msg-${UUID.randomUUID()}"

        /** Also used by the background worker after resumeTurn restores the adopted policy. */
        fun restrictRegistry(registry: ToolRegistry, readOnly: Boolean = false): ToolRegistry {
            val runtime = registry.skillRuntime ?: throw ToolException("Forked skill runtime was not restored.")
            if (runtime.context().isBlank()) throw ToolException("Forked skill is no longer installed or enabled, or its saved instructions are missing.")
            // Snapshot restrictions: disabling/removing a skill during a turn must not broaden access.
            val denied = runtime.disallowedTools()
            val tools = registry.tools.filter { tool ->
                tool.name !in FORBIDDEN_TOOLS && (!readOnly || tool.name in READ_ONLY_TOOLS) &&
                    denied.none { SkillToolPolicy.matches(it, tool.name) }
            }.map { tool ->
                object : Tool by tool {
                    override suspend fun execute(args: JsonObject): JsonObject {
                        // This runs AFTER any PreToolUse input rewrite in ToolRegistry.
                        if (denied.any { SkillToolPolicy.matches(it, tool.name, args) }) {
                            throw ToolException("Tool '${tool.name}' or its arguments are outside the forked skill/agent policy.")
                        }
                        return tool.execute(args)
                    }
                }
            }
            return ToolRegistry(tools).also { it.skillRuntime = runtime; it.readOnly = readOnly }
        }
    }
}
