# AI–human collaboration log

Source for section 5 of the Stage 2 report ("AI tools and collaboration log"). Add an entry when
an interaction is worth showing, not for every prompt. Good entries show both sides: what the AI
produced and what you reviewed, corrected, rejected or redesigned. Run `/log` in Claude Code to
draft one.

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

The first entries were drafted from the planning conversation in the claude.ai app.

### 2026-10-16: Testing Tika 4.1.0 before choosing it
- **Tool / model:** Claude (claude.ai app)
- **Task:** decide whether Tika 4.1.0's forked parser is reliable enough for `IsolatedTextExtractor`.
- **Input / instruction:** asked Claude to stop guessing and test Tika itself; supplied the
  official `tika-app-4.1.0.zip` because the AI's environment couldn't download it.
- **AI contribution:** generated sample documents and a probe program; tested page and slide
  markers, OCR on/off, crashes, timeouts, cancellation, locale problems and Gradle tests. Found that
  `isSuccess()` is true for unreadable files and that PowerPoint slides come out in the wrong order,
  and wrote a working reference extractor that handles both.
- **Human contribution:** pushed for real tests instead of claims, provided the
  release, decided to keep Tika 4.1.0.
- **Outcome:** `docs/notes/tika/` (findings and reference extractor).

### 2026-10-18: Rejecting the AI's IDE-integration recommendations
- **Tool / model:** Claude (claude.ai app)
- **Task:** choose how Claude Code connects to IntelliJ IDEA.
- **Input / instruction:** asked for the development-environment plan.
- **AI contribution:** recommended the Claude Code JetBrains plugin, then IntelliJ's built-in MCP
  server, without checking either one's maintenance or reviews.
- **Human contribution:** checked the marketplace pages, rejected both because of
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
- **Human contribution:** proposed moving testing and polish to Stage 3 and relying
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
  push unasked.
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
  data rather than guess.
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
  request.
