# Design changes

Source for sections 3 and 4 of the Stage 2 report. Every difference between the Stage 1 design
and the code needs an entry here, written when the change is made. The Stage 2 instructions say
unexplained differences aren't acceptable.

Each change also needs the class, use-case or sequence diagrams updated where it makes them wrong
(UMLet only). Keep changes few: each one costs diagram work.

## Templates

**Feature change** (added, replaced, removed, or substantially modified):

```
### Fxx: short title (status: Modified / Replaced / Removed / New / Not implemented)
1. Original Stage 1 feature and ID (or "New"):
2. Implemented feature and its behavior:
3. What changed and why:
4. How the user need is still met, or how the scope changed:
5. Affected use cases, classes, methods, and design patterns:
Diagrams updated:
```

**Design-pattern change:**

```
### Pattern: short title
6. Original pattern (if any):
7. Pattern actually implemented:
8. Participating classes and their roles:
9. Design problem addressed:
10. Why it's appropriate and how it affects the architecture:
```

**Other architecture or class change:** Original design → Implemented change → Reason → Impact.

## Decided before implementation

### Cloud model reached through OpenRouter
- **Original design:** `LangChain4jClient` adapts LangChain4j's Anthropic chat model; the cloud
  backend is Anthropic's API directly.
- **Implemented change:** `LangChain4jClient` adapts LangChain4j's `OpenAiChatModel` pointed at
  OpenRouter's OpenAI-compatible API. Cloud models (including Claude models) are chosen by their
  OpenRouter model ID. Ollama keeps LangChain4j's Ollama module.
- **Reason:** payment for Anthropic Console credits couldn't be completed.
- **Impact:** F13, F14. Requests now pass through a second company (OpenRouter) before the model
  provider; redaction and the privacy log still apply to everything sent. OpenRouter is set to
  not log prompts and to avoid providers that train on data. The Adapter pattern is unchanged.

### API key in a local `.env` file
- **Original design:** F13 has the user enter the API key in Settings.
- **Implemented change:** the key is read from `.env` (git-ignored) or the environment;
  `.env.example` is committed with placeholders.
- **Reason:** the Stage 2 instructions require keys in a local configuration file.
- **Impact:** F13, `SettingsDialog`, `ModelSettings`.

### Files without pages: DOCX, TXT, MD
- **Original design:** each chunk keeps its page or slide number.
- **Implemented change:** PDF pages and PowerPoint slides are numbered; DOCX, TXT and MD are one
  unnumbered "page", so their citations name the file but no page. `CitationVerifier` searches the
  whole file for them.
- **Reason:** these formats store no page boundaries (verified with Tika 4.1.0).
- **Impact:** F02, F09, F11; `Chunk`, `Citation`, `CitationVerifier`.

### PowerPoint slide order corrected in the extractor
- **Original design:** not covered.
- **Implemented change:** `IsolatedTextExtractor` reorders slides using the deck's own slide list.
- **Reason:** Tika 4.1.0 returns slides sorted by internal ID, not deck order (verified), which
  would make slide-number citations wrong.
- **Impact:** F02, F09; `IsolatedTextExtractor` gains a small helper.

## Made during implementation

### `ContentStore` postponed (2026-10-25, Slice 1)
- **Original design:** `Indexer.indexFile()` looks the content hash up in `ContentStore` and
  reuses stored chunks and embeddings for renamed, moved or copied files; `pruneUnreferenced()`
  cleans it up.
- **Implemented change:** Stage 2 has no `ContentStore`. `Indexer` takes no `contentStore` (nor
  `embeddingModel`, until F05) and extracts every new path again; `IndexedFile.contentHash` is
  still computed and stored, so adding the store later changes nothing in the index.
- **Reason:** time. Extraction of a renamed file costs tens of milliseconds, and the store only
  matters once embeddings (F05) make re-processing expensive.
- **Impact:** F02, F03; `Indexer`, `FindAnythingService`. SD01's `ContentStore` lifeline is not
  exercised. Diagrams to update at the freeze if the store stays out.

### Bounded retry for locked files (2026-10-25, Slice 1)
- **Original design:** F03: "A file is locked while it's being written: it's retried after another
  quiet period."
- **Implemented change:** `Indexer.indexFile()` retries a file that can't be read up to three
  times (`MAX_READ_RETRIES`), then marks it `FAILED` so `index status --failed` shows it.
- **Reason:** without a bound, a permanently locked file would be retried forever and keep the
  status from ever reaching "up to date".
- **Impact:** F03, `Indexer`. The next rescan (startup, `index rebuild`) tries the file again.

### Supported formats checked before parsing (2026-10-25, Slice 1)
- **Original design:** F02 lists "supported formats such as plain text, PDF, Word, and
  PowerPoint"; unsupported formats are indexed by name only.
