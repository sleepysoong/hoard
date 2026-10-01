# Agent skills

Hoard's skill integration is designed to load portable **Agent Skills** bundles and
compatible Claude Code skill features with any router model that supports tools.
It is not a Claude Code installation or a full Claude Code plugin runtime.

> The matrix below reflects what the code actually implements. **Supported** means it is
> wired through the worker, engine and tool registry; **Unsupported** means the field is
> parsed and reported, never approximated silently.

## Install and use

The entry point is **도구 → 스킬**: install, inspect, enable/disable, review code
trust, update and remove installed bundles. This manages skills, not toggles for the
app's normal device tools.

Accepted sources: a GitHub repository or `/tree/<ref>/<path>` URL, a ZIP / `.skill`
archive, and skills you already have in the workspace (`workspace/.claude/skills/…`
and nested projects, discovered live). A `.skill` file is a ZIP skill bundle, not an
executable app. Keep the whole skill directory:

```text
my-skill/
├── SKILL.md
├── scripts/       # Optional executable helpers
├── references/    # Optional documentation, read on demand
└── assets/        # Optional templates/data; other supporting files are allowed
```

Discovery checks `SKILL.md` at the selected path first, then `.claude/skills`,
`skills`, directly wrapped skill folders, plugin manifests (`.claude-plugin/plugin.json`,
including their `skills`/`commands` paths and a plugin-root `SKILL.md`) and legacy
`.claude/commands/*.md`. Generated copies such as `.openclaw/skills/` are skipped.
Plugin skills install under the whole plugin directory and are named
`plugin:skill`, and the plugin's own resources stay reachable through
`${CLAUDE_PLUGIN_ROOT}`. Everything is downloaded as data: importing a repository
never activates a script, hook or dependency installer. Use a commit-pinned URL
when reproducibility matters; a branch such as `main` can change, and Update
always re-downloads and resets code trust.

### Ponytail: instruction-only skills

Import the repository:

```text
https://github.com/DietrichGebert/ponytail
```

Or select just its main skill:

```text
https://github.com/DietrichGebert/ponytail/tree/main/skills/ponytail
```

The canonical `skills/` directory contains six skills: `ponytail`,
`ponytail-audit`, `ponytail-debt`, `ponytail-gain`, `ponytail-help`, and
`ponytail-review`. Do not import the generated `.openclaw/skills/` copies again.
These skills need no Node runtime or API key. Try:

```text
/ponytail lite
Simplify this function without adding dependencies.
```

The main skill asks to remain active until “stop ponytail” / “normal mode”. Its
rendered instructions are stored per session, so they survive later messages and
app restarts; the model still interprets that requested mode. The help/gain/report
skills are one-shot tasks, not always-on modes. Hoard does not get Ponytail's
plugin-level startup activation, Node hooks, configuration flags, or `/plugin`
commands merely by importing its `skills/` directory — those are separate host
integrations. Because the bundle is instruction-only, no code trust is needed.

### Binance: bundled Node helper

Import only the public leaderboard skill first:

```text
https://github.com/binance/binance-skills-hub/tree/main/skills/binance-web3/crypto-market-rank
```

It contains `SKILL.md`, `references/cli.md`, and `scripts/cli.mjs`. Try:

```text
/crypto-market-rank Show five trending BSC tokens, with liquidity and 24h change.
```

This skill uses public Binance Web3 endpoints and needs no API key. It does need
Termux, Node (the upstream helper specifies Node >= 22), outbound HTTPS, and a
Termux-readable copy of the bundle. The model should read `references/cli.md`
before selecting parameters, then run the bundled CLI, not substitute a GET-only
`web_fetch` for a JSON POST.

Install prerequisites yourself in Termux, after its initial setup:

```bash
pkg install nodejs-lts curl jq
# Only for skills that use Python:
pkg install python
```

Enable Termux's `allow-external-apps=true` setting and grant Hoard its
`com.termux.permission.RUN_COMMAND` permission (see README's `termux_exec` setup).
Termux is not needed for instruction-only skills. No interpreter, npm packages,
API keys, wallet session, or trading credentials are bundled by Hoard.

This skill bundles a script, so enabling **코드 실행 신뢰** in 도구 is required before
the bundle can be mirrored into Termux. The activation result then reports a real
`runtime_dir`; in the example below `SKILL_DIR` is that Termux mirror path, not the
app's private directory and not the literal upstream `<skill-dir>` (which the
upstream file never defines):

