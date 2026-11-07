# AI–human collaboration log

Source for section 5 of the Stage 2 report ("AI tools and collaboration log"). Add an entry when
an interaction is worth showing, not for every prompt. Good entries show both sides: what the AI
produced and what you reviewed, corrected, rejected or redesigned. Run `/log` in Claude Code to
draft one, then rewrite the "Human contribution" part in your own words.

## Tools and models

The report must give each tool's model, what it was used for, and its total token and money cost.
Fill in the totals at the end of Stage 2. Claude Code keeps its transcripts (with token counts) in
`~/.claude/projects/`, so the totals can be counted from there. The claude.ai app doesn't show
token counts, so say so for that row.

| Tool | Model(s) | Used for | Tokens | Money |
|---|---|---|---|---|
| Claude (claude.ai app) | *check the model shown in the app* | Stage 2 planning, Tika 4.1.0 testing, project setup files, test corpus | Not shown by the app | Claude Pro subscription |
| Claude Code (CLI) | | | | |
| OpenRouter (used by the app itself, not a development tool) | | Cloud model for the agent | From the OpenRouter activity page | |

## Entries

Template:

```
### YYYY-MM-DD: short title
- **Tool / model:**
- **Task:** what you were trying to do
- **Input / instruction:** what you asked
- **AI contribution:** what it generated, suggested, changed or diagnosed
- **Human contribution:** what you reviewed, corrected, redesigned, rejected or improved
- **Outcome:** what ended up in the project (commit link)
```

The entries below were drafted from the planning conversation in the claude.ai app. Edit them in
your own words before they go in the report.

### 2026-10-16: Testing Tika 4.1.0 before choosing it
- **Tool / model:** Claude (claude.ai app)
- **Task:** decide whether Tika 4.1.0's forked parser is reliable enough for `IsolatedTextExtractor`.
- **Input / instruction:** asked Claude to stop guessing and test Tika itself; supplied the
  official `tika-app-4.1.0.zip` because the AI's environment couldn't download it.
- **AI contribution:** generated sample documents and a probe program; tested page and slide
  markers, OCR on/off, crashes, timeouts, cancellation, locale problems and Gradle tests. Found that
  `isSuccess()` is true for unreadable files and that PowerPoint slides come out in the wrong order,
  and wrote a working reference extractor that handles both.
- **Human contribution:** *(your words)* pushed for real tests instead of claims, provided the
  release, decided to keep Tika 4.1.0.
- **Outcome:** `docs/notes/tika/` (findings and reference extractor).

### 2026-10-18: Rejecting the AI's IDE-integration recommendations
- **Tool / model:** Claude (claude.ai app)
- **Task:** choose how Claude Code connects to IntelliJ IDEA.
- **Input / instruction:** asked for the development-environment plan.
- **AI contribution:** recommended the Claude Code JetBrains plugin, then IntelliJ's built-in MCP
  server, without checking either one's maintenance or reviews.
- **Human contribution:** *(your words)* checked the marketplace pages, rejected both because of
  poor reviews, and chose the third-party IDE Index MCP Server (and Debugger MCP Server) instead.
- **Outcome:** setup uses IDE Index MCP Server; the AI now checks maintenance and reviews before
  recommending a tool.

### 2026-10-21: Replanning for the November 10 deadline
- **Tool / model:** Claude (claude.ai app)
- **Task:** fit Stage 2 into the earlier deadline from the Stage 2 instructions.
- **Input / instruction:** shared the Stage 2 PDF, the Stage 1 feedback and the other courses'
  deadlines.
- **AI contribution:** pointed out the new requirements (weekly commits, demo videos, cost
  reporting, `.env`), split the features into tiers, and proposed a timeline.
- **Human contribution:** *(your words)* proposed moving testing and polish to Stage 3 and relying
  on AI for most of the code while supervising the design.
- **Outcome:** scope tiers and timeline in `docs/notes/handoff.md`.

### 2026-10-26: Trying the search on real documents
- **Tool / model:** Claude Code / Claude Fable 5.1
- **Task:** understand the output of `findany search resume` and `findany show --highlight` on
  the author's own Documents folder after Slice 1 landed.
- **Input / instruction:** pasted both outputs and asked what the line under each result was,
  why a job posting matched, why `show` printed all three pages, and why links repeated. Allowed
  the AI to inspect one PDF.
- **AI contribution:** explained that the snippet is the best-scoring chunk, which for a file
  matched by name only is just its first chunk (template boilerplate for `.typ` files); that the
  posting matched because page 3 says "resume"; that `show` prints the whole file by design (F11);
  and, after decompressing the PDF, that the repeated links are 79 link annotations the browser
  wrote and PDFBox appends to the page text. Proposed fixes for each.
- **Human contribution:** decided none of it was a priority, asked for a `TODO.md` instead of
  changes, and set two working rules: prepare one commit at a time without committing, and never
  push unasked. *(edit in your own words)*
