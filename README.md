# Hoard

<p align="center"><img src="docs/hoard-icon.png" width="160" alt="Hoard 앱 아이콘 — 말차색 고양이 마스코트"></p>

AI chat app shell by **sleepysoong** — https://github.com/sleepysoong/hoard

Liquid-glass design shell (white-first + dark mode). ChatGPT/Claude/Gemini-style
chat with **mock data only**: sending a message streams fake thinking + reply text.

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

- Chat with streaming replies from sleepyrouter (offline mock when no router is set), per-message elapsed seconds + token counts
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
  function calling (Settings → 웹 도구; on by default in router mode):
  - `web_search` — discover pages via the Brave Search API with **the user's own key**
    (none is bundled). Output is normalized (`search_1…` ids, title/url/snippet, `correctedQuery`,
    `hasMore`), never Brave's raw JSON; 429 is retried with backoff honouring `X-RateLimit-Reset`.
    The description tells the model that snippets are not page contents.
  - `web_fetch` — read a page: public http(s) only (loopback/LAN/metadata/CGNAT/ULA refused, checked
    on every redirect), `finalUrl` on redirects, size/length caps, main content → Markdown (jsoup).
  - `SearchProvider` interface: Brave is one implementation; Tavily/SearXNG can be added without
    touching the tool schema. Tool loop in `RouterAiEngine`: up to 8 rounds, then `tool_choice: none`.
    Each call appears as a step under the reply's 추론 section.
- Model thinking blocks (expandable steps) + model picker (router groups/models, mock when offline)
- Plugins / MCP / Skills / slash-commands tabs with liquid-glass UI + mock data (not sent to the router yet)
- Top floating bar: session name + live context usage
- Background continuation: replies are produced by a `WorkManager` worker with a
  foreground notification, so leaving the app after send still finishes the reply.
  Always on by design — there is no setting to disable it.
- Liquid glass everywhere via Backdrop 2.0.1 (`drawBackdrop` + `blur` + `lens`),
  glassmorphism fallback on older APIs / preview. **No gradients** — solid colors only.
  How it was built, every pitfall hit and how to verify it: [`LIQUID_GLASS.md`](LIQUID_GLASS.md).
- Signed release APK via `.github/workflows/build-and-release.yml`:
  auto-bumps `version.properties`, tags, and attaches `hoard-<version>.apk` (e.g. `hoard-1.0.61.apk`) to a Release.

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

With no router URL the app uses the offline mock engine. Plain HTTP is allowed
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
