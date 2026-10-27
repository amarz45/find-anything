# Handoff: state of Stage 2

Written 2026-10-23 at the end of the planning conversation (claude.ai app), for the first Claude
Code session. Update the "State" section at the end of every session, especially before switching
machines (laptop ↔ desktop).

## Working guidelines

- **Answers:** keep them concise. After any edit, say exactly what changed. When asked to
  "discuss," make no file changes until told to go ahead.
- **Readable output:** everything delivered must be readable and editable by a person. No opaque
  generators: if a script produces something, the script is the source and must be readable.
- **Names:** keep the design's names exactly (`fileWatcher`, `searchIndex`, `addUserMessage()`).
- **Facts:** don't guess. Verify facts, versions and tool quality (maintenance, open issues, user
  reports) before stating or recommending them, and say plainly what couldn't be checked.
- **Experiments:** run tests and benchmarks yourself where possible rather than asking the user to.
- **Libraries:** prefer a library over hand-written code when it solves the problem better, but keep
  it behind the design's interfaces and adapters.
- **Reviews:** when findings from another reviewer are passed on, fix every inconsistency rather
  than explaining it away.

## The deadline and what Stage 2 requires

Stage 2 is due **Tuesday, November 10, 2026** (not Nov 20 as the syllabus said; the professor
confirmed). The full instructions are in `local/Project-Stage2-Instruction.pdf`, which is kept out
of Git with `.git/info/exclude`. In short:

- **Minimums:** a GUI and a CLI, at least 10 working non-trivial features, at least 5 distinct
  design patterns evident in the code, real LLM integration, and meaningful agent behaviour. Mockups,
  empty methods, hard-coded responses and unconnected classes don't count.
- **Accounting for the design:** every Stage 1 feature (F01–F14) is accounted for: Implemented,
  Modified, Replaced or Not implemented, with the reasons. Every change is documented (the
  structure is in `docs/logs/design-changes.md`).
- **Weekly commits:** at least **5 meaningful code commits** in each period: **Oct 20–26**,
  **Oct 27–Nov 2**, **Nov 3–9** (Toronto time, pushed during the period). Documentation,
  formatting-only, empty and artificially split commits don't count. Missing any period costs
  5 points once. The report links the commits for each period.
- **Report:** `stage2-report` (PDF or Word) in the repo, with 7 sections: overview and status;
  feature-to-implementation mapping; implemented design patterns, including an **updated UML class
  diagram**; changes and rationale; AI tools and collaboration log, including **total tokens and
  money per tool**; development and verification summary, including the weekly commit summary;
  demonstration index.
- **Videos:** a demo for **every implemented feature** (feature ID and name, how it's invoked, the
  running app and its output, where AI is involved, error behaviour where relevant). Together they
  must show both the GUI and the CLI, linked from the report with timestamps. No secrets on screen.
- **Keys:** real keys only in a git-ignored `.env`; `.env.example` is committed; the README explains
  copying it, entering a key and running the app.
- **Testing:** Stage 2 needs only basic verification. Full deterministic tests and KUMA agent tests
  are Stage 3.

The professor's Stage 1 feedback (100/100) said: *"Implement indexing lifecycle and citation
verification early; test stale/deleted files, background extraction failures and concurrent process
ownership."*

## Scope

**Tier 1, the Stage 2 target.** 11 features and all 5 patterns:

| Feature | Stage 2 version |
|---|---|
| F01 Folders | CLI; add/remove in the GUI |
| F02 Extraction | Tika, as tested in `docs/notes/tika/` |
| F03 Index updates | Rescans at startup and on demand; the read-only second process. Live watcher only if time allows; otherwise *Modified* |
| F06 Instant search | Keyword search |
| F07, F08, F09, F12 | The agent: Ask mode, answers, citation verification, follow-ups |
| F11 Preview | Extracted text with highlights |
| F13 Backends | Cloud (OpenRouter) or local (Ollama), test connection, privacy log |
| F14 Privacy | Built-in exclusions, pattern redaction, local-only folders |

The five patterns: Facade (`FindAnythingService`), Observer (index status), Command (agent tools),
Adapter (`LlmClient`, `TextExtractor`), Decorator (redacting and logging clients).

**Tier 2, in this order, if time allows:** F05 semantic search; F04 filters (CLI first); the live
file watcher; F10 comparison.

**Simplifications:**
- `ContentStore` is postponed: renamed or copied files are extracted again.
- The GUI is one window: search bar, Search/Ask switch, results table, detail pane (preview or
  conversation), an "Add folder" button, and a minimal settings dialog.

All of these are logged as design changes if they stay.