- **Outcome:** `TODO.md` (four items) and the rules in `AGENTS.md`,
  [d8921e9](https://github.com/amarz45/find-anything/commit/d8921e9c11a97d45f93de941e2f74e031eb83228).

### 2026-10-26: Diagnosing the Windows CI timeout
- **Tool / model:** Claude Code / Claude Fable 5.1
- **Task:** find out why `FindAnythingServiceTest` timed out after 60 s on the Windows CI runner
  while taking 3.5 s on Linux.
- **Input / instruction:** pasted the failing CI log; later reported that the next run "happened
  to pass" and asked what to show.
- **AI contribution:** read the uploaded report and found it said nothing useful (the indexer's
  per-file lines were below the WARN level), so it first made the failure diagnosable: a test-only
  log config at INFO with timestamps, a timeout message carrying the index status, a longer wait,
  and uploading the reports on passing runs too. The next report showed one file,
  `scanned-receipt.pdf`, taking 60 s. The AI traced that to PDFBox scanning every system font the
  first time it meets a non-embedded font, confirmed it locally with an empty cache folder, and
  moved the cache into the app's data folder so an unwritable home folder can't make the scan
  repeat at every child restart. It first floated the Cp1252 warning and a "stuck child" as
  causes before the timestamps ruled them out.
- **Human contribution:** reviewed and committed each step separately, asked whether IDE
  warnings in the test (sleep in a loop, `getFirst()`) mattered, and chose to push and wait for
  data rather than guess. *(edit in your own words)*
- **Outcome:** [4db5034](https://github.com/amarz45/find-anything/commit/4db50340110ffd349bc29bdd0183b411d1c96516),
  [5d7b3cd](https://github.com/amarz45/find-anything/commit/5d7b3cda907ac3074e743d9c1b24c5a7671d871e),
  [637839d](https://github.com/amarz45/find-anything/commit/637839dcab5862074db335594af0cd6de3ebbd49);
  finding recorded in `docs/notes/tika/findings.md`. CI green on both runners.

### 2026-11-07: The agent loop, `findany ask`, and the paused-folder gap
- **Tool / model:** Claude Code / Claude Fable 5.1
- **Task:** finish Slice 2 of the agent: `AgentController` (the loop from SD03), `ask()` in the
  facade with the instant-search fallback, and the `findany ask` command; then run the slice's
  check ("When does my lease end?") against a real model.
- **Input / instruction:** "Work on the next item" for each piece, with the one-commit-at-a-time
  rule; "Why does only the fallback skip them?" after reading the fallback code; "Fix it"; "Commit
  both, don't work on the next item yet"; "How would I run it myself? Where do I put my API key?";
  pasted the output of the real run; "Commit, then push and make a pull request".
- **AI contribution:** wrote `AgentController` with its endings (verified answer, one reformat
  request on a malformed reply, step limit, unverified reply, model failure), `ask()` with the
  fallback that keeps the conversation and saves it, and `findany ask` with `--continue`,
  `--verbose` and `--json`, with 23 tests. Its first fallback re-added the user's message after
  tool steps; it caught that itself. It did not notice that only the fallback skipped paused
  folders until asked: `SearchTool` and `ReadExcerptTool` searched and read files in paused or
  unavailable folders, so the agent could answer from them. On "Fix it" it moved the filter into
  `SearchEngine` and added the check to `ReadExcerptTool`, so every search path honours the
  folder state. It also wrote "checked on a cloud model" in the docs before knowing which backend
  the run used, and corrected it to OpenRouter after being told. Explained where the key goes
  (`.env` in `FINDANY_HOME` or the working directory, never in `settings.json`) without reading
  `.env`.
- **Human contribution:** reviewed each diff and committed it; spotted the paused-folder gap from
  the fallback code and asked for it to be fixed in the agent's tools; stopped the AI from
  starting the next item before the fix was committed; ran the lease questions with an OpenRouter
  model and confirmed the backend; chose to test Ollama later; asked for the push and pull
  request. *(edit in your own words)*
- **Outcome:** [6ce1c9a](https://github.com/amarz45/find-anything/commit/6ce1c9a89fa84d280217caecc0b624a531257d0f),
  [308a42c](https://github.com/amarz45/find-anything/commit/308a42cef1a7174bac3aea1f39d8611a0adf6d04),
  [6eda059](https://github.com/amarz45/find-anything/commit/6eda05959a0ef27462a32fbce530058ed2e54c84),
  [b15008f](https://github.com/amarz45/find-anything/commit/b15008fba12544758bb182d1d86ac1e70f2b3583),
  docs in [f9b09c5](https://github.com/amarz45/find-anything/commit/f9b09c50e3e0b68c8e96b1077e3a03be56f30d15);
  merged as PR #6 ([1f75609](https://github.com/amarz45/find-anything/commit/1f7560923d9cc887420b8f5825e1299b3fc6b40e)).
  The lease question was answered and cited to page 2 of the lease, verified, with the
  prompt-injection note ignored; the follow-up on the rent gave both figures, verified. Ollama is
  not checked yet.
