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
