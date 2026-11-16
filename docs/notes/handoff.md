# Handoff: state of the work

Update the "State" section at the end of every session, especially before switching machines
(laptop ↔ desktop).

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

## Where Stage 2 ended

Stage 2 was due Sunday, November 15, 2026. Thirteen of the fourteen features are implemented; F10
Document comparison is not. The record of what was built is in the repository:

- **The report:** `docs/stage2-report.md`, built to `docs/stage2-report.pdf` with
  `cd docs && pandoc -d stage2-pdf.yaml`. Its section 2 maps every feature to its classes and
  methods, section 4 lists every difference from the Stage 1 design with its reason, and
  section 6.3 lists the defects found and fixed.
- **The diagrams as implemented:** `docs/diagrams/stage2/`. The Stage 1 diagrams in
  `docs/diagrams/` are unchanged.
- **The demonstration:** one video, <https://youtu.be/mcVUT82xOac>, with a chapter per feature.
- **Deferred improvements:** `TODO.md`.

The course instructions are in `local/`, which is kept out of Git with `.git/info/exclude`.

The professor's Stage 1 feedback (100/100) said: *"Implement indexing lifecycle and citation
verification early; test stale/deleted files, background extraction failures and concurrent process
ownership."*

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
  keep prompt logging off and don't allow providers that train on data. Stage 2 used
  `anthropic/claude-haiku-5.5`.
- **Local model:** Ollama on the desktop (RX 7600 XT), reached from the laptop over Tailscale, with
  the context size set explicitly. Stage 2 used `qwen3:14b`.
- **Keys:** `.env` (git-ignored) with `OPENROUTER_API_KEY`, `OPENROUTER_MODEL`, `OLLAMA_BASE_URL`,
  `OLLAMA_MODEL`. Environment variables override `.env`. Never `ANTHROPIC_API_KEY`: Claude Code
  would use it instead of the subscription.
- **Root package:** `io.github.amarz45.findanything` (confirmed).
- **Entry point:** `findany` with no arguments prints help, like most command-line tools, and
  `findany gui` opens the GUI. Every other subcommand is CLI. `CliApp` is the main class; its `gui`
  command starts JavaFX with `Platform.startup(...)` (not `Application.launch()`, which would
  create the application object itself and leave no way to hand it the service) and shows a
  `Stage` it creates.
- **Workflow:** `master` takes changes only through pull requests that pass both CI checks. Group
  small commits into one PR, and don't squash merge.

## Technical notes

- **Tika:** read `docs/notes/tika/findings.md` before changing `IsolatedTextExtractor`. In short:
  - `isSuccess()` is also true for unreadable files, so check `CONTAINER_EXCEPTION`.
  - Use the XML handler for page and slide markers.
  - Turn OCR off with `{ "text-recognizers": [] }`.
  - Set `setJavaPath` from `java.home`, `-Xmx1g`, `setMaxEmbeddedCount(0)`, and quiet the child's logs.
  - Fix PowerPoint slide order.
  - Expect a non-UTF-8 Linux locale to break non-ASCII paths.

  `docs/notes/tika/ExtractorDemo.java` is a working reference, run against the whole test corpus.
- **JavaFX on the class path:** the main class must not extend `Application`, or the launch fails.
  `CliApp` is the main class, and its `gui` command calls `Platform.startup(...)`.
  JavaFX logs an "unsupported configuration" warning on the class path, which is harmless.
- **Logging:** Tika brings Log4j 2 and LangChain4j logs through SLF4J. One backend is configured
  (`log4j2.xml` in resources) so the CLI's output stays clean, plus the forked parser's own config
  (`docs/notes/tika/tika-fork-log4j2.xml`).
- **`FINDANY_HOME`:** the folder for the index, settings, conversations and logs. It defaults to
  the platform's app-data folder (`~/.local/share/find-anything`, `%LOCALAPPDATA%\FindAnything`),
  which built-in exclusions always skip. Tests get a folder under `build/`.
- **The test corpus:** `testdata/corpus/` (25 files), with every planted fact and its page or slide
  in `testdata/FACTS.md`. Includes a 12-slide deck that Tika misorders, a prompt-injection note,
  fake card/SIN/key data in `private/`, a credential path and an Office lock file (both must be
  excluded), conflicting dates, and broken files. Modification times aren't kept by Git.
- **The system Java on the desktop** (`/usr/lib/jvm/java-25-openjdk`) is Fedora's headless
  package, on which the GUI's "Open file" and "Show in folder" report that opening files isn't
  supported. Run the launcher with `JAVA_HOME=/home/amar/.jdks/temurin-25.0.5`.
- **Building the PDFs:** `docs/pdf.yaml` and `docs/stage2-pdf.yaml` name the font
  (`mainfont: Libertinus Serif`, Typst's default), because Typst 0.15.1 refuses the empty font
  list that Pandoc 3.7's template passes.

## Stage 3

Stage 3 (due Dec 8) adds full tests and KUMA behaviour tests. KUMA needs Python 3.10+ and a thin
adapter that runs `findany ask --json`. The user still needs to ask the professor about KUMA credits.

Already in place for it:
- `findany ask --json`
- `--continue`
- `FINDANY_HOME`
- interfaces with constructor injection
- `ToolRegistry`'s call log
- `testdata/`
- the defects table in section 6.3 of the Stage 2 report, for Stage 3's discussion of failures

Moved to Stage 3 during Stage 2: most tests, static analysis (Error Prone, NullAway), ArchUnit,
polish, and a check of live watching on Windows.

Noted for Stage 3's tests (Amar, 2026-11-10): the privacy tests should use realistic sensitive
data, not stand-ins. `testdata/corpus/home-like/.ssh/id_ed25519` is the line "placeholder: not a
real key", so today the built-in exclusion is tested only on the path and `PrivateKeyPattern` only
on hand-written PEM blocks. Throwaway keys can be generated for the corpus and the unit tests
(`ssh-keygen -t ed25519`, `-t rsa`, with `-m PEM` for the PKCS#1 form, plus PKCS#8 and PuTTY
`.ppk` files, and a key pasted inside a note), together with card numbers of each brand, SINs in
each spelling, and keys with each prefix `ApiKeyPattern` knows, so redaction and the exclusions are
checked on the real formats. Generated keys only, never one that was ever used.

## State

*(Update at the end of each session: what's done, what's in progress, what's next, anything
surprising.)*

- **2026-11-15 (the Stage 2 submission, Claude Code):**
  - Done: the Stage 2 report and its PDF (40 pages), the Stage 2 diagrams, the demonstration
    video, and the README's Stage 2 links, supported formats and build commands. `./gradlew build`
    passes with 324 tests.
  - The working logs and the traceability table that fed the report, and the skill that drafted
    log entries, were removed once the report held their content; the per-session history that
    was in this file went with them. Git has all of it.
  - Next: Stage 3.