**Moved to Stage 3:** most tests, static analysis (Error Prone, NullAway), ArchUnit, polish. Still
done now: formatting, CI, and small tests for logic as it's written (`ExclusionRule`, `Chunker`,
`CitationVerifier`), since they're cheap and catch real bugs.

## Timeline

| Dates | Project | Commit period |
|---|---|---|
| Sat Oct 24 – Sun Oct 25 | Setup, then slice 1 | 1 |
| Mon Oct 26 | More slice 1. **Period 1 ends tonight: 5 code commits** | 1 |
| Oct 27–29 | Slice 1: indexing lifecycle, read-only mode, `search`, `show` | 2 |
| Oct 30 – Nov 2 | Slice 2: the agent | 2 |
| Nov 3–5 | Slice 3: GUI, then privacy and backends; F05 if time | 3 (5 commits by Nov 5) |
| Nov 6–8 | No project work planned | |
| Mon Nov 9 | Freeze; record videos; update the class diagram | 3 |
| Tue Nov 10 | Report; final check; submit | |

## Decisions already made

- **IDE:** IntelliJ IDEA with the IDE Index MCP Server. The Debugger MCP Server comes later if
  needed. No Claude Code JetBrains plugin and no IntelliJ built-in MCP server: the user rejected
  both on their reviews. Claude Code runs in a terminal (Konsole); changes are reviewed in
  IntelliJ's Commit view.
- **Build:**
  - Gradle 9.8.2 with Kotlin DSL and a version catalog (`gradle/libs.versions.toml`); Java 25.
  - JavaFX 27 on the class path (no module-info).
  - Spotless with google-java-format **1.36.1, AOSP style**, the same version as the IntelliJ plugin.
  - JUnit 6 and AssertJ for tests.
- **Libraries:** Lucene 10.5.2, Tika 4.1.0 (`tika-pipes-fork-parser`), LangChain4j 1.22.0
  (`open-ai` for OpenRouter, `ollama`), Jackson 2.22.4 (the line Tika and LangChain4j use), picocli
  4.7.7, directory-watcher 0.19.1.
- **CLI parsing:** picocli.
- **GUI:** layouts written in Java code (no FXML).
- **`ContentStore`:** one JSON file per content hash, when it's built (postponed).
- **Cloud model:** OpenRouter through LangChain4j's `OpenAiChatModel` (base URL
  `https://openrouter.ai/api/v1`), because paying for Anthropic Console credits didn't work. Use a
  model that supports tool calling; copy its exact ID from openrouter.ai/models. On OpenRouter,
  keep prompt logging off and don't allow providers that train on data.