- **Implemented change:** `IsolatedTextExtractor` detects the type in-process from the file's
  contents (Tika's detector, no parsing) and only forks the parser for plain text, Markdown, PDF,
  Word (.docx) and PowerPoint (.pptx). Everything else is `METADATA_ONLY` without being parsed.
- **Reason:** parsing arbitrary formats (archives, video, spreadsheets) in the child process
  costs time and memory for text nobody searches; detection takes about a millisecond.
- **Impact:** F02, `IsolatedTextExtractor`. CSV and images are indexed by name only (as
  `testdata/FACTS.md` expects).

### Small members added for the CLI and tests (2026-10-25, Slice 1)
- **Original design:** the class diagrams leave out constructors, most getters and small
  helpers.
- **Implemented change:** members not drawn in the diagrams: `SearchFolder.removeRule()`,
  `IndexConfig.removeGlobalRule()`, `findFolderContaining()` and the `appDataFolder` constructor
  argument (so the app's own folder is excluded); `FindAnythingService.removeExclusion()`,
  `listFailedFiles()`, `countIndexedFiles()`, `getIndexConfig()`, a `search()` overload with a
  limit; `SearchIndex.listFiles(ExtractionStatus)`, `countFiles()`; `Indexer.removeStatusListener()`;
  `SearchFilters.NONE` and `isEmpty()`; `ExtractedDocument.numberedPages`; `ChunkHit.chunk` is
  null for a file found by name only; `IndexConfig.findFolder()` and `SearchIndex.getFile()`
  return `Optional`. `IndexerState` has `STOPPED` after `STOPPING`.
- **Reason:** `findany exclude remove` and `index status --failed` (F01, F02) need them, and the
  facade must know which folder a path belongs to.
- **Impact:** none on the patterns. The class diagram gets these at the freeze.

### `testConnection()` says why it failed (2026-10-29, Slice 2)
- **Original design:** `LlmClient.testConnection()` returns a boolean (cd06, SD04), while F13 and
  UC08 (3a) want the user told that the key is invalid, or that the local runtime isn't running,
  with setup instructions.
- **Implemented change:** `testConnection()` still returns a boolean, but throws `LlmException`
  when the backend can't be used. `true` means the backend answered, `false` that it was reached
  but replied with nothing; the exception's message says what to fix (wrong key, unknown model,
  unreachable server, timeout, rate limit, no credits). `LangChain4jClient` maps the library's
  exceptions to those messages, with cloud and local wording.
- **Reason:** a bare `false` would be a silent error.
- **Impact:** F13; `LlmClient`, `LangChain4jClient`, `FindAnythingService.testBackend()` (the
  future fails with the exception), `findany model set|test`, `SettingsDialog` in Slice 3.

### `LlmException` is checked (2026-10-29, Slice 2)
- **Original design:** SD03 names `LlmException` without saying whether it's checked.
- **Implemented change:** a checked exception, like `ExtractionException`, thrown by
  `LlmClient.complete()` and `testConnection()`, by the `LangChain4jClient` constructor (no key,
  no model name) and so by `LlmClientFactory.createClient()`.
- **Reason:** the agent loop must handle it explicitly, since SD03's fallback to instant search
  depends on catching it.
- **Impact:** F07, F13; `AgentController`, `FindAnythingService`.

### `Message` carries the system prompt and tool calls (2026-10-29, Slice 2)
- **Original design:** the cd06 note gives `Message` a role (user, assistant or tool), the content
  and the source files.
- **Implemented change:** `Message.Role` also has `SYSTEM`; an assistant message carries its
  `toolCalls`, and a tool result carries the `toolCallId` and `toolName` it answers. Two records
  describe tools in the design's own terms: `ToolCall` (id, name, arguments; the id is null when
  the backend gives none) and `ToolSpec` with nested `Parameter` and `Type` (string, integer,
  boolean, or one of listed values). All three live in `llm` with `LlmRequest`, so `llm` never
  depends on `agent` (`ToolResult` stays in `agent`).
- **Reason:** OpenAI-style APIs (OpenRouter) require the assistant's tool calls to be replayed and
  each tool result matched to a call by id; Ollama returns no ids. The system prompt has its own
  role in both APIs.
- **Impact:** F07, F08, F12; `LlmRequest`, `LangChain4jClient`, and later `PromptBuilder`,
  `AgentController` and `ToolRegistry`, which build these records.

### OpenRouter requests opt out of provider data collection (2026-10-29, Slice 2)
- **Original design:** the "Cloud model reached through OpenRouter" entry above relies on the
  OpenRouter account settings (no prompt logging, no training providers).
- **Implemented change:** `LangChain4jClient` also sends `provider.data_collection = deny` with
  every OpenRouter request, so a provider that trains on prompts is never routed to, whatever the
  account says. Prompt logging has no per-request switch and stays an account setting (the README
  says so). The client sends no referer or title headers.
- **Reason:** the code enforces the privacy claim instead of trusting a dashboard setting.
- **Impact:** F13, F14; `LangChain4jClient`.

### Small members added for the model settings (2026-10-29 to 2026-11-02, Slice 2)
- **Original design:** cd06 and cd07 draw `ModelSettings` (backend type, model name, base URL, API
  key), `LlmClientFactory.createClient()`, `SettingsStore.saveModelSettings()` and
  `FindAnythingService.testBackend()` and `setBackend()`; the `.env` entry above moves the key out
  of the settings file.
