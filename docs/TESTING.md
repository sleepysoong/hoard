# Behavior-first verification

A green report is evidence for the behaviors its oracles check, not evidence that
all edge cases were covered. Do not use test counts as a quality claim.

## Release evidence

The release gate uses the production app on an Android emulator and a disposable,
real SSH + headful Chrome/CDP + password-authenticated VNC fixture. No production
relay or browser extension is added. Host keys are pinned and secrets use Android
Keystore in the app, including in these flows.

| User-visible failure | Independent oracle | Evidence |
| --- | --- | --- |
| Preview fits an image but Chrome still opens at the desktop's old aspect | First-navigation VNC geometry; actual outer window vs measured Android image area, portrait and landscape | `ui-initial-chrome-*`, `ui-fitted-chrome-*`, `VIEWPORT` log |
| AI selects a tab but preview shows another | Authored pages have different solid colors; assert rendered pixels independently of CDP state | `ui-red-tab-*`, `ui-blue-tab-*`, `ai-visible-tab-*` |
| Touch looks successful but edits the wrong place | Scaled touch and the viewer's input button must update the actual page input returned by CDP | Native browser UI flow + page state |
| Dialog falls back to flat controls or samples another window | Hold a real button, assert visible press deformation; capture the dialog's local rendering | `ui-liquid-button-held-*`, portrait/landscape captures |
| An idle automatic turn silently stops a goal | Several plain responses, then real `write_file`/`read_file`; assert filesystem contents, read output in next request and settled goal | Native chat transcript |
| Upgrade leaves an old suppressed goal parked | Seed the legacy persisted marker and invoke the same recovery used at app startup | Native goal transcript |
| User stop races automatic requeue | Hold an actual HTTP response, use the ViewModel stop path, release the response, assert paused state and no new request | WorkManager + native goal transcript |
| Hidden viewer still consumes frames or owns Chrome | Background and dismiss the actual viewer; verify VNC reception stops and CDP regains control | Native UI flow |

Artifacts are in `hoard-native-smoke`; native screenshots are reconstructed from
logcat so APK uninstall cannot erase them. Screenshots alone are **manual visual
evidence**, not automated proof of appearance. Review the captures as well as the
assertions. The emulator fixture does not validate the user's VPS or physical phone.
The browser UI fixture pre-grants notification/all-files permissions so the unrelated
first-launch permission screen cannot cover the viewer. It does not validate that screen.

## Why smaller tests remain

- Transport fault injection (`VncTransportFlowTest`) can deterministically exercise
  malformed packets, cancellation and stalled I/O that an ordinary real server
  rarely produces. It is not evidence of a real network connection.
- Host flow tests cover ordering, retries, scheduling, permissions and persistence
  cheaply. Their fake router is a wire fixture, **not** proof of model judgment.
- `RealSleepyrouterTest` checks the real router contract; a fake-only test once hid
  an incompatible streaming error.
- Authority, path containment, redirects, archive trust and cron/DST checks have
  independent safety/time-boundary expectations. Keep them when their failure is
  actionable, even if they are not Android UI tests.

## Audit of this change

- Removed `wallTimeBudget`: it only called a function that always returned null.
  The unused production stub was removed too.
- Removed `budgetAndContinuationDecisions`: it repeated evaluator branches already
  exercised through workers. Kept lifecycle/authority and cron/scheduler scenarios.
- Replaced the idle-turn suppression scenario with actual file creation and reading,
  with no additional user message. The slash-command UI test now stops an active goal
  explicitly instead of relying on the retired idle guard.
- Strengthened native browser captures with pixel assertions and added the missing
  production viewer → real page path. Raw VNC input tests alone missed UI mapping.
- Reviewed token estimates, tool schema/permissions, settings wiring, worker failures
  and popup captures. They do not copy production source, but exact module-order
  checks and screenshot-only cases are weaker evidence than behavioral outcomes.
  Do not describe them as full coverage or tokenizer accuracy.

This is a scoped audit, not a certification of every existing test. Do not delete
useful fault/security tests merely to lower the count. Before adding another test,
name the observable failure, the independent result, and why existing scenarios
cannot catch it. Prefer extending a realistic flow over another micro-test.

## Oracle checks

Use the manual **Tests** workflow with `native_oracles` enabled to run the native
flows and deliberately break window fitting, touch mapping and idle continuation
in the disposable CI checkout. Each mutation must compile and fail for its expected
behavioral reason; a build failure or missing fixture does not count. Results are
written to `native-smoke/oracles/summary.json` with per-mutation XML and logs.

Local builds must use `scripts/gradlew-lowspec.sh`; do not run the full suite or an
emulator on the low-memory development server.
