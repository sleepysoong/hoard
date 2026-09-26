package com.sleepysoong.hoard.data

object MockData {
    const val DEFAULT_SYSTEM_PROMPT =
        "너는 Hoard라는 친절한 AI 어시스턴트다. 사용자의 언어로 간결하게 답하라."

    val models = listOf(
        AiModel("hoard-1-ultra", "Hoard 1 울트라", "Hoard", "최상위 추론 모델. 코드와 긴 컨텍스트에 강함.", true, true),
        AiModel("hoard-1-pro", "Hoard 1 프로", "Hoard", "빠르고 균형잡힌 일상용 모델.", true, true),
        AiModel("hoard-1-mini", "Hoard 1 미니", "Hoard", "가벼운 질문용 경량 모델.", false, false),
        AiModel("hoard-vision-xl", "Hoard 비전 XL", "Hoard", "이미지·PDF·스크린샷 분석에 강함.", true, false),
        AiModel("community-gpt-mock", "커뮤니티 GPT (목업)", "커뮤니티", "오픈 가중치 스타일 목업 엔드포인트.", false, false),
        AiModel("community-claude-mock", "커뮤니티 Claude (목업)", "커뮤니티", "장문 작성용 목업 엔드포인트.", false, true)
    )

    val slashCommands = listOf(
        SlashCommand("/summarize", "현재 세션 요약", "핵심만 요약"),
        SlashCommand("/translate", "마지막 답변 번역", "/translate English"),
        SlashCommand("/code", "코드 우선 답변", "/code 코틀린 퀵소트"),
        SlashCommand("/imagine", "이미지 프롬프트 작성", "/imagine 리퀴드 글래스 섬"),
        SlashCommand("/plan", "주제를 계획으로 변환", "/plan 주말 여행"),
        SlashCommand("/explain", "쉽게 설명", "/explain 트랜스포머")
    )

    val plugins = listOf(
        PluginItem("web-search", "웹 검색", "인용이 포함된 목업 검색 결과.", true),
        PluginItem("code-runner", "코드 실행", "목업 Kotlin/Python 실행 샌드박스.", false),
        PluginItem("calendar", "캘린더", "목업 일정 조회 및 예약.", true),
        PluginItem("memory", "메모리", "세션 간 목업 장기 기억.", false)
    )

    val skills = listOf(
        SkillItem("pdf-reader", "PDF 리더", "첨부 PDF의 텍스트·표 추출 (목업).", true),
        SkillItem("image-ocr", "이미지 OCR", "첨부 사진 속 글자 읽기 (목업).", true),
        SkillItem("csv-analyst", "CSV 분석", "첨부 스프레드시트 요약 (목업).", false),
        SkillItem("meeting-notes", "회의록", "회의 기록을 할 일 목록으로 정리 (목업).", false)
    )

    fun mockMcpServers(): List<McpServer> = listOf(
        McpServer("local-fs", "로컬 파일", "stdio://hoard-fs", true, 8, "연결됨"),
        McpServer("github-mock", "GitHub", "https://mcp.mock/github", false, 12, "연결 끊김"),
        McpServer("notion-mock", "Notion", "https://mcp.mock/notion", true, 5, "연결됨")
    )

    fun welcomeSession(modelId: String = "hoard-1-pro"): ChatSession = ChatSession(
        id = "session-welcome",
        name = "Hoard에 오신 것을 환영합니다",
        systemPrompt = DEFAULT_SYSTEM_PROMPT,
        modelId = modelId,
        contextLimit = 32_000
    )

    fun welcomeMessages(): List<ChatMessage> = listOf(
        ChatMessage(
            id = "msg-welcome-1",
            role = MessageRole.Assistant,
            text = "안녕하세요, Hoard입니다 (목업). 편하게 질문해 보세요. " +
                "사진이나 파일을 첨부할 수 있고, / 를 입력하면 명령어, 도구 탭에서 MCP 서버·플러그인·스킬을 관리할 수 있어요.",
            modelId = "hoard-1-pro",
            thinking = listOf(
                ThinkingStep("요청 파악", "인사 의도로 판단.", 210),
                ThinkingStep("답변 계획", "채팅·첨부·명령어·도구 안내.", 340)
            ),
            elapsedMs = 1240,
            promptTokens = 18,
            completionTokens = 42
        )
    )

    fun mockThinking(question: String, contextMessages: Int, droppedMessages: Int, tools: List<String>): List<ThinkingStep> = listOf(
        ThinkingStep("요청 파악", "분석: ${question.take(64)}…", 320),
        ThinkingStep(
            "컨텍스트 조회",
            "이전 메시지 ${contextMessages - 1}개 참고" +
                (if (droppedMessages > 0) ", 컨텍스트 한도로 오래된 메시지 ${droppedMessages}개 생략" else "") +
                (if (tools.isNotEmpty()) " · 도구: ${tools.joinToString(", ")}" else "") + " (목업).",
            480
        ),
        ThinkingStep("답변 작성", "간결한 목업 답변 구성.", 610)
    )

    fun mockAnswer(question: String, modelId: String, hasAttachments: Boolean): String {
        val cmd = slashCommands.firstOrNull { question.trimStart().startsWith(it.command) }
        val attachNote = if (hasAttachments) "\n\n첨부파일도 확인했습니다 (목업 분석: 내용 좋아 보이네요)." else ""
        val base = when {
            cmd != null -> "**${cmd.command}** 실행 중 (목업): ${cmd.description}. " +
                "\"${question.removePrefix(cmd.command).trim().ifEmpty { "요청 내용" }}\"" +
                "에 대한 목업 답변입니다. 나중에 실제 백엔드로 교체하면 됩니다."
            question.length < 24 -> "짧은 답변 (목업, $modelId): \"$question\" — 알겠습니다. " +
                "핵심만 먼저 정리했고, 실제 모델이었다면 이어서 물어볼 내용까지 붙였을 거예요."
            else -> "**$modelId**의 목업 답변: " +
                "\"${question.take(120)}${if (question.length > 120) "…" else ""}\"" +
                " 내용을 파악했습니다. " +
                "지금은 껍데기 단계라 생각 과정+텍스트 스트리밍과 시간 측정까지만 보여주고, 실제 백엔드는 함수 하나만 교체하면 됩니다."
        }
        return base + attachNote + "\n\n- 목업 항목 하나\n- 목업 항목 둘\n\n후속 질문을 하거나 내 메시지를 수정해 보세요."
    }
}
