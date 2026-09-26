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
- **Run the narrowest thing first.** Iterate with `--tests '*OneTest*'`; run the full suite once, at the end, before committing.
- **Never combine heavy tasks in one invocation** (e.g. full test suite + assemble) and never start a second Gradle run while one is going.
- Don't raise the limits in `gradle.properties` / `testOptions` (Gradle heap 1280m, one Robolectric fork with 1024m, serial GC, `ActiveProcessorCount=2`). If something runs out of memory, split the run instead.
