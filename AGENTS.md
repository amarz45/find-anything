# Find Anything: instructions for coding agents

Find Anything is a Java 25 desktop app (JavaFX GUI and a `findany` CLI) that indexes a user's
files and answers questions about them with an AI agent. It's the EECS3311 course project of
Amar Al-Zubaidi. Stage 2 (implementation) is due **November 10, 2026**.

Read `docs/notes/handoff.md` at the start of a session for the current state, scope and next steps.

## Sources of truth

- **The design:** `docs/stage1-report.md` and the UMLet diagrams in `docs/diagrams/`. Implement
  what they describe, with their names: classes, methods, fields and packages (`fileWatcher`,
  `searchIndex`, `addUserMessage()`, and so on). Don't rename or shorten them.
- **The Stage 2 requirements:** `local/Project-Stage2-Instruction.pdf` (not in Git; see the handoff).
- **Tika behaviour:** `docs/notes/tika/` (tested findings and a working reference extractor).
- **Test data:** `testdata/corpus/` with its fact sheet `testdata/FACTS.md`.

## Commands

| What | Command |
|---|---|
| Build, check formatting, run all tests | `./gradlew build` |
| Fix formatting | `./gradlew spotlessApply` |
| Run tests only | `./gradlew test` |
| Run the app | `./gradlew run --args="<findany arguments>"` |
| Make the `findany` launcher | `./gradlew installDist` (then `build/install/findany/bin/findany`) |

Before saying a task is done, run `./gradlew build` and make sure it passes.

## Code layout

Root package `io.github.amarz45.findanything`, with the ten design packages under it: `ui`,
`core`, `config`, `indexing`, `storage`, `embedding`, `search`, `agent`, `llm`, `privacy`.
Dependencies point one way, from `ui` through `core` to the lower packages, with no cycles.
Tests mirror the main package structure under `src/test/java`.

## Rules

- **Library types stay inside adapters.** Lucene stays inside `SearchIndex`; Tika inside
  `IsolatedTextExtractor`; LangChain4j inside `LangChain4jClient` (and `LocalEmbeddingModel`);
  JavaFX inside `ui`. Other classes use the design's own types.
- **Constructor injection, no static singletons or global state.** Every collaborator is passed
  in, so tests can substitute fakes (for example a scripted `LlmClient`).
- **Threads** (design report, section 10):
  - Only the indexing worker thread writes to `SearchIndex` (and `ContentStore`).
  - The JavaFX UI thread never waits on slow work. Slow facade methods return `CompletableFuture`,
    and results go back to the UI with `Platform.runLater()`.
- **One process owns the index.** The first process takes the Lucene write lock; a second
  process opens the index read-only, runs no watcher or worker, and refreshes every few seconds.
- **One model per conversation.** A `Conversation` keeps its `ModelSettings` for life, and
  `AgentController` gets the client for that conversation on every call. Never replace this with
  one shared client: it would leak local-only content to a cloud model.
- **Privacy order for cloud models:** `RedactingLlmClient` → `LoggingLlmClient` →
  `LangChain4jClient`, built only by `LlmClientFactory`. Local models get no decorators.
- **Keep `ToolRegistry`'s call log.** Stage 3's agent tests need those traces.
- **File paths are `java.nio.file.Path`, never `String`.** Compare paths as `Path`s (Windows is
  case-insensitive). Don't follow symbolic links or junctions when scanning.
- **No `module-info.java`.** Tika's forked parser starts its child JVM with the parent's class path.
- **Tests and experiments use `FINDANY_HOME`** for the index, settings and logs, never the user's
  real data folder. The Gradle test task sets it automatically.
- **Secrets:** API keys come from `.env` or environment variables (`.env.example` lists the
  names). Never read `.env`, print a key, write one to a file, or put one in a log, test or commit.
- **Errors are never silent.** Every failure the design lists (unreadable file, model unavailable,
  invalid tool call, and so on) produces the status or message the design describes.

## Working with the IDE

IntelliJ IDEA is open on this project with the **IDE Index MCP Server** (`intellij-index` tools).
Use it for finding references, definitions, implementations and hierarchies, and for renames, in
preference to text search. After editing files outside the IDE, use its sync tool before relying
on its diagnostics.

## Scope and changes

- Stage 2 scope is in `docs/notes/handoff.md` (Tier 1 must work; Tier 2 if time allows). Don't add
  features or abstractions beyond the design.
- **Stop and ask before changing the design** (adding, removing or renaming a class, method or
  pattern, or changing behaviour the report describes). When the change is approved, add an entry
  to `docs/logs/design-changes.md`.
- When a feature's code lands, update its row in `docs/traceability.md`.
- When a test or check catches a real bug, add an entry to `docs/logs/test-failures.md`.

## Commits

Small commits with descriptive messages, each one building and passing tests. The course requires
at least five meaningful **code** commits per week (implementation or test code; documentation,
formatting-only and empty commits don't count), so commit each finished piece of code separately.
