package com.sleepysoong.hoard.data

object MockData {
    const val DEFAULT_SYSTEM_PROMPT =
        "You are Hoard, a helpful on-device-styled AI assistant. Answer concisely in the user's language."

    val models = listOf(
        AiModel("hoard-1-ultra", "Hoard 1 Ultra", "Hoard", "Flagship reasoning model. Best for code and long context.", true, true),
        AiModel("hoard-1-pro", "Hoard 1 Pro", "Hoard", "Balanced everyday model with fast responses.", true, true),
        AiModel("hoard-1-mini", "Hoard 1 Mini", "Hoard", "Lightweight model for quick questions.", false, false),
        AiModel("hoard-vision-xl", "Hoard Vision XL", "Hoard", "Excels at images, PDFs and screenshots.", true, false),
        AiModel("community-gpt-mock", "Community GPT (mock)", "Community", "Open-weight style mock endpoint.", false, false),
        AiModel("community-claude-mock", "Community Claude (mock)", "Community", "Long-form writing mock endpoint.", false, true)
    )

    val slashCommands = listOf(
        SlashCommand("/summarize", "Summarize the current session", "condenses key points"),
        SlashCommand("/translate", "Translate the last answer", "/translate English"),
        SlashCommand("/code", "Answer with code first", "/code quicksort in kotlin"),
        SlashCommand("/imagine", "Describe an image prompt", "/imagine liquid glass island"),
        SlashCommand("/plan", "Turn the topic into a plan", "/plan weekend trip"),
        SlashCommand("/explain", "Explain like I'm five", "/explain transformers")
    )

    val plugins = listOf(
        PluginItem("web-search", "Web Search", "Mock search results with citations.", true),
        PluginItem("code-runner", "Code Runner", "Mock Kotlin/Python execution sandbox.", false),
        PluginItem("calendar", "Calendar", "Mock event lookup and scheduling.", true),
        PluginItem("memory", "Memory", "Mock long-term memory across sessions.", false)
    )

    val skills = listOf(
        SkillItem("pdf-reader", "PDF Reader", "Extracts text and tables from attached PDFs (mock).", true),
        SkillItem("image-ocr", "Image OCR", "Reads text inside attached photos (mock).", true),
        SkillItem("csv-analyst", "CSV Analyst", "Summarizes attached spreadsheets (mock).", false),
        SkillItem("meeting-notes", "Meeting Notes", "Formats notes into action items (mock).", false)
    )

    fun mockMcpServers(): List<McpServer> = listOf(
        McpServer("local-fs", "Local Files", "stdio://hoard-fs", true, 8, "connected"),
        McpServer("github-mock", "GitHub", "https://mcp.mock/github", false, 12, "disconnected"),
        McpServer("notion-mock", "Notion", "https://mcp.mock/notion", true, 5, "connected")
    )

    fun welcomeSession(modelId: String = "hoard-1-pro"): ChatSession = ChatSession(
        id = "session-welcome",
        name = "Welcome to Hoard",
        systemPrompt = DEFAULT_SYSTEM_PROMPT,
        modelId = modelId,
        contextLimit = 32_000
    )

    fun welcomeMessages(): List<ChatMessage> = listOf(
        ChatMessage(
            id = "msg-welcome-1",
            role = MessageRole.Assistant,
            text = "Hi, I'm Hoard (mock). Ask anything — attach a photo or file, " +
                "type / for commands, or open Tools to manage MCP servers, plugins and skills.",
            modelId = "hoard-1-pro",
            thinking = listOf(
                ThinkingStep("Understanding the request", "Greeting intent detected.", 210),
                ThinkingStep("Planning the answer", "Mention chat, attachments, commands and tools.", 340)
            ),
            elapsedMs = 1240,
            promptTokens = 18,
            completionTokens = 42
        )
    )

    fun mockThinking(question: String): List<ThinkingStep> = listOf(
        ThinkingStep("Understanding the request", "Parsed ${question.take(64)}…", 320),
        ThinkingStep("Retrieving context", "Checked session scope and attachments (mock).", 480),
        ThinkingStep("Drafting the answer", "Composed a concise response with mock data.", 610)
    )

    fun mockAnswer(question: String, modelId: String, hasAttachments: Boolean): String {
        val cmd = slashCommands.firstOrNull { question.trimStart().startsWith(it.command) }
        val attachNote = if (hasAttachments) "\n\nI also looked at your attachment(s) (mock analysis: content looks good)." else ""
        val base = when {
            cmd != null -> "Running **${cmd.command}** (mock): ${cmd.description}. " +
                "Result for \"${question.removePrefix(cmd.command).trim().ifEmpty { "your request" }}\": " +
                "here is a well-structured mock answer you can restyle later."
            question.length < 24 -> "Short version (mock, $modelId): \"$question\" — got it. " +
                "Here's the key point, plus what I'd ask next if this were wired to a real model."
            else -> "Mock answer from **$modelId**: I understood your message about " +
                "\"${question.take(120)}${if (question.length > 120) "…" else ""}\". " +
                "This shell streams thinking + text with timing so the real backend can replace one function later."
        }
        return base + attachNote + "\n\n- Mock bullet one\n- Mock bullet two\n\nAsk a follow-up or try editing your message."
    }
}