```bash
node "$SKILL_DIR/scripts/cli.mjs" token-rank \
  '{"rankType":10,"chainId":"56","page":1,"size":5}' > "$HOME/market-rank.json"
jq '{code, success, data: (.data | keys)}' "$HOME/market-rank.json"
```

Read the reference to select the response fields needed for the answer. Even five
items can exceed the output cap; save the response to a file and filter or page it
rather than treating a truncated prefix as a complete leaderboard. Skill shell
output is capped at 16 KB per stream and truncation is an error, not a silent
prefix. Several upstream Binance CLIs have a main-module guard that silently does
nothing when invoked via a symlink or a path containing spaces or non-ASCII
characters: the mirror is a real, digest-named ASCII path for that reason. Do not
silently patch vendor code. Regional blocks, upstream API changes and rate limits
can still prevent a call.

Other skills in this repository can sign transactions, trade, transfer money, or
publish posts. They have different credentials and dependencies; importing the
hub is not permission to do any of those things. Nested `metadata.openclaw.install`
instructions and `metadata.requires.skills` are metadata, not automatic installers
or a working dependency resolver.

## Compatibility matrix

The reference is the live [Claude Code skills documentation](https://code.claude.com/docs/en/skills),
checked 2026-10-01. Its extensions are broader than the six standard fields
(`name`, `description`, `license`, `compatibility`, `metadata`, `allowed-tools`).
Claude.ai / Skills API uploads can reject Claude Code-only frontmatter even when
Claude Code itself accepts it.

| Feature / field | Hoard integration status and contract |
| --- | --- |
| `SKILL.md` + supporting files | **Supported:** scripts/references/assets and relative links are retained; the activation result reports a `base_directory` (workspace-relative) and the model reads resources on demand. |
| YAML frontmatter | **Supported:** bounded SnakeYAML `SafeConstructor` (no tags, no recursive keys, alias/codepoint/depth limits), including `>`/`|` scalars, lists and maps. A malformed document fails discovery and is reported; it never grants a privilege. |
| `name`, `description`, `when_to_use` | **Supported:** named catalog; directory/body fallbacks; `when_to_use` is appended to the description, XML-escaped and capped at 1,536 chars in the 16 KB listing budget. |
| `license`, `compatibility`, `metadata` | **Supported:** retained and shown in 도구. `compatibility` is informational text passed to the model; nothing is installed to satisfy it. Unknown fields are preserved, never guessed. |
| `argument-hint` | **Supported:** shown in the slash menu label only. |
| `disable-model-invocation` | **Supported:** excluded from the model catalog and rejected for model/scheduled invocation; explicit user invocation still works. |
| `user-invocable: false` | **Supported:** absent from the slash menu and rejected for direct invocation; model invocation still works. |
| User `/name args` and model `skill` invocation | **Supported:** one rendering path (`SkillRuntime.activate`), user-invoked from the answered message, model-invoked through the `skill` tool. Only installed, enabled names resolve. A slash command that is not a skill is sent as an ordinary prompt. |
| `arguments`, `$ARGUMENTS`, `$ARGUMENTS[N]`, `$N`, `$name` | **Supported:** named string/list declarations, zero-based indices, shell-style quoted splitting. `ARGUMENTS: …` is appended only when no placeholder consumed arguments; `\$1` escapes. |
| `${CLAUDE_SKILL_DIR}`, `${CLAUDE_PROJECT_DIR}`, `${CLAUDE_SESSION_ID}`, `${CLAUDE_EFFORT}`, `${CLAUDE_PLUGIN_ROOT}`, `${CLAUDE_PLUGIN_DATA}` | **Supported:** mapped to the Termux mirror, workspace, session, effort and plugin paths, and labelled as such. `CLAUDE_PROJECT_DIR` is a warning, not a Termux-accessible checkout. |
| Session activation persistence | **Supported:** the rendered body is stored per session outside the workspace, restored on later turns and app restarts, and labelled as standing guidance. Branching copies it; deleting the session clears it. A bounded 128 KB × 16-skill budget is enforced. |
| Inline `` !`command` `` and fenced ` ```! ` context | **Supported with code trust:** rendered once through Termux before the request, only from the original template (never from arguments or command output), 8-command limit, 120 s bound. Untrusted or unavailable execution fails the invocation explicitly. |
| `shell: bash` | **Supported:** Termux bash with bounded commands, output capped at 16 KB, and an explicit `Termux runtime_dir`. |
| `shell: powershell` | **Unsupported:** rejected before any shell runs; there is no PowerShell runtime. |
| Termux mirror | **Supported:** the bundle is copied to a fresh digest-named directory under `$HOME/.hoard/skills/` (never reusing a mutable tree), ASCII and space-free so upstream `import.meta` guards work, and re-synced from the trusted bundle before every automatic hook run. |
| `allowed-tools` | **Supported as advisory:** preapproval, **not a tool allowlist**, exactly like Claude Code. Hoard has no per-call approval dialog, so it grants nothing and hides nothing; the activation result labels it advisory. |
| `disallowed-tools` | **Supported:** mapped to Hoard tools for the invoking turn only. Both the offered schemas and actual dispatch are filtered, including hook-rewritten arguments, and tool calls run serially while a restriction is active. Cleared on the next user message. Argument-scoped Bash rules deny shell compositions rather than pretending to parse them; see below. |
| `model` / `inherit` | **Supported:** validated against the router catalog and used for the rest of the turn or the child run; the session model is untouched. `sonnet`/`opus`/`haiku` resolve only when exactly one catalog model matches, and ambiguity or absence is an error, never a silent fallback. |
| `effort`, `${CLAUDE_EFFORT}` | **Supported:** forwarded as Responses `reasoning.effort` for the invoking turn (`low`, `medium`, `high`, `xhigh`, `max`; anything else is rejected). Acceptance is provider-dependent and the activation result says so. |
| `context: fork`, `agent`, `background` | **Supported:** a child session with no parent history, a 10-minute bound, mapped `Explore`/`Plan` read-only policies and workspace `.claude/agents/<name>.md` support. `background: true` (default) queues the child through WorkManager; `background: false` waits. Results are delivered durably (`SkillForkDelivery`) and readable with `skill_task`. Recursive `skill`, `schedule` and `schedule_wakeup` calls are removed in a child. |
| `hooks` | **Partial, explicit:** `PreToolUse`, `PostToolUse`, `PostToolUseFailure`, `PostToolBatch`, `UserPromptSubmit`, `UserPromptExpansion`, `Stop` and `StopFailure` with synchronous bash `command` handlers, matchers, `if`, `timeout` and `once`. Hooks register at invocation, persist for the session and re-verify the reviewed bundle digest before every run. `async`, `asyncRewake`, `http`, `mcp_tool`, `prompt`, `agent`, PowerShell and desktop-only events are reported as unsupported. |
| `paths` | **Supported for file tools:** a matching glob activates the skill when `read_file`/`write_file`/`edit_file`/`glob`/`grep` touches a matching workspace file, relative to the project's `.claude` directory. Nested workspace projects are qualified (`apps/web:name`). |
| Claude account sync / enterprise locations | **Unavailable:** no Anthropic account sync, managed-directory discovery, organization policy distribution, or desktop home-directory inheritance. Import a bundle explicitly. |
| Claude built-ins, plugins, MCP and desktop subagents | **Unavailable as a runtime:** importing `skills/` retains a plugin bundle's files (hooks, `agents/`, shared resources) and loads custom `.claude/agents/<name>.md` definitions for `agent:`, but it does not install `/plugin`, `/loop`, bundled skills, MCP servers, agent teams, LSP servers or agent *implementations*. Plugin hook files are data, not skill frontmatter hooks. |
| Skill content lifecycle / compaction | **Adaptation:** Hoard trims history to the session context limit instead of compacting, and activated instructions are re-sent while still installed and enabled. Removed, disabled or code-changed skills drop out of context. |

### Argument and dynamic-context caveats

* `$0` is the first argument, not the skill name. `/demo "hello world" second`
  yields `$0 = hello world`, `$1 = second`; `$ARGUMENTS` keeps the original string.
* Missing indexed placeholders stay literal; missing declared named arguments
  become empty strings. A single `\$1` escapes it; `\\$1` still expands. Inserted
  argument values are not recursively expanded as argument placeholders.
* Claude recognizes inline injection only at line start/after whitespace, not
  `` KEY=!`cmd` ``. Command output is plain text, never another preprocessing pass.
  Hoard parses injection and arguments from the **original template only**, and an
  escaped placeholder inside a dynamic command is refused rather than guessed.
* Inside a dynamic command, arguments and `${CLAUDE_*}` values are bound to shell
  variables and referenced safely, so a value can never become new syntax. A
  backslash-escaped placeholder in that context is rejected instead of silently
  expanding.
* Hoard's injected commands run with the Termux mirror as cwd, not a persistent
  desktop shell. There is no shared shell, no `cd` persistence and no inherited
  environment between one-shot commands.
* Claude never prompts during preprocessing. Hoard's code-trust gate is a mobile
  policy applied per bundle, not a reproduction of that permission classifier.
* A scheduled run is an unattended context: it never lets a model invoke a
  user-only skill, and `skills` follows the schedule's permission snapshot, so a
  schedule created without Termux cannot gain shell access through a skill.

## Security and trust

**Import is data-only and code trust starts off.** Nothing runs during discovery or
installation: no `!` commands, hooks, bundled scripts, `npm install`, or metadata
installer snippets. Trust is granted per bundle in 도구 → 스킬 → 내용 및 권한 and
is tied to the digest of the reviewed code tree, so any later edit revokes it.

Enabling a skill is not the same as enabling code: an instruction-only skill needs
no trust at all, while enabling trust lets the skill's own `!` commands and hooks
run automatically. Both are explicit, reversible decisions.

Skills are third-party instructions and code, not harmless metadata. They can
steer normal file, browser, network, and Termux tools; trusted scripts/hooks can
read or modify everything the Termux user can access and send data externally.
Code trust gates automatic skill execution, **not an OS sandbox for normal tool
calls**. Enabling a skill does not authorize trades, purchases, posts, credential
access, or destructive commands beyond the user's request. All normal tools
remain available under their existing rules unless an implemented
`disallowed-tools` restriction removes them; skills are model-independent.

What the code enforces today:

* ZIP extraction validates the **central directory** before writing anything:
  traversal/absolute/backslash paths, duplicate names, symlink and special-file
  modes, encryption, ZIP64, 10,000-entry and 128 MiB expansion limits, a 200×
  compression-ratio cap and CRC verification. GitHub installs are pinned to a
  resolved commit, allow redirects only to known GitHub hosts, and refuse a
  download that exceeds 64 MiB compressed.
* Installation is staged outside the workspace and committed with atomic renames
  plus rollback; an existing command is never overwritten, so Update is an
  explicit, separate action. A download is new code and always resets code trust.
* The Termux mirror is never reused. Each preparation and each automatic hook run
  extracts a fresh digest-named tree and verifies its SHA-256, so a modified
  mirror cannot be executed under an earlier trust decision. Mirror contents are
  `umask 077` and the plugin's own data directory is separate and persistent.
* Model-writable files cannot smuggle trusted code: enabling trust records the
  digest of the whole code tree, and any edit to `SKILL.md`, hooks, scripts or any
  plugin resource revokes it on the next refresh.
* A direct slash invocation is cached by message + skill + arguments + bundle
  digest, so a retry after a network failure cannot rerun dynamic commands or
  create a second fork. An invocation interrupted mid-flight is reported as
  possibly having run rather than replayed automatically.
* Fork results are stored outside the workspace, keyed by session, with bounded
  size and count; a corrupt registry fails closed instead of being treated as
  empty.

Never bundle keys. Prefer user-owned Termux credential configuration; don't copy
secrets into a skill prompt, archive, or Termux mirror. Shell tools are not
covered by `PageFetcher`'s public-IP filter; a trusted shell script can make
network requests of its own, including to loopback and LAN addresses.

## Runtime integration contract

Code layout, one file per responsibility:

| File | Role |
| -- | -- |
| `skills/SkillDocument.kt` | Bounded safe YAML frontmatter parse; typed field accessors; command-name validation. |
| `skills/SkillStore.kt` | Registry, enablement, hash-backed code trust, workspace/`.claude` discovery, atomic install commit with rollback. |
| `skills/SkillInstaller.kt` | GitHub ref resolution + pinned archive download, ZIP central-directory validation, archive import, update. |
| `skills/SkillRuntime.kt` | Per-turn activation: preprocessing, substitutions, Termux mirroring, session persistence, hook execution, turn-scoped overrides. |
| `skills/SkillShell.kt` | Bounded tar/extract helper for the Termux mirror; no cached mutable trees. |
| `skills/SkillPathActivation.kt` | `paths`-scoped activation from file-tool calls. |
| `skills/SkillForkExecutor.kt` | `context: fork` child session, agent policies, foreground/background dispatch. |
| `skills/SkillForkDelivery.kt` | Durable child → parent result records for background forks. |
| `tools/SkillTool.kt`, `tools/SkillTaskTool.kt`, `tools/SkillToolPolicy.kt` | The `skill` and `skill_task` tools, and Claude → Hoard tool-name/argument matching. |

The `skills` ToolKit module offers `skill` (and `skill_task` outside scheduled
runs) through `ToolServices`. `ChatResponseWorker` builds the runtime once per
turn, activates a slash skill from the answered message, and passes the rendered
material into `ReplyRequest.skillContext`. `RouterAiEngine` re-reads the turn's
model/effort overrides for every tool round and runs the hook lifecycle.
Sleepyrouter still relays ordinary function calls; it does not install or run
skills, and no provider-specific skill API is required.

**Persistent rendered content** stays separate from a **turn-scoped execution
layer**. The latter (`turnSkills`) holds the model override, effort, advisory
preapproval and denied tools for skills activated *this* turn, so it clears at the
next user message; the rendered bodies and hook registrations persist. Restrictions
apply to both schema generation and dispatch, including hook-rewritten arguments.
`ReplyRequest.withSkillContext` reserves the activation budget before trimming old
turns, so skill content can push history out but is never silently cut itself, and
context that cannot fit is an explicit error. Restored material is labelled
“invoked earlier; standing guidance only”.

### Forks

`SkillForkExecutor` creates a real child session (`스킬 · /name`) with only the
rendered skill task as its prompt: no parent transcript, goals, todos or other
activated skills. Restrictions are flattened into the persisted child document, so
a restored worker keeps the same policy. `Explore`/`Plan` are read-only for real
(the read-only tool set is enforced, not just named), `general-purpose` is the
default, and a custom `agent` loads workspace `.claude/agents/<name>.md` with its
`model`/`tools`/`disallowedTools`. `skill`, `schedule` and `schedule_wakeup` are
removed in the child, so a fork cannot recurse. Both paths are bounded to 10
minutes; a timeout is reported, never retried as a fresh task.

Background forks register a durable `SkillForkDelivery` record before enqueue and
write the settled child reply into it when the worker finishes (once, not on retry
backoff). The next parent turn receives the saved results as user-level context,
and `skill_task` can read, cancel or dismiss them. Foreground forks stream into the
child session's bubble and return a bounded summary. Isolation is of **model
context**, not files: two runs can still edit the same workspace, and Android
WorkManager/Doze can delay a background child.

### Hooks: what runs, and where

Hook execution lives in `SkillRuntime.hook` and is called only from the worker and
engine boundaries, never from the skill tool. Skill-frontmatter hooks register
**when invoked**, persist for that session, and are not retroactive. Plugin
`hooks/hooks.json` and repository hook files are retained as data and do not become
skill frontmatter. Every run re-verifies the reviewed bundle digest and refuses to
run against a changed bundle.

| Event | Hoard behaviour |
| --- | --- |
| `UserPromptSubmit` | Runs on a typed user prompt before the request. Its result is attached as user-level context; `decision: block` fails the turn. |
| `UserPromptExpansion` | Runs for a direct `/skill` before expansion, matched on `command_name`. |
| `PreToolUse` | Runs inside `ToolRegistry.execute` before execution. `permissionDecision: deny` and `continue: false` block the call (as a tool error, not a retry), and `updatedInput` is applied and then rechecked against `disallowed-tools` and tool schemas. Exit 2 blocks. |
| `PostToolUse` | After a successful call. `updatedToolOutput` replaces the model-visible output; `continue: false` ends the turn. A hook failure here is reported as a warning and never replays the tool. |
| `PostToolUseFailure` | Only after the tool actually started and failed. Argument validation, unknown tools, permission denial and cancellation are not execution failures. |
| `PostToolBatch` | Once per completed batch, before the next model round. `decision: block` stops the loop. |
| `Stop` | After a normal answer. `decision: block` continues the loop once (bounded, with `stop_hook_active` exposed); `continue: false` stops. |
| `StopFailure` | Runs on a router/API failure and cannot change the outcome. |

Unsupported events (`SessionStart`, `SessionEnd`, `Notification`,
`InstructionsLoaded`, `ConfigChange`, `FileChanged`, `SubagentStart/Stop`,
`PreCompact`, `PreModelSwitch`, `MessageDisplay`, `Setup`, `Task*`, `Worktree*`,
`Elicitation*`) and unsupported handlers (`async`, `asyncRewake`, `http`,
`mcp_tool`, `prompt`, `agent`, PowerShell) produce an explicit warning at
invocation instead of being ignored or faked. Hook `matcher` keeps Claude aliases
(`Bash`, `Edit`, …) in the hook payload while dispatching Hoard tool names, and
`updatedInput` is mapped back (`file_path` → `path`).

Hook compatibility also requires handlers and result schemas, not just event names:

Notes on the implemented subset, kept here because the differences are easy to
mistake for full compatibility:

* Only synchronous `command` handlers run. `http`, `mcp_tool`, `prompt`, `agent`,
  `async`, `asyncRewake` and PowerShell handlers are reported as unsupported at
  invocation rather than silently dropped. `statusMessage` is not surfaced.
* `if` is evaluated on tool events with the same matcher used by `disallowed-tools`,
  so it inherits that matcher's conservative behaviour for composed shell
  commands.
* `args` (including an empty list) is passed as a safely quoted argv vector; shell
  form is the default. Hook input is JSON on stdin with `session_id`,
  `hook_event_name`, `cwd` and the event fields. There is no desktop transcript
  path, so `transcript_path` is never fabricated.
* `hookSpecificOutput` is honoured for `permissionDecision`, `updatedInput` and
  `updatedToolOutput`; `defer` is not implemented. `additionalContext` and
  `systemMessage` are attached to the model/tool context as plain text.
* Exit 0 parses structured output; exit 2 blocks only where blocking exists
  (`PreToolUse`, `UserPromptSubmit`, `UserPromptExpansion`, `Stop`,
  `PostToolBatch`). Post-action events never undo or replay a completed tool.
* `once: true` removes a handler only after a successful run. `suppressOutput` and
  `terminalSequence` have no Android equivalent.

### Claude tool names on mobile

`SkillToolPolicy` maps both directions: `Read → read_file`, `Write → write_file`,
`Edit → edit_file`, `Glob → glob`, `Grep → grep`, `Bash → termux_exec`,
`WebFetch → web_fetch`, `WebSearch → web_search`, `Skill → skill`,
`TodoWrite → todo`, `Browser → browser_use`. Hook payloads keep the Claude name
and also expose `hoard_tool_name`/`hoard_tool_input`, and `file_path` is mapped
back to Hoard's `path`.

Argument-scoped Bash rules (`Bash(git:*)`, `Bash(rm *)`) are matched against the
whole command string, which cannot see subcommands inside `;`, `&&`, pipes or
command substitution. Hoard therefore **denies** a call whose command contains
shell composition or quoting characters when such a rule applies, instead of
pretending to be a shell sandbox. Whole-tool rules (`Bash`, `Read`) are exact.
Desktop `Agent`, `AskUserQuestion`, `ExitPlanMode`, `EndConversation`, notebook and
MCP tool names are unavailable. `allowed-tools` is never read as "disable every
other tool".

## References

* [Claude Code skills](https://code.claude.com/docs/en/skills)
* [Claude Code hooks](https://code.claude.com/docs/en/hooks)
* [Agent Skills specification](https://agentskills.io/specification)
* [Ponytail](https://github.com/DietrichGebert/ponytail)
* [Binance Skills Hub](https://github.com/binance/binance-skills-hub)
