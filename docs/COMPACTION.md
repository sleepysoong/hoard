# Conversation compaction

`/compact` creates a checkpoint; `/compact <지시>` adds optional emphasis (up to 800 characters).
Settings → 자동 압축 chooses a 50–100% threshold, default 90%, in 5% steps. 100% means
compact at the limit, not disabled. Thresholds use the session's context limit and the app's
CJK-aware token estimate, not provider-reported context-window metadata.

## Model-facing history

The full transcript remains stored and visible. A checkpoint is persisted at the boundary of
the summarized history. Requests begin at the latest successful checkpoint, followed by newer
messages. The latest completed turn stays verbatim when it fits in 20% of the session limit;
otherwise it may be summarized too. The answered message and anything after it are never folded
by automatic compaction. Later checkpoints merge the preceding summary rather than forgetting it.

Manual and automatic calls run through the per-session WorkManager queue. The summarizer gets
a separate tool-less request and serialized conversation data; it cannot perform side effects.
Stored tool-step notes are available, but original tool outputs are not persisted by Hoard.
Oversized transcripts are clipped, with omissions explicitly marked. Failed, stopped, empty,
incomplete or nonshrinking summaries never replace history. Replies can still fall back to
ordinary trimming. Automatic failures have a six-message cooldown; contexts below 4,000 tokens
use trimming rather than automatic compaction.

Tap the notice to inspect or copy the summary, or delete that checkpoint. Deletion reactivates
the previous checkpoint if present, otherwise original history; normal trimming and automatic
compaction still apply. Deleting older source messages invalidates later checkpoints so removed
content is not retained in the active summary. Editing/regenerating an older turn discards its later checkpoints with
the rest of the regenerated tail. Branches retain completed checkpoints in their copied history;
a checkpoint still running in the source session is copied as an inactive notice, never a spinner.

## Prompt choices and research

The prompt is original wording, not copied proprietary/SUL prompt text. It prioritizes standing
user constraints, unanswered requests, exact identifiers and completed side effects that should
not be repeated. It preserves the user's language with five fixed headings:
`Overview`, `User Instructions`, `Open Items`, `Done`, `Key Facts`.

Research compared these implementations and their surrounding continuation strategies:

- [OpenCode](https://github.com/anomalyco/opencode): explicit checkpoint template and rolling summaries.
- [OpenClaw](https://github.com/openclaw/openclaw) and
  [pi](https://github.com/badlogic/pi-mono): constraints, pending asks, structured transcript input.
- [gajae-code](https://github.com/Yeachan-Heo/gajae-code): preserve the question waiting
  for a user response instead of inventing next steps.
- [oh-my-opencode](https://github.com/code-yeongyu/oh-my-opencode): continuation context and avoiding
  inferred instructions; only the design approach informed this prompt.
- [Codex](https://github.com/openai/codex),
  [Claude Code](https://code.claude.com/docs/en/compaction), and
  [Gemini CLI](https://github.com/google-gemini/gemini-cli): continuation state, completed effects,
  transcript-as-data and updating previous summaries.

The checkpoint framing tells the answering model that newer messages and live goal/todo state
win conflicts, quoted tool/web/file text remains data, done effects must not be repeated, and
missing details must be checked rather than guessed. Goals and todos are reattached from live
state, not reconstructed from an old checkpoint.