- **Implemented change:** `config/EnvFile` reads `.env`; `BackendType` is the enum the cd07 note
  names; `SettingsStore` gains a constructor taking the environment, `loadModelSettings()` and
  `defaultModelSettings()`; `ModelSettings` gains `hasApiKey()`, the two base-URL constants and a
  `toString()` that hides the key; `LlmRequest.withMessages()`, `LlmResponse.hasToolCalls()`;
  `LangChain4jClient` has a package-private constructor taking the library's chat model, for tests;
  `LlmClientFactory.build()` is the protected hook that Slice 3 wraps in the decorator chain and
  tests override; `FindAnythingService.getModelSettings()` and `resolveModelSettings()` give the
  CLI the settings to test. Precedence: saved settings (backend, model, URL) win; `.env` in
  `FINDANY_HOME`, then `.env` in the working directory, then environment variables fill the rest
  and always supply the key; with nothing saved the backend is cloud when a key is configured,
  else local. The key is never written to the settings file.
- **Reason:** `findany model set|test` (pulled forward from Slice 2, item 5) and the tests need
  them, and the design didn't say where the defaults come from once the key left Settings.
- **Impact:** none on the patterns. The class diagram gets these at the freeze.

### `ToolRegistry` checks every call against the tool's spec first (2026-11-04, Slice 2)
- **Original design:** `ToolRegistry.execute()` calls `Tool.validate()` and then `Tool.execute()`
  (SD03); an invalid call returns `ToolResult.failure()` with the errors.
- **Implemented change:** before `Tool.validate()`, the registry checks the arguments against the
  tool's `ToolSpec`: every required parameter is present, no parameter is unknown, and each value
  has its parameter's type and, for a choice, one of its allowed values. `Tool.validate()` then
  covers only what the spec can't express (a malformed date, a limit out of range) and is skipped
  when the spec check already failed. A tool name that isn't registered also returns a failure,
  naming the tools that exist. A tool that throws a runtime exception is logged and returned as a
  failure, so one broken call can't end the request. Every call is logged, including the rejected
  ones.
- **Reason:** the Command pattern's point is that every tool gets the same validation and error
  handling; without the shared check, each tool would repeat the type checks.
- **Impact:** F07, F08; `ToolRegistry`, `Tool` (its `validate()` contract). SD03's order
  (validate, then execute) is unchanged.

### The agent's search filters by type and date only (2026-11-04, Slice 2)
- **Original design:** F04: "The agent can apply the same filters through its search tool": type,
  modification date, size and location.
- **Implemented change:** `SearchTool` takes `query`, `file_type`, `modified_after`,
  `modified_before` and `limit` (1 to 20, default 5). `file_type` is one of the formats whose text
  is indexed (pdf, docx, pptx, txt, md); other files are found by name without a type filter.
  There are no size or location parameters. An empty query lists the newest files.
- **Reason:** a fixed type list makes "unknown file type" a rejectable error (F07) instead of a
  search that silently finds nothing. A location has to be checked against `IndexConfig`, which
  the class diagram doesn't give `SearchTool`, and size is rarely part of a request.
- **Impact:** F04, F07; `SearchTool`. The CLI and GUI filters (F04, Tier 2) are unaffected.

### `ReadExcerptTool` reads a long file in parts (2026-11-04, Slice 2)
- **Original design:** `ReadExcerptTool` reads a file's chunks from `SearchIndex`, limited to
  `maxExcerptChars`; the parameters aren't specified.
- **Implemented change:** the parameters are `file` (the full path from the search results),
  `page` (start at this page or slide) and `position` (start at this chunk; not together with
  `page`). An excerpt is whole chunks in order while they fit in `maxExcerptChars`, with a
  `[page N]` or `[slide N]` line before each numbered page, and ends by giving the position to
  continue from or by saying the text has ended. A file that isn't indexed or has no text, and a
  page or position the file doesn't have, are failures with the reason.
- **Reason:** Word, text and Markdown files have no pages, so a long one needs another way to be
  read past the first excerpt.
- **Impact:** F08; `ReadExcerptTool`.

### Small members added for the tools (2026-11-04, Slice 2)
- **Original design:** cd05 draws `ToolResult` with `failure()` and `ToolRegistry` with its
  `callLog` field.
- **Implemented change:** `ToolResult` is a record with a `success()` factory beside `failure()`;
  `ToolRegistry.getCallLog()` returns a copy of the log; `SearchTool.NAME` and
  `ReadExcerptTool.NAME` are the tools' names.
- **Reason:** the call log has to be readable (for `ask --json` and Stage 3's traces), and tools
  need a way to build a successful result.
- **Impact:** none on the patterns. The class diagram gets these at the freeze.

## Pending (decide during implementation; write the entry if it happens)

- F03: live file watching replaced by rescans at startup, on demand, and on a timer.
- F04, F05, F10: Tier 2 features that may end up *Not implemented* in Stage 2.
- GUI: conversation drawer, side-by-side comparison, privacy log viewer, editing exclusions in the
  GUI (CLI-only).
