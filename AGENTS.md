# Agent Rules — Hoard

## Testing & verification

- **Never write unit tests after writing the code.** If a change needs tests, write them before (or alongside) the code.
- **Prefer end-to-end (E2E) tests as the main way to test.** Use them to confirm complex features actually work, not just compile.
- **Make E2E tests produce an artifact that can be checked and reproduced** (recording, video, screenshot, or an output file in a predictable path).
- **If you need to test a system in isolation, first list all the ways it could fail.** Then write the code that would catch each of those ways—not just the happy path.
- **For complex features, use realistic E2E scenarios with medium or high complexity.** Don’t test only the simplest successful case; cover the paths that actually break in production.
- **Avoid tautological tests** that only confirm what the code already says (e.g. `assertThat(foo).isEqualTo(foo)`).
- **Avoid tests that only detect whether code changed.** A test should verify behavior, not presence.
- **For bug fixes, add a regression test only when existing behavior tests leave a real gap.** Don’t add a test mechanical bug-by-bug that duplicates coverage.

## Why this way

This project runs an Android app with heavy UI and platform constraints (Compose, glass effects, WorkManager, foldables). Small units of logic are rarely the failure point; the failures I actually hit are integration (backdrops across windows, insets, FGS declarations, IME behavior) that only E2E exposes. I’d rather spend cycles on real flows than on micro-mock tests of Repositories.

## When nothing existed

If a module already has no tests, write the E2E (or the narrowest meaningful integration) test that covers the feature being touched. Don’t backfill unit tests for legacy code.

## Building & testing on the dev machine (low spec)

The dev box is small (3 cores / 6 GB) and **freezes when a build or test run takes all of it.** Always build and test at reduced spec:

- **Use `scripts/gradlew-lowspec.sh` instead of `./gradlew`.** It runs Gradle at the lowest CPU/IO priority (`nice 19`, `ionice idle`), with one worker, and stops the daemon afterwards so it doesn't keep ~1.5 GB resident.
  - Tests: `scripts/gradlew-lowspec.sh :app:testDebugUnitTest -q`
  - One class: `scripts/gradlew-lowspec.sh :app:testDebugUnitTest -q --tests '*PopupMotionTest'`
  - APK: `scripts/gradlew-lowspec.sh :app:assembleDebug -q`
- **Never run the full suite locally — it lags the machine even at low spec.** Locally run only the one or two
  test classes you touched (`--tests '*OneTest*'`). The full suite (including `RealSleepyrouterTest` against a
  freshly built sleepyrouter) runs in GitHub Actions (`.github/workflows/test.yml`) on every push; check it with
  `gh run list --workflow=Tests` and read failures in the run summary / `hoard-test-output` artifact.
- **Never combine heavy tasks in one invocation** (e.g. full test suite + assemble) and never start a second Gradle run while one is going.
- Don't raise the limits in `gradle.properties` / `testOptions` (Gradle heap 1280m, one Robolectric fork with 1024m, serial GC, `ActiveProcessorCount=2`). If something runs out of memory, split the run instead.

- If a build dies with odd errors (`Illegal Capacity`, Kryo "No space left"), check `df -h /`: a full disk corrupts
  Gradle's test-result store — free space, then `rm -rf app/build/test-results`. Robolectric's ~200 MB native runtime
  now unpacks into `app/build/test-tmp` (not tmpfs `/tmp`, which is RAM on this box and OOM-killed sessions).

- This box is an Incus container: 6 GB hard limit, **no swap possible**. Other agents (e.g. codex running
  `make test -race` in sleepyrouter) may build at the same time. `gradlew-lowspec.sh` waits for 1.5 GB free and
  marks Gradle `oom_score_adj=1000` so an OOM kills the build, not the session. For the whole suite, run it in
  chunks (a few test classes per Gradle run) instead of one big run.

## Backend contract (sleepyrouter)

Hoard talks to sleepyrouter's `POST /hoard/v1/responses` (repo: `../sleepyrouter`). The wire contract
lives in sleepyrouter's `docs/protocol-openai.md` ("Hoard endpoint"); Hoard's side is `engine/RouterAiEngine.kt`.

- When changing either side, keep `FakeRouter` (app/src/test/.../testing) byte-compatible with the real
  router and run `RealSleepyrouterTest` against a freshly built binary (see README). The fake alone once hid a
  real bug (stream-path status codes lost in the router).
- Commit and push each repository separately, one logical change at a time.
- Tools are assembled only through `ToolKit` (modules + `ToolContext`/`ToolServices`); follow README "Tool API" when adding one.
- Tools (`tools/`) execute on the device; the router only relays `function_call` / `function_call_output`.
  Tool output must stay provider-neutral (never forward a search provider's raw JSON), and `web_fetch`
  URLs come from the model — keep the public-address check on every redirect hop (`PageFetcher`).
  Never bundle API keys (Brave): the user enters their own in Settings.
