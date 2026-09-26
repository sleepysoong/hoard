# Hoard

AI chat app shell by **sleepysoong** — https://github.com/sleepysoong/hoard

Liquid-glass design shell (white-first + dark mode). ChatGPT/Claude/Gemini-style
chat with **mock data only**: sending a message streams fake thinking + reply text.

## What is implemented (shell)

- Chat with mock streaming replies, per-message elapsed seconds + token counts
- Photo (`PickMultipleVisualMedia`) and file (`OpenMultipleDocuments`) attachments (mock)
- System prompt editing, per-session context limit, session rename/delete
- Edit my message (save & regenerate), branch from a message, delete message, retry
- Model thinking blocks (expandable steps) + model picker (mock)
- Plugins / MCP / Skills / slash-commands tabs with liquid-glass UI + mock data
- Top floating bar: session name + live context usage
- Background continuation: replies are produced by a `WorkManager` worker with a
  foreground notification, so leaving the app after send still finishes the reply.
  Always on by design — there is no setting to disable it.
- Liquid glass everywhere via Backdrop 2.0.1 (`drawBackdrop` + `blur` + `lens`),
  glassmorphism fallback on older APIs / preview. **No gradients** — solid colors only.
- Signed release APK via `.github/workflows/build-and-release.yml`:
  auto-bumps `version.properties`, tags, and attaches `app-release.apk` to a Release.

## The one seam for the real backend

`engine/MockAiEngine.kt :: streamReply(ReplyRequest)` — replace with a real network call.
`ReplyRequest` already carries the model, current system prompt, history trimmed to the
session context limit, attachments and enabled plugin/MCP/skill names.
UI (`ChatViewModel`, `ChatScreen`, `ChatResponseWorker`) stays unchanged.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

Robolectric E2E flows drive the real ViewModel → WorkManager → worker → engine → store
stack. Each test writes a conversation transcript to `app/build/test-artifacts/`.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # signed with app/release.keystore
```
