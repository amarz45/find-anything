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