- **Outcome:** [6ce1c9a](https://github.com/amarz45/find-anything/commit/6ce1c9a89fa84d280217caecc0b624a531257d0f),
  [308a42c](https://github.com/amarz45/find-anything/commit/308a42cef1a7174bac3aea1f39d8611a0adf6d04),
  [6eda059](https://github.com/amarz45/find-anything/commit/6eda05959a0ef27462a32fbce530058ed2e54c84),
  [b15008f](https://github.com/amarz45/find-anything/commit/b15008fba12544758bb182d1d86ac1e70f2b3583),
  docs in [f9b09c5](https://github.com/amarz45/find-anything/commit/f9b09c50e3e0b68c8e96b1077e3a03be56f30d15);
  merged as PR #6 ([1f75609](https://github.com/amarz45/find-anything/commit/1f7560923d9cc887420b8f5825e1299b3fc6b40e)).
  The lease question was answered and cited to page 2 of the lease, verified, with the
  prompt-injection note ignored; the follow-up on the rent gave both figures, verified. Ollama is
  not checked yet.

### 2026-11-08: The GUI, and what testing it on real documents turned up
- **Tool / model:** Claude Code / Claude Fable 5.1
- **Task:** build the JavaFX GUI from the design (`MainWindow`, `ResultsView`, `DetailPane`,
  `GuiController`, `findany gui`), then act on what Amar found when he used it on his own
  documents.
- **Input / instruction:** "Create a plan for the GUI" and its scope choices; reports from
  testing the GUI on real documents, with a screenshot and permission to list one folder's file
  names but not read any contents; "Don't fix before we understand the cause. How shall we
  investigate?"; a sequence of questions on what a conversation stores and why; "Did the model
  read the file? Check."; the pasted output of a verbose CLI run.
- **AI contribution:** wrote the views and controller with headless toolkit tests, and found and
  fixed an `Indexer` bug the GUI's Observer exposed: listeners never heard "up to date" after a
  request made while the worker was idle. From the saved conversations' structure (roles, sizes,
  tool calls; no file contents) it diagnosed the follow-up failure: the first exchange was 80,000
  characters of search results, twice the history limit, so trimming dropped it whole and saved
  the result. It implemented the approved change: requests and answers are kept across
  exchanges, tool calls and results only for the exchange in progress and never saved, with the
  trace for `ask --json` preserved. It confirmed, by lengths only, that a small access-code file
  had gone to the cloud model inside a search result's best passage. Its investigation of answers
  that repeat their citation entries ran 53 corpus requests, ruled out a reformat step, the
  model, context size and the file-listing wording, and reproduced it once; its first
  explanation (the reformat request) was wrong. Other mistakes Amar caught: it filed a serious
  bug as a low-priority TODO, offered two "variants" that didn't address the two details it had
  itself raised, and blurred the app's own 40,000-character threshold with the model's context
  window.
- **Human contribution:** tested every commit in the real GUI on his own documents, which found
  what the tests had not: answers shown as raw Markdown, a follow-up that denied an earlier
  answer, and the first prompt gone after reopening a conversation. Offered a hypothesis for the
  follow-up (a narrowed index), gave a scoped permission (list the folder's names, never read
  contents), and judged the answer itself weak for not reading the folder's other files. Refused
  a fix until the cause was understood, and led the diagnosis with his questions: why one
  question was so expensive, whether the trimming was too harsh or too much was stored, what
  exactly is stored, why search results are saved at all and whether they go stale, how much
  dropping them would save, what the 40,000 limit is for, whether one exchange can exceed it,
  and how a request over the limit could still succeed. Set the scope of the fix (drop tool
  results from stored conversations for the cloud case now, the local-model case later),
  rejected the AI's two unclear variants and demanded one approach that handled both details,
  then approved it and verified it. Noticed the model's wording "I haven't shown its contents
  here", asked whether the file had been read, judged the designed redaction insufficient for
  such cases, and proposed a name-based sensitivity check with its limits. Ran the verbose CLI
  himself, which disproved the reformat hypothesis, approved the corpus experiments, and deferred
  the real-document comparison as tedious to reproduce.
- **Outcome:** [a443690](https://github.com/amarz45/find-anything/commit/a443690af7b6d02a702ab934710d285af618afab),
  [1474a97](https://github.com/amarz45/find-anything/commit/1474a9775fa83d5065f99e1ac4a07c203bce2ee8),
  [c0639cf](https://github.com/amarz45/find-anything/commit/c0639cfd6d6829867d11e6af9a661912c954226d),
  [7a01dd6](https://github.com/amarz45/find-anything/commit/7a01dd6910a49d4295b32520ca327718b0784e60),
  [fbb0b7e](https://github.com/amarz45/find-anything/commit/fbb0b7e044409cb1df8746ab8bad0b575cb7b494);
  merged as PR #7. Two entries in `docs/logs/test-failures.md`. The duplicated citation entries,
  the leak and the agent's folder blindness are in `TODO.md` with a test plan.

### 2026-11-10: The privacy slice: redaction, local-only folders and the log
- **Tool / model:** Claude Code / Claude Fable 5.1
- **Task:** implement F14 and the rest of F13 as designed: the `privacy` package, the two
  decorators and the factory chain, the facade methods, the CLI commands, the chip item and the
  settings dialog.
- **Input / instruction:** "Make a plan for implementing the privacy stuff"; answers to the
  plan's three questions; a note for Stage 3's tests; three observations from using the settings
  dialog.
- **AI contribution:** read the design's privacy sections and diagrams, listed what they left
  open (placeholder format, category names, log fields and file, saving the toggles, how the
  answer learns that sources were withheld, the read-only process, an unwritable log) and
  decided each with a logged reason. Built the four patterns with the Luhn check, the redactor,
  policy and log, the decorators (wrapped in `createClient()` so the scripted test clients are
  decorated too), the facade methods with the withheld notice, the three CLI commands, the chip
  item and the dialog, with end-to-end tests on fake values and a check against the real cloud
  model on the corpus. Fixed its own pattern when it saw that digits inside a file or folder
  name could pass Luhn and mangle paths. Mistakes: two test helpers waited on the JavaFX thread
  from the JavaFX thread; one CLI test read a truncated excerpt and failed only on Windows, where
  the temporary path is longer. Verified that JavaFX runs on XWayland by design before writing
  the note.
- **Human contribution:** chose the scope at each decision point: the settings dialog as well as
  the chip item, no mitigation yet for the search-result leak, and, after asking exactly what
  whole-message withholding hides, the simpler form first with finer withholding as a TODO, with
  the instruction to do the finer one instead if it turned out easy. Reviewed each of the seven
  pieces. Pointed out that the corpus's SSH key is a placeholder line, so Stage 3 should test
  redaction and the exclusions on generated throwaway keys in real formats. Used the dialog and
  reported three problems to document rather than fix: a long test result that squeezed the
  Backend, Model and Test connection controls to "…", the privacy log visible by default (a risk
  when recording or streaming), and the window running under XWayland, asking whether that was
  avoidable.
- **Outcome:** [6068d97](https://github.com/amarz45/find-anything/commit/6068d97b9099bc7a9cc7e6e565ccd5f1928a8798),
  [de99189](https://github.com/amarz45/find-anything/commit/de991895fa720017abd64a90ef867f8c775eea50),
  [70726e6](https://github.com/amarz45/find-anything/commit/70726e649eab4d5dc3e99185261d8ed4fdd5e0fa),
  [29866ae](https://github.com/amarz45/find-anything/commit/29866ae93ae13c31c5e7cbd46c3d74764746dd8a),
  [2fb7acc](https://github.com/amarz45/find-anything/commit/2fb7acc81b9f76edbc0ef93cc8f7de0c70cdc417),
  [800a3ce](https://github.com/amarz45/find-anything/commit/800a3ce8122b09264dce038b4765972be4cb0cc7),
  [c19cf29](https://github.com/amarz45/find-anything/commit/c19cf29767f08817575c510102509ca6e7e32283);
  pull request #8, CI green on both runners. F13 and F14 Implemented; Decorator, Factory and MVC
  evident in the code. Four design-change entries for what the design left open.

### 2026-11-12: Setting up Ollama, a local model that didn't search, and an agent that wasted time
- **Tool / model:** Claude Code (Claude Opus 5.5 for the setup and the debugging, then Claude
  Fable 5.2 for the search-size plan and the attempted implementation)
- **Task:** set up Ollama for the local backend, find out why "What's my cumulative GPA?" failed,
  and start making search results smaller so a bigger local model fits in 16 GB of VRAM.
- **Input / instruction:** "Help me set up Ollama for this project"; later two screenshots of
  failed answers and the output of `findany show`, `privacy-log`, `search` and `ask --json`;
  then a request for ideas on smaller search results, and to implement the first one on a new
  branch `optimization`.
- **AI contribution:**
  - Useful: found Ollama already running on the GPU with no models; explained why the context
    size matters; from the saved conversations and the privacy log, showed that both failing
    screenshots were the local model, that the one failed cloud run had every search result
    withheld as local-only, and that `qwen3:8b` answered with no tool call at all although
    `findany search` ranked the transcript's page 1 second. Measured a real request (15,665
    characters per search, 34,019 for two searches returning the same ten files) and proposed
    ways to shrink it. Worked through the case of a file that itself says "shown above", and
    found the worse case of a note pointing at a result withheld from a cloud model.
  - Mistakes Amar caught:
    - Asked "How do you install the model?", it downloaded `qwen3:8b` (5.2 GB) without asking.
    - Gave instructions that set the model both in `.env` and with `--model`.
    - Saved the resulting rule to Claude Code's memory folder, although agent rules belong in
      `AGENTS.md`.
    - Tried to list four screenshots when two were asked for.
    - Claimed smaller search results wouldn't help and that sending more chunks per file was the
      fix for the page 2 answer; both were wrong, since a bigger model is the fix and the cloud
      model handled the same results.
    - In the implementation, edited source files with Python scripts run through the shell,
      then sent runs of three-line edits (up to seven in one file), each one a separate approval;
      carried on after rejections that said to stop and wait; didn't say what was applied and
      what wasn't; and, told to stop wasting time, kept reading files with half-applied changes
      in the tree. The first item was written three times and each attempt was discarded. No
      code from this part of the session reached the project.
- **Human contribution:** chose a small model first to try local use; tested in the GUI on his
  own documents rather than the suggested corpus test, and ran the diagnostic commands himself;
  corrected both wrong claims about what would fix the local model's answers; had the two rules
  (no downloads without permission, no agent memory) and the older memory entries moved into
  `AGENTS.md`, then a third rule that files are edited with the Edit and Write tools, never
  scripts; agreed to the first three ideas only and had the plan say so; rejected the scripted
  and piecemeal edits, had them undone, and stopped the implementation to move it to a new
  conversation with the plan saved in the handoff.
- **Outcome:** [f694da8](https://github.com/amarz45/find-anything/commit/f694da8336a0c6657b5d19e7d8537e98cf070dfe)
  (agent rules in `AGENTS.md`),
  [c1b30f3](https://github.com/amarz45/find-anything/commit/c1b30f3f4ed2bd8f6ec1ea92a5b6db073ed19f7c)
  (`TODO.md`: a local model can answer without searching). Uncommitted on `optimization`: the
  editing rule in `AGENTS.md` and the plan in `docs/notes/handoff.md` ("Plan: smaller search
  results"). `qwen3:8b` is installed in Ollama and works with the app. No code changed.

### 2026-11-12 to 2026-11-13: Smaller search results, measured, and a 14B local model
- **Tool / model:** Claude Code, Claude Fable 5.2
- **Task:** implement the three agreed ideas for smaller search results, measure what they save,
  and find out whether a better local model fits in 16 GB of VRAM and why it is slow.
- **Input / instruction:** "Read AGENTS.md and the handoff. Start working on Idea 1 from the
  plan"; then "How do you measure the saving?", "I ran the query. Measure the savings", "Test it
  yourself", "Commit the change, and work on Idea 2" and the same for idea 3; later questions on
  a character budget, embeddings, whether a better model fits, and why `qwen3:14b` took about a
  minute, with a screenshot of its answer.
- **AI contribution:**
  - Wrote the three changes with tests, one commit each, with one edit per file as the rules
    ask: `ShownPassages` (a passage is given once per request), identical files as one entry,
    and whole passages for the first three entries only. The first followed the design approved
    in the handoff; for the other two it chose the details itself (the `Same content at:` lines,
    three whole passages, 200-character parts) and listed those choices for review.
  - Measured from the privacy log with a small read-only script that prints sizes only: the
    two-search GPA request went from 34,019 to 12,385 characters and the resume request from
    21,456 to 10,321. Ran four questions on the test corpus with the cloud model in a scratch
    `FINDANY_HOME` and showed the notes working, with every answer correct and verified.
  - From Ollama's log, showed that `qwen3:14b` loads entirely on the GPU (11.3 GB at a 16k
    context), reads prompts at about 600 tokens per second and writes at 24, and that it wrote
    853 tokens for an answer of about 150, most likely hidden thinking. Related the writing
    speed to the card's memory bandwidth and estimated other hardware from published figures.
  - Raised things it then left alone: a note saves nothing on a very short passage; parts are
    sent again by a second search; the transcript's two copies weren't joined.
  - Weak points:
    - It reported idea 1 as done before any real run showed a saving. Amar's first run made one
      search, so it saved nothing, and the 41% figure it then gave was a replay of an older log,
      not a measurement. The real figures came only after Amar ran both questions again.
    - Its live check of idea 3 on the test corpus returned two files, so it never exercised a
      part; that change went in on a unit test and Amar's own run.
    - It looked for the screenshot in the wrong folder and was stopped.
    - Its first version of the idea 3 test failed on its own query, which matched another file.
- **Human contribution:** ran the GPA and resume questions on his own documents before and after,
  which gave the only like-for-like measurements; told the AI to run the corpus test itself;
  asked what "the first line still counts every file" meant; passed over the offer of a
  `--verbose` count and the short-passage fix; had each change committed in turn and merged the
  pull request; downloaded `qwen3:14b` himself and tested it, judging it much better than the
  8B model, not as good as the cloud model, and slower than he likes; questioned the assumption
  behind the embeddings estimate; had the documentation commit made on `master`, not the merged
  branch.
- **Outcome:** [add80fd](https://github.com/amarz45/find-anything/commit/add80fd004c538008225df69074c4654a84dc6d8)
  (a passage once per request),
  [5f41301](https://github.com/amarz45/find-anything/commit/5f41301bc2860ed5a42bc3c5c2df511e49a2b27c)
  (identical files as one entry),
  [158e6d0](https://github.com/amarz45/find-anything/commit/158e6d0f181b801d333c6ef61dedb86ad5136bb9)
  (whole passages for the first three entries),
  [a9c2322](https://github.com/amarz45/find-anything/commit/a9c2322e06f7b71417dcdb0055de3de56ad144dd)
  (handoff and `TODO.md`), merged as pull request #9. `qwen3:14b` is installed and works with
  the app. Not done: turning the local model's thinking off, a character budget, embeddings.

### 2026-11-13: F04 filters, and a local model that answered without searching
- **Tool / model:** Claude Code, Claude Fable 5.2
- **Task:** implement F04 (filters) in the CLI and the GUI, then find out why `qwen3:14b` said
  Amar's transcript holds no GPA when it is on the first page, and fix it.
- **Input / instruction:** "Create a plan for feature 4", then one commit at a time. Later: a
  screenshot of the failed GPA question and "Is this an index issue or an agent issue or
  something else?"
- **AI contribution:**
  - **Filters.** Found that the lower half of F04 already existed (`SearchFilters`, the index's
    filter clauses, the facade's validation), so planned it as `ui` work only: the six options
    of `findany search` with a size parser, then a "Filters" button and row in the GUI with
    `GuiController.onFiltersChanged()`, each with tests. Checked the CLI on the test corpus in a
    scratch `FINDANY_HOME`.
  - **Diagnosis.** Copied the two transcript files to a scratch folder and reproduced the
    failure with the same model: the first question was answered with no tool call, and the
    follow-up ran `search(query="transcript")`, whose best passage is page 2 (help links that
    repeat the word), so the model never saw page 1. Showed that the index was fine: searching
    "GPA" returns page 1.
  - **Fixes.** Proposed three: send a first reply without a search back to the model, describe
    in the prompt what a search returns, and say on a result how much of the file a passage is.
    Implemented the first two and measured each with three runs of the same questions.
  - Weak points:
    - Its prompt sentence did not fix the failure it was written for: in two runs of three the
      model still searched for "transcript" alone. It reported this and left the decision to
      Amar. Only the second fix changed the outcome (3 runs of 3).
    - Its first descriptions of the fixes ("say so in the result", "add the user's question
      words to the ranking") were too vague to judge; Amar had to ask what they meant.
    - Its first prompt wording carried a specific example and told the model what not to search
      for. Its first label wording told the model to read the rest of the file. It had tested
      neither and said so only when asked.
    - It tried to add the GPA finding to `TODO.md` after Amar had said he would come back to it;
      the edit was rejected.
    - The pull request description for the agent changes listed a commit that wasn't in it, and
      the handoff merged with the filters said the branch was "not pushed yet". It corrected
      both, the second at Amar's request.
    - The GUI's filter row was checked only through headless tests, never on a real display.
- **Human contribution:**
  - Chose F04 ahead of F05 in the Tier 2 order, approved the plan with the row-not-menu design
    point, and had each piece committed before the next was started.
  - Asked why Ask mode removes the filters. The answer was that they never reach the agent; he
    left it as built.
  - Noticed the failure himself in the GUI, pointed out that the GPA is on the transcript's
    first page, and noted that the question had worked with this model before.
  - Questioned each proposed fix before agreeing to any:
    - Asked how the AI knew a "use read_excerpt to read the rest" hint wouldn't make a weak
      model read whole files. It didn't know; the wording was reduced to the fact alone, and the
      label was put off.
    - Pointed out that the model already has the whole conversation, which showed that
      "use the request's words for ranking" only works around a model that writes poor queries.
    - Rejected a specific example in the general prompt, and asked whether "not only where you
      expect it" would stop a weak model searching by a file's name when that is right. The
      sentence was rewritten to describe what a search returns and to forbid nothing.
    - Raised the "hi" case against forcing a search: a user may greet the model just to see
      that it works. This led to the fixed reply `NO SEARCH NEEDED`, with which the model
      declines and its first reply is kept.
  - Decided the order (the prompt sentence first, then the search-first step), kept the sentence
    after seeing that it hadn't helped on its own, and put the label off.
  - Rejected the `TODO.md` edit, had the out-of-date documentation corrected in its own commit,
    and merged the filters pull request himself.
- **Outcome:** [ca5b70b](https://github.com/amarz45/find-anything/commit/ca5b70b7365eb19bd456166350b0e2d5f9e6e6bb)
  (the filter options of `findany search`),
  [aeaccb0](https://github.com/amarz45/find-anything/commit/aeaccb0b7c6bfac68b6037c889c09c6251b99e09)
  (the GUI's Filters controls), merged as pull request #10;
  [94d474d](https://github.com/amarz45/find-anything/commit/94d474d162387bdf215203a9d324c0193caab83c)
  (the prompt says a search gives one passage per file),
  [d063b8c](https://github.com/amarz45/find-anything/commit/d063b8c30d819885d9b09f37c5435a558f9ef90b)
  (a first reply without a search is sent back once), merged as pull request #11. With
  `qwen3:14b` the GPA question is now answered after a search, and "hi" gets its greeting
  without one. Not
  done: the label on a search result, checking either agent change with the cloud model, and
  looking at the filter row on a real display.

