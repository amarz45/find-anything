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

Stage 2 is due **Sunday, November 15, 2026**. It was Tuesday, November 10 (not Nov 20 as the
syllabus said; the professor confirmed), and the deadline was extended on November 10. The change
freeze moves to **November 14–15**; the days before it go to the privacy work, then items from
`TODO.md` and Tier 2. The full instructions are in `local/Project-Stage2-Instruction.pdf`, which is kept out
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
  5 points once. The report links the commits for each period. Whether the extension adds a
  fourth period (Nov 10–15) isn't confirmed; keep making and pushing code commits in it as if it
  did.
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
| Nov 9–10 | The GUI merged (PR #7); privacy: patterns, policy and log, decorators, facade | 3, then 4 (if it counts) |
| Nov 10–13 | Privacy: CLI commands, GUI chip and settings dialog; then `TODO.md` items and Tier 2 in order (F05, F04, live watcher, F10) | 4 (if it counts) |
| Nov 14–15 | Freeze: record videos; update the diagrams; report; final check; submit by Sun Nov 15 | |

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
  command starts JavaFX with `Platform.startup(...)` (not `Application.launch()`, which would
  create the application object itself and leave no way to hand it the service) and shows a
  `Stage` it creates. The design doesn't specify this, so note it in the
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
  `CliApp` is the main class, and its `gui` command calls `Platform.startup(...)`.
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

**Freeze (Nov 14–15):**
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

Noted for Stage 3's tests (Amar, 2026-11-10): the privacy tests should use realistic sensitive
data, not stand-ins. `testdata/corpus/home-like/.ssh/id_ed25519` is the line "placeholder: not a
real key", so today the built-in exclusion is tested only on the path and `PrivateKeyPattern` only
on hand-written PEM blocks. Throwaway keys can be generated for the corpus and the unit tests
(`ssh-keygen -t ed25519`, `-t rsa`, with `-m PEM` for the PKCS#1 form, plus PKCS#8 and PuTTY
`.ppk` files, and a key pasted inside a note), together with card numbers of each brand, SINs in
each spelling, and keys with each prefix `ApiKeyPattern` knows, so redaction and the exclusions are
checked on the real formats. Generated keys only, never one that was ever used.

## Plan: smaller search results (branch `optimization`)

Written 2026-11-12. Nothing is implemented yet: `optimization` differs from `master` only by an
uncommitted rule in `AGENTS.md` (edit files with the Edit and Write tools, never scripts).

**Goal.** Higher answer quality per context used, so a bigger local model fits in the desktop's
16 GB of VRAM. `qwen3:8b` is installed and works with the app, but it often answers without
searching (see `TODO.md`, "The agent's search"); the cloud model handles the same results fine.

**Measured** (privacy log, cloud run of "What's my cumulative GPA?", 2026-11-12):
- One `search` with `limit=10` returned 15,665 characters (about 3,900 tokens), nearly all of it
  the ten passages of about 1,500 characters each (`Chunker` is built with 1,500).
- The model ran two searches that returned the same ten files, so the request was 34,019
  characters, half of it repeated. The answer needed under 100 characters from two files.
- Both models ask for `limit=10` themselves; the default of 5 is rarely used.

**Agreed with Amar: implement these three, in this order.**
1. **Don't repeat a passage within one request.** A later search lists the file with a one-line
   note in place of a passage the model already has. Design below.
2. **Collapse identical files.** `IndexedFile.contentHash` is already there: show one passage and
   "same content at: …" (seen: `transcript.pdf` and `Student Document Services.pdf`, and
   `resume.pdf` twice).
3. **Full passages for the top few, one line for the rest** (for example top 3 with passages, the
   others with name, type, date and a short snippet around the matched words). Snippets for every
   result would be smaller, but the answer then often needs `read_excerpt`, which weak models skip.

**Other ideas, only raised. Amar hasn't agreed to any of them; ask before doing one.**
- A character budget per search result (say 4,000), so steps × budget fits the context. With 1
  to 3 it would bring the measured request from about 34,000 characters to about 8,000.
- Stricter matching: the query is OR across words (`SearchIndex`); require all words first and
  fall back to OR.
- Cleaner extracted text (the transcript's page 2 ends with about 20 duplicated URL lines).
- Marking each passage separately as file content (see "Imitation" below).

**What embeddings (F05, which Amar plans to add anyway) would change.**
- They don't shrink results; they improve the ranking, which makes showing fewer results safe.
- Fewer searches per question: the taxes run on Nov 10 made four searches with reworded queries.
- Probably smaller chunks: small embedding models limit their input (from memory, unchecked: 256
  tokens for `all-MiniLM-L6-v2`, 512 for `bge-small`; a 1,500-character chunk is about 375
  tokens). Smaller chunks mean smaller results, and a reindex.
- Risk: vector search always returns the nearest chunks, never "no match", so it needs a
  similarity cutoff.
- The same vectors can pick the best sentences in a chunk and detect near-duplicates.
- No VRAM cost: the design runs the embedding model in-process on the CPU.

**Model size.** The saving matters most for a 14 GB model such as `gpt-oss:20b`, which fits 16 GB
only with a small context. `qwen3:14b` (9.3 GB) already fits at 16k; there the gain is more steps
before the context runs out. Neither model's memory use per context size is measured. Nothing may
be downloaded without Amar's say-so. `LangChain4jClient.OLLAMA_NUM_CTX` (16,384) is still a
placeholder.

**To compare variants** (a suggestion): run the questions in `testdata/FACTS.md` through
`ask --json` and record correct, verified answers against characters sent.

**Design for idea 1** (approved by Amar on 2026-11-12; needs an entry in
`docs/logs/design-changes.md` and the Command row of `docs/traceability.md`):
- **The note.** It never says "shown above", which file text could say too. It replaces the
  passage on the label line, which only the tool writes, and names the earlier search:
  `Best passage (page 1): already given above, in the results for "cumulative GPA".` A passage's
  text always starts on the line after a label. The file stays in the result's `sourceFiles`.
- **Imitation.** A file can already imitate any label, because `PromptBuilder` wraps the whole
  result in one `[FILE CONTENT START]`…`[FILE CONTENT END]` block. The note adds one more line to
  imitate; the worst case is the model looking for a passage that isn't there. Marking each
  passage separately would fix it (about 45 characters per passage); not part of this change.
- **Never point at text the model can't see.**
  - Only passages of the request in progress count, because `dropToolMessages()` removes earlier
    tool results when a request starts.
  - `RedactingLlmClient` withholds a whole result from a cloud model when any of its files is
    local-only. A result that holds such a file records nothing, so its passages are given again
    later. A local model has nothing withheld.
- **Code.**
  - New class `agent/ShownPassages`, one per request: built with a `Predicate<Path>` (whether a
    file is withheld from this request's model); `queryThatGave(Path, Chunk)` returns the query of
    the search that gave a passage (keyed by file and `Chunk.position()`);
    `record(String query, List<SearchResult>)` records a result's passages, keeping the first
    query, and nothing when any file of the result is withheld.
  - `Tool.execute(arguments, shownPassages)` and `ToolRegistry.execute(toolCall, shownPassages)`
    take it, because the registry and its tools are shared by all requests. `ReadExcerptTool`
    ignores it; `SearchTool.describe()` writes the note and `execute()` records the result.
  - `AgentController` takes the `FolderPolicy` in its constructor (from `FindAnythingService`);
    `run()` creates the record: withheld means the conversation's backend is `CLOUD` and
    `folderPolicy.isLocalOnly(file)`.
- **Tests.**
  - `SearchToolTest`: a second search with the same record gives the note and not the text; a
    different passage of the same file comes in full; a new record gets the passage in full; a
    record whose predicate withholds a file of the first result gets it in full again. Queries
    with one clear best chunk: "lease ends", then "ends august".
  - `AgentControllerTest` with a real `SearchTool`: two searches in one request, then a follow-up
    request; a cloud conversation with a local-only file against a local one.
  - `ScriptedTool` and the other callers of `execute()` need the new argument.

**How Amar wants the work done** (also in `AGENTS.md`): one edit per file, never a run of small
edits and never scripts; stop after any rejected edit; one commit at a time, not committed by the
agent.

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
    - The CLI uses a zero quiet period (`ChangeQueue`). The GUI does too for now: the two
      seconds were meant for the live watcher's bursts, and without a watcher a quiet period
      would only delay every rescan. Revisit with `FileWatcher` (Tier 2).
    - `SearchFilters` is already applied inside `SearchIndex` queries, so F04 is mostly the CLI
      options and the GUI menu.
  - Next: Slice 2 (the agent), starting with `LlmRequest`/`LlmResponse`, `LlmClient`,
    `LangChain4jClient`, `ModelSettings`, `LlmClientFactory`. Period 2 (Oct 27–Nov 2) needs 5
    code commits.
- **2026-10-29 to 2026-11-02 (Session 3: Slice 2, item 1, Claude Code):**
  - Done: Slice 2, item 1 plus `findany model set|test` (pulled forward from item 5), on branch
    `agent` (5 code commits, pushed and merged into `master` as PR #4 on Nov 2; see the Period 2
    row in `docs/traceability.md`).
    The `llm` package (`Message`, `ToolCall`, `ToolSpec`, `LlmRequest`, `LlmResponse`,
    `LlmException`, `LlmClient`, `LangChain4jClient`, `LlmClientFactory`), `ModelSettings`,
    `BackendType`, `EnvFile` and the model-settings half of `SettingsStore`;
    `FindAnythingService.getModelSettings()`, `resolveModelSettings()`, `testBackend()`,
    `setBackend()`. Test doubles for the agent work: `ScriptedLlmClient`,
    `ScriptedLlmClientFactory`, `FakeChatModel`. `docs/traceability.md` and
    `docs/logs/design-changes.md` are updated.
  - Verified: the error paths (no key, no model name, Ollama unreachable, nothing saved on
    failure) from the CLI. **Not yet verified:** a successful `findany model set cloud` with the
    real key, because the session couldn't see `.env`; run it from the repo root with a scratch
    `FINDANY_HOME` first. Ollama over Tailscale and the five-question model check are still to do.
  - Surprises worth knowing:
    - LangChain4j's `RetryUtils` logs a WARN with a stack trace on every retry; `log4j2.xml` sets
      `dev.langchain4j` to `error` so the CLI prints one clean message.
    - OpenRouter answers 404 for a model whose endpoints don't support tool calling, so the
      `testConnection()` request includes a dummy tool and the 404 is reported as model not found.
    - `LangChain4jClient` doesn't call `think()` (some models reject it) and `numCtx` for Ollama is
      a placeholder (16k) until the model is picked.
  - Next: Slice 2, item 2 (`Tool`, `ToolResult`, `ToolRegistry`, `SearchTool`,
    `ReadExcerptTool`), then items 3 and 4, then `ask` in the CLI. Period 3 (Nov 3–9) needs 5
    code commits by Nov 5.
- **2026-11-04 (Session 4: Slice 2, items 2 and 3, Claude Code):**
  - Done: Slice 2, items 2 and 3, on branch `agent` continued from its merged tip (8 code
    commits, pushed and merged as PR #5 on Nov 5; see the Period 3 row in
    `docs/traceability.md`), all in the new `agent` package. Item 2: `Tool`, `ToolResult`, `ToolRegistry` (shared spec checks, call log),
    `SearchTool` and `ReadExcerptTool`. Item 3: `Conversation`, `ConversationStore`, `Answer`,
    `Citation`, `VerificationStatus`, `CitationVerifier`, `ResponseParser` (with
    `AnswerFormatException`) and `PromptBuilder`. Test double for the agent loop: `ScriptedTool`.
    None of it is wired into `FindAnythingService` yet; that comes with `AgentController`.
  - Decisions to remember (all in `docs/logs/design-changes.md`):
    - `ToolRegistry` checks arguments against the `ToolSpec` (required, unknown, types, allowed
      values) before `Tool.validate()`, so a tool's `validate()` and `execute()` can rely on the
      types.
    - `SearchTool` returns each file's whole best chunk (up to 1,500 characters) and defaults to
      5 files; `file_type` is a fixed list.
    - `ReadExcerptTool` takes `maxExcerptChars` in its constructor; the value isn't chosen yet
      (the facade sets it when the agent is wired up).
    - The answer format is plain lines: the text with `[n]` markers, a `CITATIONS:` line, then
      `file:`, `page:` and `quote:` per entry. `ResponseParser` throws `AnswerFormatException`
      with the problems, and `PromptBuilder.buildReformatRequest()` passes them to the model.
    - `PromptBuilder` wraps file text in tool results between `[FILE CONTENT START]` and
      `[FILE CONTENT END]`; the stored conversation keeps the tool's own text.
    - `ConversationStore` takes a `SettingsStore` for the key of a loaded conversation. Nothing
      generates conversation ids yet; that belongs with `FindAnythingService.newConversation()`.
  - Low-priority improvements noted along the way are in `TODO.md` (history trimming, citation
    verification).
- **2026-11-05 (Session 4, continued: Slice 2, items 4 and 5, Claude Code):**
  - Done: Slice 2, items 4 and 5, on branch `agent` after PR #5's merged tip (4 code commits,
    pushed and merged as PR #6 on Nov 7, CI green on Ubuntu and Windows): `AgentController` (the loop),
    `FindAnythingService.ask()` with the instant-search fallback and the conversation methods, a
    fix so the agent's tools skip paused and unavailable folders, and `findany ask` with
    `--continue`, `--verbose` and `--json`. The agent now works end to end from the CLI.
  - Checked with OpenRouter (Amar's run, Nov 5, with the real key in `.env`):
    "When does my lease end?" gave August 31, 2026, cited to page 2 of
    `housing/lease-2025-2026.pdf` and marked verified. The model also cited the 2026–2027 draft,
    and said it had ignored the instructions in `notes/note-from-unknown-sender.md`. The
    follow-up `ask --continue "And how much is the rent?"` gave $1,850 and the draft's $1,925,
    both verified. No reformat request was needed. This is also the first successful request
    with the real key; `findany model set cloud` itself wasn't confirmed separately. **Still to
    do:** the same check with Ollama (the slice needs both backends); Amar will run it later.
  - Decisions to remember (all in `docs/logs/design-changes.md`):
    - `AgentController.run()` throws `LlmException`; the fallback to instant search is in
      `FindAnythingService.ask()`, as SD03 draws it.
    - The conversation gets exactly one closing reply per request. A reply the parser rejects is
      shown to the model through a copy of the conversation and isn't stored.
    - `SearchEngine` and `ReadExcerptTool` take the `IndexConfig` and skip files outside the
      active folders; the facade no longer filters.
    - The agent's limits are constants in `FindAnythingService`: 8 steps, 6,000 characters per
      excerpt, 40,000 characters of history. The current exchange is never trimmed, so a long
      run of searches can still outgrow a local model's 16k context (see `TODO.md`).
    - `findany ask` has no interactive session; follow-ups use `--continue`, which picks the most
      recently saved conversation.
    - `ask --json` takes a request's tool calls from the conversation's messages after the last
      user message, not from `ToolRegistry`'s call log, which is shared by every request.
  - Seen on the real run, not acted on:
    - A marker can be attached to a statement its quote doesn't support (the follow-up cited the
      rent line for "not yet signed"). The verifier checks that a quote is in the file, not that
      it supports the sentence; that is the design's limit for F09.
  - Next: Slice 3, starting with the GUI (Amar's choice on Nov 7), then privacy and backends.
    The check with Ollama is still owed; Amar will run it later. `AgentController.summarizeDiff()`
    and `CompareTool` wait for F10 (Tier 2).
- **2026-11-07 to 2026-11-08 (Session 5: the GUI, Claude Code):**
  - Done: the `ui` package (`SearchMode`, `ResultsView`, `DetailPane`, `MainWindow`,
    `GuiController`) and `findany gui`, on branch `gui`, merged as PR #7 on Nov 8 (8 commits).
    Search mode, Ask mode with marked clickable citations and Back, New conversation, a
    "Conversations" menu that reopens a saved conversation. The GUI tests run the real JavaFX
    toolkit headless (`glass.platform=headless`, `prism.order=sw` on the test task; the OpenGL
    pipeline crashed the test JVM whenever a display existed), on both CI OSes.
  - Found and fixed (both in `docs/logs/test-failures.md`): `Indexer` never told its listeners
    the index was up to date after a request made while idle; a follow-up lost the whole first
    exchange when its search results were over the 40,000-character history limit, and the saved
    file lost the first request. Conversations now keep requests and answers, and tool calls and
    results only for the exchange in progress, never saved (design change to F12).
  - Investigated, not fixed (all in `TODO.md`): the model sometimes writes its citation entries
    twice and the parser keeps the first copy in the answer (1 run in 53 on the corpus, 3 of 3 on
    Amar's documents; a test plan is written); the agent can't look inside a folder or find one
    by name; a small sensitive file's whole text reaches the cloud model through a search
    result's best passage; answers show raw Markdown; text can't be selected in the pane.
  - Workflow: the first GUI commits were made on local `master` by mistake and moved to `gui`;
    `AGENTS.md` now says to wait after each commit and to work on a feature branch.
- **2026-11-09 to 2026-11-10 (Session 6: privacy, Claude Code):**
  - Done, on branch `privacy` (from `gui`'s tip, so it carries one `TODO.md` commit PR #7 missed;
    7 code commits, **not pushed yet**): the `privacy` package (`SensitivePattern` with four
    implementations, `Redactor`, `FolderPolicy`, `PrivacyLog`, `PrivacyLogEntry`), the
    decorators (`LlmClientDecorator`, `RedactingLlmClient`, `LoggingLlmClient`) and the factory's
    cloud chain, the facade's `setLocalOnly()`, `setRedaction()`, `getPrivacyLog()` and the
    "sources withheld" notice, `findany privacy|policy|privacy-log`, "Local model only" on the
    chips, and `SettingsDialog` (backend test, redaction toggles, privacy log). F13 and F14 are
    Implemented; Decorator, Factory and MVC too. 257 tests. Checked on the corpus with the real
    cloud model: placeholders instead of the fake card number, SIN and key; the withheld marker
    and the notice once `private/` was marked.
  - Decisions (all in `docs/logs/design-changes.md`): a message is withheld whole when any of its
    source files is local-only; a folder inside a search folder can be marked; policy changes are
    refused in a read-only process; a request that can't be logged isn't sent; the toggles are
    saved in `settings.json`; the chain is built in `createClient()` so scripted test clients are
    decorated too; a number glued to a letter, underscore or hyphen isn't a card number or SIN.
  - The deadline moved to **November 15**; the freeze is Nov 14–15.
  - Next: push `privacy` and open its PR into `master` (merge without squashing). Then
    `TODO.md` items and Tier 2 in order (F05, F04, live watcher, F10) until the freeze. Still
    owed: the Ollama check; the test for the duplicated citation entries; a decision on the
    sensitive-file leak; finer withholding (only the local-only entries of a search result).
- **2026-11-12 (Session 7: smaller search results, Claude Code):**
  - Done, on branch `optimization` (3 code commits): the three ideas agreed in "Plan: smaller
    search results" above, so that section's "nothing is implemented yet" is out of date. All
    three are in `SearchTool`, each with an entry in `docs/logs/design-changes.md`. 271 tests.
    1. A passage is given once per request (`ShownPassages`, passed through
       `ToolRegistry.execute()` and `Tool.execute()`): a later search writes
       `Best passage (page 1): already given above, in the results for "…".`
    2. Files with the same `contentHash` share one entry, with `Same content at: <path>` lines.
    3. The first three entries (`FULL_PASSAGES`) get whole passages; later ones get one line of
       at most 200 characters (`PART_CHARS`) under `Part of the best passage`.
  - Measured from the privacy log, on Amar's documents with the cloud model, same searches before
    and after: "What's my cumulative GPA?" (two searches) went from 34,019 to 12,385 characters
    in its last request (64% less); "Which version of my resume is the most recent one?" (one
    search of 12 files) from 21,456 to 10,321 (52% less). Whether the answers stayed as good was
    Amar's to judge; the log only shows what was sent.
  - To check a run: `tail -n 1 <FINDANY_HOME>/privacy-log.jsonl | grep -o 'already given
    above[^.]*\.'` prints the notes of the last request. Nothing shows them for a local model.
  - Noticed, not done (ask before doing one):
    - A part isn't recorded as given, so a second search sends the same parts again (seven of
      them, about 1,400 characters, in the GPA run).
    - The note is about 70 characters plus the query, so on a very short passage it saves
      nothing; such a passage could come whole.
    - The transcript's two files weren't joined in the GPA run, probably the same text in
      different bytes (unchecked); joining by extracted text would catch that.
    - The result's first line counts files, not entries, so "2 files" can have one entry.
    - `--verbose` could say how many passages a search left out.
  - Discussed, not agreed: a character budget per result (recommendation: skip; lowering
    `FULL_PASSAGES` to 2 gives nearly the same); embeddings (F05) would allow five results and
    smaller chunks, roughly 3,000 characters per result by arithmetic, unmeasured.
  - Local model: only `qwen3:8b` was installed; Amar started pulling `qwen3:14b` (9.3 GB) on
    2026-11-12. Next: ask the GPA and resume questions with it and read `ollama ps` for the
    loaded size and any spill to the CPU. Requests are now about 2,600 to 3,100 tokens (at four
    characters per token) against `OLLAMA_NUM_CTX` of 16,384, which is still a placeholder.
  - Next: merge the `optimization` pull request (no squash), the 14B check, then `TODO.md` items
    and Tier 2 until the freeze on Nov 14.