- **Local model:** Ollama on the desktop (RX 7600 XT), reached from the laptop over Tailscale. Set
  the context size explicitly. Pick the model in slice 2 with a quick check on five questions
  (that's the "benchmark" the Stage 1 report promised), and log the choice.
- **Keys:** `.env` (git-ignored) with `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`, `OLLAMA_BASE_URL`,
  `OLLAMA_MODEL`. Environment variables override `.env`. Never `ANTHROPIC_API_KEY`: Claude Code
  would use it instead of the subscription.
- **Root package:** `io.github.amarz45.findanything` (confirmed).
- **Entry point:** `findany` with no arguments prints help, like most command-line tools, and
  `findany gui` opens the GUI. Every other subcommand is CLI. `CliApp` is the main class; its `gui`
  command calls `Application.launch(...)`. The design doesn't specify this, so note it in the
  report's overview rather than the design-changes log.

## Technical notes

- **Tika:** read `docs/notes/tika/findings.md` before writing `IsolatedTextExtractor`. In short:
  - `isSuccess()` is also true for unreadable files, so check `CONTAINER_EXCEPTION`.
  - Use the XML handler for page and slide markers.
  - Turn OCR off with `{ "text-recognizers": [] }`.
  - Set `setJavaPath` from `java.home`, `-Xmx1g`, `setMaxEmbeddedCount(0)`, and quiet the child's logs.
  - Fix PowerPoint slide order.
  - Expect a non-UTF-8 Linux locale to break non-ASCII paths.

  `docs/notes/tika/ExtractorDemo.java` is a working reference, run against the whole test corpus.
- **The first build:** the build files were written without network access to Maven Central or the
  Gradle plugin portal. Versions come from the projects' release tags and the build script
  compiles, but dependency resolution hasn't run yet. If a version doesn't resolve, fix it in the
  version catalog. `tika-pipes-fork-parser` pulls in the standard parsers through a `pom`-type
  dependency. Gradle resolves those fine but also lists the `.pom` file on the class path, which is
  harmless.
- **The wrapper** was generated with Gradle 8.14.3 for distribution 9.8.2. Run
  `./gradlew wrapper --gradle-version 9.8.2` once to refresh the scripts. There's no distribution
  checksum yet; that command can add one with `--gradle-distribution-sha256-sum`.
- **JavaFX on the class path:** the main class must not extend `Application`, or the launch fails.
  `CliApp` is the main class, and its `gui` command calls `Application.launch(...)`.
  JavaFX logs an "unsupported configuration" warning on the class path, which is harmless.
- **Logging:** Tika brings Log4j 2 and LangChain4j logs through SLF4J. Configure one backend
  (`log4j2.xml` in resources) so the CLI's output stays clean, plus the forked parser's own config
  (`docs/notes/tika/tika-fork-log4j2.xml`).
- **`FINDANY_HOME`:** the folder for the index, settings, conversations and logs. It defaults to
  the platform's app-data folder (`~/.local/share/find-anything`, `%LOCALAPPDATA%\FindAnything`),
  which built-in exclusions always skip. Tests get a folder under `build/`.
- **The test corpus:** `testdata/corpus/` (25 files), with every planted fact and its page or slide
  in `testdata/FACTS.md`. Includes a 12-slide deck that Tika misorders, a prompt-injection note,
  fake card/SIN/key data in `private/`, a credential path and an Office lock file (both must be
  excluded), conflicting dates, and broken files. Modification times aren't kept by Git.

## Order of work

**Session 1: setup.** The user does the steps marked (user).
1. (user) Unpack the setup archive into the repo root and check the archive's file list.
2. (user) Put the Stage 2 PDF and the Stage 1 feedback in `local/`, and add `/local/` to
   `.git/info/exclude`.
3. Run `./gradlew wrapper --gradle-version 9.8.2`, then `./gradlew build`. Fix version or
   resolution problems until it passes with no source files yet.
4. (user) `git config core.hooksPath .githooks`. In `~/.claude/settings.json`, set
   `"cleanupPeriodDays": 3650`. In IntelliJ, enable google-java-format (AOSP) for this project,
   and turn on Reformat code and Optimize imports under Actions on Save.
5. (user) Commit and push the setup. It's not a code commit, so it doesn't count toward the five.
   Then set up the GitHub ruleset for `master`: require a pull request, require the two CI checks
   (`build (ubuntu-latest)`, `build (windows-latest)`), block force pushes, and allow auto-merge.
   Do this after CI has run once, so the checks can be selected.

**Slice 1: indexing lifecycle and keyword search** (code commits start here; each item is one
commit with a small test where there's logic):
1. Records: `Chunk`, `IndexedFile`, `ExtractedDocument`, `SearchResult`, `ChunkHit`.
2. `ExclusionRule` (with `parse()`), `SearchFolder`, `IndexConfig` with built-in exclusions
   (credential locations, the app's own data folder, Office/LibreOffice lock and temp files).
3. `TextExtractor` and `IsolatedTextExtractor`, adapted from the reference, with an integration test
   on `testdata/corpus/`.
4. `Chunker.split()`, page by page.
5. `SearchIndex`, keyword part: open with the write lock or read-only, `put`, `remove`, `commit`,
   `keywordSearch`, `listFiles`, `getChunks`, `getFile`. Accents folded (résumé = resume).
6. `ChangeQueue` (simple), `Indexer` worker (`rescanFolder()`, `indexFile()`, `notifyStatus()`),
   `IndexStatus`, `IndexStatusListener`, `SettingsStore` (JSON via Jackson).
7. `SearchEngine` (keyword only), `FindAnythingService`, and `CliApp` with `folders`, `exclude`,
   `index status|rebuild`, `search`, `show`.

   Done when you can index `testdata/corpus`, edit a file, rescan, and see the change; deleted
   files disappear; a second process opens read-only; CI is green on both OSes.

**Slice 2: the agent:**
1. `LlmRequest`/`LlmResponse`, `LlmClient`, `LangChain4jClient` (OpenRouter, then Ollama),
   `ModelSettings`, `LlmClientFactory`.
2. `Tool`, `ToolResult`, `ToolRegistry` (validation and call log), `SearchTool`, `ReadExcerptTool`.
3. `Conversation`, `ConversationStore`, `PromptBuilder` (today's date; tool results marked as
   file content, not instructions), `ResponseParser`, `Answer`, `Citation`, `CitationVerifier`.
4. `AgentController`: step limit, invalid calls returned to the model, one reformat request, and a
   fallback to instant search when the model is unavailable.
5. CLI: `ask` with `--json` (answer, citation statuses, tool calls, model, fallback flag),
   `--continue`, `--verbose`; `model set|test`.

   Done when "When does my lease end?" gets August 31, 2026, cited to page 2 of
   `housing/lease-2025-2026.pdf` and marked verified, with OpenRouter and with Ollama.

**Slice 3: GUI, privacy, backends:**
1. GUI: `MainWindow`, `ResultsView`, `DetailPane`, `GuiController` (Search and Ask modes, status,
   add folder, clickable citations).
2. `SensitivePattern`s, `Redactor`, `FolderPolicy`, `PrivacyLog`; `LlmClientDecorator`,
   `RedactingLlmClient`, `LoggingLlmClient`; the factory builds the cloud chain.
3. `SettingsDialog` (backend, Test connection, redaction toggles). CLI: `privacy-log`,
   `policy local-only`, `privacy redact`.
4. Tier 2 items, in order, as time allows.

**Freeze (Nov 9–10):**
1. Record the videos.
2. Update the class diagram in UMLet, plus any sequence or use-case diagrams the changes made wrong.
3. Write `docs/stage2-report.md` from `docs/traceability.md` and `docs/logs/`, and build the PDF.
   `docs/pdf.yaml` is hard-wired to the Stage 1 report, so add a second Pandoc defaults file for
   Stage 2.
4. Total the token and money costs per tool from the Claude Code transcripts.
5. Update the README (setup, `.env`, launch commands, example usage), check it works from a fresh
   clone, and submit.

## Built now so Stage 3 is easy

These are already in the plan:
- `findany ask --json`
- `--continue`
- `FINDANY_HOME`
- interfaces with constructor injection
- `ToolRegistry`'s call log
- `testdata/`
- `docs/logs/test-failures.md`

Stage 3 (due Dec 8) adds full tests and KUMA behaviour tests. KUMA needs Python 3.10+ and a thin
adapter that runs `findany ask --json`. The user still needs to ask the professor about KUMA credits.

## State

*(Update at the end of each session: what's done, what's in progress, what's next, anything
surprising.)*

- **2026-10-23 (planning, claude.ai):**
  - Done: the user set up IntelliJ, JDK 25 and IDE Index MCP. The setup files and test corpus were
    created; this handoff was written.
  - Not done yet: everything from Session 1, step 1 onward.
- **2026-10-24 (Session 1: setup, Claude Code):**
  - Done: all of Session 1. The setup is committed (`c5e8319`) and pushed, and CI passed on both
    OSes. The wrapper was refreshed for Gradle 9.8.2 with a distribution checksum.
    `./gradlew build` passes with no sources. Every dependency resolves at the catalog's versions
    with no fixes needed (Jackson conflicts settle on 2.22.4), and the only SLF4J backend is Tika's
    Log4j 2 binding.
  - Workflow from now on: `master` takes changes only through pull requests that pass both CI
    checks. Group small commits into one PR, and don't squash merge, because each code commit has
    to count toward the weekly five. `gh` is installed but needs `gh auth login` before Claude can
    use it.
  - Next: Slice 1, item 1 (the records). Period 1 ends Mon Oct 26: 5 code commits by then.
- **2026-10-25 (Session 2: Slice 1, Claude Code):**
  - Done: all seven Slice 1 items, on branch `slice1-indexing` (8 code commits, pushed; see the
    Period 1 row in `docs/traceability.md`). `findany folders|exclude|index|search|show` work on
    `testdata/corpus`: edits and deletions are picked up by the rescan, excluded files never get
    indexed, a second process opens read-only and is refused folder changes, 67 tests pass.
    `docs/traceability.md`, `docs/logs/design-changes.md` and `docs/logs/test-failures.md` are
    updated.
  - Not done: the PR `slice1-indexing` → `master` (open it, wait for both CI checks, merge
    without squashing). The live `FileWatcher` (Tier 2) and `ContentStore` (postponed) are out.
  - Surprises worth knowing:
    - Lucene stores times in milliseconds but the file system reports nanoseconds; the rescan
      must compare truncated values (see `docs/logs/test-failures.md`).
    - Spotless rewraps lines, so an edit script that matches text written moments before can
      silently miss; check the file after `spotlessApply`.
    - Lucene logs through `java.util.logging`; the CLI installs `jul-to-slf4j` so `log4j2.xml`
      governs it. The run and test JVMs get `--enable-native-access=ALL-UNNAMED` for Lucene's mmap.
    - The CLI uses a zero quiet period (`ChangeQueue`), the GUI should use about two seconds.
    - `SearchFilters` is already applied inside `SearchIndex` queries, so F04 is mostly the CLI
      options and the GUI menu.
  - Next: Slice 2 (the agent), starting with `LlmRequest`/`LlmResponse`, `LlmClient`,
    `LangChain4jClient`, `ModelSettings`, `LlmClientFactory`. Period 2 (Oct 27–Nov 2) needs 5
    code commits.
