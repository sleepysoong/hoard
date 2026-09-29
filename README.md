# Hoard

<p align="center"><img src="docs/hoard-icon.png" width="160" alt="Hoard 앱 아이콘 — 말차색 고양이 마스코트"></p>

AI chat app shell by **sleepysoong** — https://github.com/sleepysoong/hoard

Liquid-glass AI chat app (white-first + dark mode). Replies come from
[sleepyrouter](https://github.com/sleepysoong/sleepyrouter); the app ships no sample data
or fake replies — without a router it says so.

## Brand colours

![Hoard palette](docs/palette.png)

The mascot (app icon) sets the two key colours: **matcha** and **cocoa**.

| Token | Hex | Role |
| --- | --- | --- |
| `HoardMatcha` | `#9CC054` | Fill accent: switches on, success fills (light) |
| `HoardMatchaLight` | `#B4D866` | Matcha on dark backgrounds, secondary (dark) |
| `HoardMatchaDeep` | `#5E7A24` | Matcha-coloured *text* on white (4.9:1), e.g. "연결됨" |
| `HoardMatchaPale` | `#E4F0CB` | Selected pills / tab capsule (`primaryContainer`, light) |
| `HoardMatchaNight` | `#2E3E17` | Selected pills / tab capsule (dark) |
| `HoardCocoa` | `#7E4E30` | Text & icon accent, my bubbles (`primary`, light; 6.9:1 on white) |
| `HoardCocoaDeep` | `#4A2A14` | Text on matcha pills (10.8:1) |
| `HoardLatte` | `#D7A57C` | Cocoa on dark backgrounds (`primary`, dark; 8.9:1) |
| `HoardEspresso` | `#411C03` | Mascot eyes / deepest brown |

Matcha is too light for text on white (2.1:1), so it's used as a fill; text in
the accent colour uses cocoa (or matcha deep). Defined in `ui/theme/Color.kt`, wired in `ui/theme/Theme.kt`.
Still no gradients: every colour is solid.

## What is implemented

- Chat with streaming replies from sleepyrouter, per-message elapsed seconds + token counts
- Routing trace per reply: answering model, tried models, why each failed
- Photo (`PickMultipleVisualMedia`) and file (`OpenMultipleDocuments`) attachments, sent as real content
- Conversations persist across restarts (`data/HoardStore.kt`: one JSON file in app storage, atomic writes,
  debounced + flushed when the app leaves the screen; a corrupt file is kept aside, not overwritten)
- System prompt editing, per-session context limit, session rename/delete
- Edit my message (save & regenerate), branch from a message, delete message, retry
- Model replies render as Markdown (`ui/chat/MarkdownText.kt`, [huarangmeng/Markdown](https://github.com/huarangmeng/Markdown)):
  headings, **bold**/*italic*/~~strike~~, lists, task lists, GFM tables, quotes, code blocks with
  highlighting, and LaTeX (`$…$` inline, `$$…$$` block). What I type is shown verbatim.
  Remote images in replies are not fetched (alt text shown instead).
- Web tools (`tools/`), run on the device and offered to the model through sleepyrouter's
  function calling (always on in router mode; Brave key in 설정 → 웹 검색):
  - Flow: `web_search` → 10 results → the model picks the relevant URLs → several `web_fetch` in one turn
    (run in parallel, max 5) → answer from the fetched text. Read-only tools of a round run concurrently;
    side-effect tools (write/edit, termux) run alone in call order.
  - `web_search` — discover pages via the Brave Search API with **the user's own key**
    (none is bundled). Fixed params `count=10, extra_snippets=false, text_decorations=false, operators=true,
    result_filter=web`; `country` / `search_lang` only when the model sets them for a clearly local / single-language
    query (never from the device locale). The description teaches query refinement (specific / English terms,
    `site:`, `"exact"`, `-exclude`, AND/OR/NOT, `filetype:`). Output is normalized (`search_1…` ids, title/url/snippet, `correctedQuery`,
    `hasMore`), never Brave's raw JSON; 429 is retried with backoff honouring `X-RateLimit-Reset`.
    The description tells the model that snippets are not page contents.
  - `web_fetch` — read a page: public http(s) only (loopback/LAN/metadata/CGNAT/ULA refused, checked
    on every redirect), `finalUrl` on redirects, size/length caps, main content → Markdown (jsoup).
  - `SearchProvider` interface: Brave is one implementation; Tavily/SearXNG can be added without
    touching the tool schema. Tool loop in `RouterAiEngine`: up to 8 rounds, then `tool_choice: none`.
    Each call appears as a step under the reply's 추론 section.
- File tools (always on): `read_file(path, offset?, limit?)`, `write_file(path, content)`,
  `edit_file(path, old_string, new_string, replace_all?)`, `glob(pattern, path?)`,
  `grep(pattern, path?, glob?, case_sensitive?, max_results?)`. Relative paths = the app's private workspace
  (`filesDir/workspace`). Shared storage is also reachable (`/storage/emulated/0`:
  Download, Documents, DCIM…) by absolute path, like a file manager — needs Android's "All files access"
  (`MANAGE_EXTERNAL_STORAGE`, granted in system settings; Android still blocks `Android/data`/`obb`).
  Anything else (`..`, other absolute paths, symlinks out) is refused. UTF-8 text only, atomic writes,
  grep has a 3 s regex budget, glob/grep walks stop at 200k entries / 15 s. Code: `tools/files/`.
- `termux_exec` (always on; needs Termux + its RUN_COMMAND permission): one-shot shell commands in Termux via the official
  `com.termux.RUN_COMMAND` intent to `RunCommandService` (`bash -lc <command>`, background, result back
  through a PendingIntent → `TermuxResultReceiver`). Returns `{stdout, stderr, exitCode}` as-is (non-zero exit
  is a normal result). Distinct errors for: Termux not installed, RUN_COMMAND permission missing,
  `allow-external-apps` not set, timeout. Short commands only (Binder size limit); no PTY/streaming.
  One-time Termux setup: `mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && termux-reload-settings`.
  Code: `tools/TermuxExecTool.kt` (tool layer) ↔ `termux/` (Android side, `TermuxConstants` from termux-shared, compileOnly).
- **Goals** (`goal/`): a thread-scoped persistent completion contract. `/goal <objective>` (or the model's
  `goal` tool, action create/get/complete/block) sets one per session; after each settled turn the
  continuation engine (in `ChatResponseWorker.afterTurn`, outside the agent loop) queues a hidden continuation
  turn while the goal is active, the session is idle (nothing queued, no pending wakeup) and budget remains
  (8 auto turns / 150k tokens / 30 min). Completion needs concrete evidence; a continuation with no tool call
  and no goal change suppresses further ones (spin guard); an exhausted budget → `budget_limited` + one
  summary turn, never "completed". Goal context is injected as ephemeral developer text, never stored in the
  conversation. Pause/resume/clear are user-only (goal bar → sheet, `/goal pause|resume|clear`); the stop
  button pauses; branching snapshots the goal (`parentGoalId`). Goals persist with the conversation store.
- **Schedules** (`schedule/`): durable `at` / `every` (anchored, no drift) / 5-field `cron` + IANA zone,
  created by the model's `schedule` tool on request. Each firing claims a unique (schedule, planned_at) run,
  applies overlap (skip/queue/parallel) and catch-up (skip/latest/all≤3) policies, and runs in its **own
  isolated session** ("예약 · …") with the prompt (+ optional context snapshot) and the permissions
  snapshotted at creation ∩ current settings (no schedule/wakeup tools inside a run). Run history,
  stale-run recovery and re-arming on app start (`SchedulerEngine.reconcile`). Backend: WorkManager (survives
  reboot; Doze may delay — 15 min grace). `schedule_wakeup` (5 s–1 h, one per session) wakes the same session
  later instead of busy-polling.
- Work steps (reasoning / tool calls, expandable cards) + model picker (router groups/models)
- Session task tracking through one `todo` tool (`create`, `update`, `remove`, `list`, `clear`), with no priorities.
  State lives in one SQLite database keyed by session, independent of chat history. At most one task can be
  in progress; app restart restores tasks, branches copy unfinished work with new IDs, and a new turn gets
  one temporary reminder when unfinished tasks exist. A collapsible glass panel shows committed progress.
  Contract, lifecycle and E2E artifacts: [`docs/TODO_TOOL.md`](docs/TODO_TOOL.md).
- No tool toggles: every tool is always on (the 도구 tab is empty for now). At every app launch
  `PermissionGate` asks for what's missing: notifications + Termux RUN_COMMAND (if Termux is installed) in one
  dialog, then Android's "All files access" screen (shared storage for the file tools).
- Settings: theme, router (URL/token), default model (typed; blank = router's first group), Brave key, default context.
- Top floating bar: session name + live context usage
- Background continuation: replies are produced by a `WorkManager` worker with a
  foreground notification, so leaving the app after send still finishes the reply.
  Always on by design — there is no setting to disable it.
- Liquid glass everywhere via Backdrop 2.0.1 (`drawBackdrop` + `blur` + `lens`),
  glassmorphism fallback on older APIs / preview. **No gradients** — solid colors only.
  How it was built, every pitfall hit and how to verify it: [`LIQUID_GLASS.md`](LIQUID_GLASS.md).
- Signed release APK via `.github/workflows/build-and-release.yml`:
  auto-bumps `version.properties`, tags, and attaches `hoard-<version>.apk` (e.g. `hoard-1.0.61.apk`) to a Release.

## Tool API

Every tool the model can call lives in `app/src/main/kotlin/com/sleepysoong/hoard/tools/` and is assembled
per turn by one entry point, `ToolKit`:

```
ChatResponseWorker
  └─ ToolKit.registry(ToolContext(sessionId, modelId, AndroidToolServices(…), permissions, scheduledRun))
       └─ for each ToolModule in ToolKit.modules → module.tools(context)
  = ToolRegistry ──► RouterAiEngine: schemas() → request `tools`, guidance() → `instructions`,
                                      execute(name, args) in the tool loop (parallel-safe calls concurrently)
```

### Pieces

| Type | Role |
|--|--|
| `Tool` | One function: `name`, `description`, `parameters` (JSON Schema), `execute(args): JsonObject`. Optional: `guidance` (system text while offered), `parallelSafe` (read-only → may run concurrently), `title` / `subject(args)` / `summarize(output)` (the reply's 작업 card). |
| `ToolModule` | A group of related tools: `id` + `tools(context)`. Decides from the context whether its tools are offered this turn. |
| `ToolContext` | One turn's facts: `sessionId`, `modelId`, `services`, `permissions` (a scheduled run's snapshot, else everything), `scheduledRun`. |
| `ToolServices` | Dependencies, lazily created: `searchProvider`, `pageFetcher`, `workspace(fullStorage)`, `termux`, `todos`, `goals`, `schedules`, `wakeups`. `AndroidToolServices` in the app; `SimpleToolServices(…)` for tests/partial setups. A missing (null) service = its tools aren't offered. |
| `ToolKit` | `modules` (built-in, in offer order) and `registry(context, modules = …)`. `ToolKit.override` replaces the whole registry in tests. |
| `ToolRegistry` | `register(tool)`, `tools`, `schemas()`, `guidance()`, `execute(name, argumentsJson): ToolOutcome` (never throws except cancellation), `preview`, `isParallelSafe`. |
| `toolParameters { … }` | Schema DSL: `string(name, description, required, enum, minLength, maxLength)`, `integer(name, description, required, minimum, maximum)`, `boolean(…)`; `additionalProperties = false` by default (`null` omits it). |
| Argument helpers | `args.string(k)`, `args.requireString(k)`, `args.number(k)` / `long(k)` (accept `5`, `5.0`, `"5"`), `args.bool(k)`. |

Built-in modules (`ToolKit.modules`, in this order):

| Module | Tools | Offered when |
|--|--|--|
| `todo` | `todo` | always (session task list) |
| `web` | `web_search`, `web_fetch` | `web_search` only with a search provider (Brave key in Settings) |
| `termux` | `termux_exec` | always; the call reports if Termux / its permission is missing |
| `files` | `read_file`, `write_file`, `edit_file`, `glob`, `grep` | always; shared storage needs "All files access" |
| `goal` | `goal` | always |
| `schedule` | `schedule`, `schedule_wakeup` | not inside a scheduled run |

### Contract every tool follows

- **Output** is a JSON object in the tool's own normalized shape (never a provider's raw response), small enough
  for the model's context (cap long text and say so, e.g. `truncated: true`).
- **Errors** the model should see: throw `ToolException("…")` → it receives `{"error": "…"}` and can adapt. Other
  exceptions become `{"error": "Type: message"}`; cancellation propagates (stop button).
- **Side effects**: leave `parallelSafe = false` (default). Read-only tools set it to `true`; consecutive read-only
  calls of one round run concurrently (max 5), anything else runs alone, in call order.
- **Security**: arguments come from the model — possibly steered by a web page it read. Validate them, keep file
  access inside `Workspace`, network access to public addresses (`PageFetcher`), never bundle API keys.
- **Card text**: `title` is the tool's short Korean name; `subject(args)` the call's target (query, path);
  `summarize(output)` a one-line result. The registry shows failures as `실패: …`.
- **Guidance**: put cross-turn usage rules (when to use it, what to do next) in `guidance`, not in every call.

### Adding a tool

1. Write the tool (next to related ones in `tools/`):

   ```kotlin
   class WeatherTool(private val api: WeatherApi) : Tool {
       override val name = "weather"
       override val title = "날씨"
       override val description = "Current weather for a city. Returns temperature (°C) and conditions."
       override val parameters = toolParameters { string("city", "City name, e.g. Seoul.", required = true) }
       override val parallelSafe = true
       override fun subject(args: JsonObject) = args.string("city").orEmpty()
       override suspend fun execute(args: JsonObject): JsonObject {
           val w = api.current(args.requireString("city")) ?: throw ToolException("unknown city")
           return buildJsonObject { put("tempC", w.tempC); put("conditions", w.conditions) }
       }
   }
   ```
2. If it needs a new dependency, add it to `ToolServices` (nullable), `AndroidToolServices` and `SimpleToolServices`.
3. Add a module (or extend one) and list it in `ToolKit.modules`:

   ```kotlin
   object WeatherModule : ToolModule {
       override val id = "weather"
       override fun tools(context: ToolContext) = listOfNotNull(context.services.weather?.let(::WeatherTool))
   }
   ```
4. Test it without Android: `ToolKit.registry(ToolContext("s", "m", SimpleToolServices(…)), listOf(WeatherModule))`,
   then `registry.execute("weather", """{"city":"Seoul"}""")`. End-to-end through the tool loop: set
   `ToolKit.override` and drive a `FakeRouter` that returns a `function_call` (see `ToolLoopTest`, `ToolKitTest`).

## Backend: sleepyrouter

Replies come from [sleepyrouter](https://github.com/sleepysoong/sleepyrouter)'s
`POST /hoard/v1/responses` — the OpenAI Responses API plus a routing trace
(`sleepyrouter.routing`: which models were tried, why each failed or was skipped,
which one answered).

1. Run sleepyrouter (default `127.0.0.1:4567`; bind to a LAN/Tailscale address to reach it from the phone).
2. In the app: **설정 → 라우터**, enter e.g. `http://192.168.0.10:4567`, tap **연결**.
   The status shows the router's group/model counts, and the model picker switches to the router's catalog.
   If the router has inbound auth (`[server] auth_token_env`, needed when it listens beyond 127.0.0.1),
   enter the token in **토큰 (선택)** too; it is sent as `Authorization: Bearer …` and stored masked.
3. Chat. Each reply bubble shows `via <answering model> · N개 실패`; tap it for every attempt
   (outcome, HTTP status, error class, duration, reason). Failed replies show the reason instead of an empty bubble.

Attachments are sent as real content with the newest message (`engine/AttachmentEncoder.kt`):
images → `input_image` data URL (photos over 1.5 MB are re-encoded to ≤2048 px JPEG), text/code files →
inlined `input_text`, other files (e.g. PDF) → `input_file`. Files over 10 MB or unreadable ones are
reported to the model instead of silently dropped. Earlier turns only name their attachments.
Whether a model can use `input_file` depends on the provider.

With no router URL a reply fails with "라우터가 연결되지 않았습니다". Plain HTTP is allowed
(`network_security_config.xml`) because sleepyrouter is a local gateway; note it applies app-wide.

Code: `engine/RouterAiEngine.kt` (HTTP + SSE, request encoding, trace parsing, error
classification), `engine/RouterConnection.kt` (`/v1/models` check + catalog),
`work/ChatResponseWorker.kt` (retries transient failures up to 3 attempts; 4xx are not retried).

## Tests

```bash
scripts/gradlew-lowspec.sh :app:testDebugUnitTest -q   # low-spec wrapper, see AGENTS.md
```

Robolectric E2E flows drive the real ViewModel → WorkManager → worker → engine → store
stack. Each test writes a conversation transcript to `app/build/test-artifacts/`.

- `RouterIntegrationTest`, `RouterUiTest`: against `FakeRouter`, which speaks sleepyrouter's exact wire format.
- `RealSleepyrouterTest` (opt-in): runs the **real** sleepyrouter binary in front of fake upstreams.
  ```bash
  (cd ../sleepyrouter && go build -o /tmp/sleepyrouter-bin ./cmd/sleepyrouter)
  SLEEPYROUTER_BIN=/tmp/sleepyrouter-bin scripts/gradlew-lowspec.sh :app:testDebugUnitTest -q --tests '*RealSleepyrouterTest'
  ```
  Skipped when `SLEEPYROUTER_BIN` is unset (CI).

## Build

Requires **JDK 21** to run Gradle/tests (the Markdown/LaTeX libraries ship Java 21 bytecode;
the app itself still targets Java 17 and D8 handles the rest).

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # signed with app/release.keystore
```
