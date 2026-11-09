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

### The answer format, and `AnswerFormatException` (2026-11-04, Slice 2)
- **Original design:** the model "writes an answer with citations in a structured format, which
  is parsed" (F08); the format isn't specified. SD03: if parsing fails, the model is asked once to
  reformat.
- **Implemented change:** the final reply is the answer's text with `[n]` markers, then a
  `CITATIONS:` line and one entry per marker with `file:`, `page:` and `quote:` on their own
  lines. `ResponseParser.parseAnswer()` forgives layout (Markdown decoration, code fences,
  "Sources", a quote in quotation marks or over several lines) and renumbers markers to the
  citations' order. It throws the new checked `AnswerFormatException`, listing every problem, when
  the reply is empty, a marker has no entry, an entry is listed twice, or an entry has no file, a
  relative path, or a page that isn't a number. A reply with no markers and no entries is an
  answer without citations (a clarifying question). A citation without a quote is kept and left
  for the verifier to mark unverified. The parser leaves `Answer.sourceFiles` empty, because it
  has no index to build `SearchResult`s from; `AgentController` fills it.
- **Reason:** plain lines need no escaping, and escaping Windows paths and quotation marks is
  where models break JSON. The exception's message tells the model what to fix.
- **Impact:** F08, F09; `ResponseParser`, `PromptBuilder` (describes the format),
  `AgentController` (catches the exception).

### `PromptBuilder`: file text marked as data, and a wider `buildReformatRequest()` (2026-11-04, Slice 2)
- **Original design:** `buildRequest(conversation, toolSpecs, today)`,
  `buildReformatRequest(conversation)` and `buildDiffSummaryRequest(diffResult)` (cd05).
- **Implemented change:** `buildRequest()` sends a system message (the role, today's date with
  the weekday, how to work, the answer format) and then the conversation. A tool result that
  carries file text is wrapped between `[FILE CONTENT START]` and `[FILE CONTENT END]`, the
  system message says that text is data and never instructions, and an end marker inside file
  text is altered so a file can't close the block. A failed call's error stays outside the
  markers. `buildReformatRequest(conversation, toolSpecs, today, problems)` is the same request
  plus a closing user message with the parser's problems and the format; that message isn't added
  to the conversation. `buildDiffSummaryRequest()` isn't implemented (F10, Tier 2).
- **Reason:** the test corpus has a prompt-injection note, and files are untrusted input. The
  reformat request needs the tools because the history holds tool calls, which some providers
  reject when no tools are defined, and the problems tell the model what to correct.
- **Impact:** F07, F08, F09, F12; `PromptBuilder`, `AgentController`.

### Citation verification rules made exact (2026-11-04, Slice 2)
- **Original design:** F09: the quote is searched on the cited page, ignoring whitespace, and
  each citation is `VERIFIED`, `UNVERIFIED` or `SOURCE_CHANGED`; `Answer.isVerified()` is false
  when the citations couldn't be parsed.
- **Implemented change:** `SOURCE_CHANGED` when the file is gone, or its size or modification
  time (to the millisecond) differs from the index, which is the rescan's own test; it wins over
  the other statuses. Whitespace includes non-breaking spaces. A quote may run across chunks of
  one page. A citation with page 0 is searched in the whole file (see "Files without pages"); a
  page number on a file without pages, a file that isn't indexed and an empty quote are
  `UNVERIFIED`. `Answer.isVerified()` is true only when there is at least one citation and all
  are `VERIFIED`. `verify()` and `verifyCitation()` throw `IOException` when the index can't be
  read.
- **Reason:** the report leaves these cases open, and an unreadable index must not look like an
  unverified quote.
- **Impact:** F09; `CitationVerifier`, `Answer`. `TODO.md` lists two of these rules to reconsider.

### Conversations: trimming by exchange, and saving without the key (2026-11-04, Slice 2)
- **Original design:** `Conversation.trimToFit(maxChars)` drops the oldest exchanges (F12);
  `ConversationStore` has only its `conversationsDirectory`.
- **Implemented change:** an exchange is a request and everything up to the next request, and is
  dropped whole, so a tool result never loses the call it answers; the latest exchange is always
  kept. A failed tool call is stored as `Error: <reason>`. `ConversationStore` writes one JSON
  file per conversation (model, messages, tool calls, latest results), never the API key, and
  takes a `SettingsStore` to supply the key when loading. `listAll()` is ordered by the files'
  modification times and skips a file it can't read, with a warning in the log. An id must be a
  safe file name.
- **Reason:** providers reject a tool result without its call; the key lives only in `.env`.
- **Impact:** F12; `Conversation`, `ConversationStore`. `TODO.md` lists two weaknesses of the
  trimming.

### Small members added for conversations and answers (2026-11-04, Slice 2)
- **Original design:** cd05 draws `Conversation`, `Answer` and `Citation` with their fields, and
  names the three statuses in a note.
- **Implemented change:** `Conversation.getId()`, `getLastResults()`, `setLastResults()` and a
  constructor that restores a saved conversation; `VerificationStatus` is the enum for the note's
  statuses; `Answer` and `Citation` are records, with `Answer.withCitations()`,
  `Citation.withStatus()` and a `Citation` constructor that starts unverified;
  `ConversationStore.getConversationsDirectory()`; `ResponseParser.CITATIONS_HEADER`.
- **Reason:** the store, the verifier and the prompt need them.
- **Impact:** none on the patterns. The class diagram gets these at the freeze.

### `AgentController`: three more collaborators, and what each ending returns (2026-11-05, Slice 2)
- **Original design:** cd05 gives `AgentController` a `ToolRegistry`, `PromptBuilder`,
  `ResponseParser`, `CitationVerifier` and `maxSteps`. SD03 ends the loop with a verified answer,
  or with "best results so far, with a notice" at the step limit.
- **Implemented change:** the constructor also takes the `SearchIndex` (to turn the paths of an
  answer's files into the `SearchResult`s that `Answer.sourceFiles` and
  `Conversation.lastResults` hold), `maxConversationChars` (the limit for `trimToFit()`) and a
  `Clock` (today's date for the prompt). `run()` throws `LlmException` and `IOException`. What it
  returns:
  - a parsed answer lists the files it cites, each once, in citation order;
  - at the step limit, and when the reply can't be parsed even after the one reformat request,
    it lists every file the tools returned, with a notice; the unparsed reply is returned as it
    is, with no citations, so `isVerified()` is false;
  - an empty reply is asked for again once, then reported as "The model gave no answer."

  The reformat request doesn't count against `maxSteps`, and a tool call sent in reply to it
  isn't run. The conversation gets exactly one closing reply per request, the one the answer came
  from; a rejected reply is shown to the model through a copy of the conversation. At the step
  limit a fixed sentence is stored as the reply. `summarizeDiff()` waits for F10.
- **Reason:** the design leaves these cases open. One closing reply per request keeps the history
  a sequence of complete exchanges, which strict chat formats require.
- **Impact:** F07, F08, F09, F12; `AgentController`. cd05 and SD03 get these at the freeze.

### The instant-search fallback, conversation ids, and the agent's limits (2026-11-05, Slice 2)
- **Original design:** when the client throws `LlmException`, `FindAnythingService.ask()`
  completes the future with instant search results and a "model unavailable" notice (SD03).
  `newConversation()` returns a `Conversation`.
- **Implemented change:** as designed, with these details. The fallback also covers a client that
  can't be built (for example no API key). Its answer has a fixed text, at most 10 files, and a
  notice that starts with `FindAnythingService.MODEL_UNAVAILABLE` and gives the model's error.
  The fallback is recorded in the conversation as the reply, and its files become `lastResults`.
  A conversation's id is the time and four random hex digits, such as `20261105-143012-3f9a`, and
  the conversation is first saved with its first answer. The facade's constructor takes a
  `ConversationStore` and a `Clock`. `open()` builds the `ToolRegistry` (with `SearchTool` and
  `ReadExcerptTool`) and the `AgentController`, with these limits as constants: 8 steps, 6,000
  characters per excerpt, 40,000 characters of history.
- **Reason:** the design names the behaviour but not these values; a missing key is as much
  "model unavailable" as a refused connection.
- **Impact:** F07, F12; `FindAnythingService`, `CliApp` (composition root).

### Paused and unavailable folders are skipped inside `SearchEngine` (2026-11-05, Slice 2)
- **Original design:** `SearchEngine` holds only the `SearchIndex` (cd04), and `ReadExcerptTool`
  only the `SearchIndex` and `maxExcerptChars` (cd05). The report doesn't say where a paused
  folder is left out of searches; Slice 1 did it in `FindAnythingService.search()`.
- **Implemented change:** `SearchEngine` takes the `IndexConfig` and drops files that aren't in
  an active folder, so the facade, the fallback and the agent's `SearchTool` all skip them
  through the one method; the facade's own filter is gone. `ReadExcerptTool` takes the
  `IndexConfig` too and refuses a file in such a folder with an error for the model. New method:
  `IndexConfig.isInActiveFolder(Path)`.
- **Reason:** the agent's tools call `SearchEngine` and `SearchIndex` directly, so with the
  filter in the facade they returned and read files from paused folders. Found in review while
  writing `ask()`, before any release.
- **Impact:** F01, F06, F07, F08; `SearchEngine`, `ReadExcerptTool`, `IndexConfig`,
  `FindAnythingService`.

### `findany ask`: no interactive session, and statuses in words (2026-11-05, Slice 2)
- **Original design:** `findany ask "<request>"` prints the answer with numbered citations
  marked `[✓]` or `[?]`; `--verbose` prints the agent's steps; `findany ask` with no arguments
  starts an interactive session; `--continue` continues the last conversation (F07–F09, F12).
- **Implemented change:** `ask` needs a request; there is no interactive session, and follow-ups
  use `--continue`. Each source line also gives its status in words ("verified", "unverified: the
  quote was not found there", "unverified: the file has changed since it was indexed"); a changed
  source is marked `[?]`. `--verbose` prints the steps on standard error. `--json` (added for
  Stage 3's behaviour tests) prints the conversation id, model, request, answer, `verified`,
  `fallback`, citations with statuses, source files, notices and the request's tool calls. A
  fallback exits with 0.
- **Reason:** a console that can't show the check mark prints `?` in its place, which would make
  a verified citation look unverified. The interactive session is left out for time.
- **Impact:** F07, F08, F09, F12; `CliApp`.

### F12: saved conversations in a menu, reopened without verification marks (2026-11-07, Slice 3)
1. **Original Stage 1 feature and ID:** F12 Conversation follow-ups. The GUI keeps past
   conversations in a drawer with a "New conversation" button; a reopened conversation shows its
   exchanges.
2. **Implemented feature and its behavior:** in Ask mode the toolbar has "New conversation" and a
   "Conversations" menu that lists the saved conversations, most recent first, each as its start
   time and first request. Choosing one shows its requests and answers in the detail pane, puts
   the last answer's files in the table, and the next request continues it. Each reopened citation
   is still a link that opens the file at the quote, but carries no verified or unverified mark;
   its tooltip says that verification isn't kept for saved conversations. The CLI continues the
   most recent conversation with `ask --continue`.
3. **What changed and why:** a menu instead of a drawer fits the one-window GUI (status: Modified).
   The marks are missing because `ConversationStore` saves the messages and the last results, not
   the verification statuses; verifying again when a conversation is reopened would cost an index
   lookup per citation and could differ from what the user saw at the time.
4. **How the user need is still met:** any saved conversation can be reopened, read and continued
   from the GUI or the CLI.
5. **Affected use cases, classes, methods, and design patterns:** UC for F12;
   `GuiController.onOpenConversation()` (reads each saved reply again with `ResponseParser`),
   `MainWindow.showConversations()`, `DetailPane.showPastExchange()`. Patterns unchanged.
   Diagrams updated: not yet (cd01 at the freeze).

### Conversations keep requests and answers, not old tool results (2026-11-08, Slice 3)
- **Original design:** F12 keeps everything: "Each request, tool result, and answer is added to
  the conversation, which is saved after every answer. The whole conversation, trimmed to fit, is
  sent with each new request, so the model can refer to earlier results." SD03 draws the same.
- **Implemented change:** tool calls and tool results are kept only for the exchange in progress.
  When the next request starts, `AgentController.run()` calls the new
  `Conversation.dropToolMessages()`, which reduces every earlier exchange to its request and its
  closing reply. `ConversationStore.save()` never writes tool calls or results, so a saved
  conversation holds only requests and answers (plus the model and the last answer's files). A
  file saved before this change still loads, and loses its tool messages at its next request.
- **Reason:** found in real use on 2026-11-08 (see `test-failures.md`). Tool results were 89 to
  98 percent of three real conversations; one question about taxes stored about 78,000 characters
  of search results. That is twice the 40,000-character history limit, so the follow-up dropped
  the whole first exchange and the model no longer knew what it had answered. Old results also go
  stale when files change, are sent again on every later call, and left copies of file text in
  the conversation files. The answer already says what was found, and a follow-up that needs more
  searches or reads again, which uses the files as they are now.
- **Impact:** F12; `Conversation`, `AgentController`, `ConversationStore`; SD03. A follow-up now
  sees earlier requests and answers but not earlier search results or excerpts. `findany ask
  --json` is unchanged: it reads the latest request's tool calls from the conversation in memory,
  where they stay until the next request. Not solved here: a single exchange can still outgrow a
  local model's context, and `trimToFit()` still edits the kept history (both in `TODO.md`).
  Diagrams updated: not yet (SD03 at the freeze).

### Redaction: placeholders, category names, and what a pattern matches (2026-11-09, Slice 3)
- **Original design:** `Redactor.redact(text): RedactionResult` applies every enabled
  `SensitivePattern`, with one implementation per category: card numbers and SINs (both checked
  with the Luhn checksum), private keys, and API keys. Matches "are replaced with placeholders".
  The design gives no placeholder format, no category names, no `RedactionResult` contents, and
  no rule for what counts as a match.
- **Implemented change:** a match becomes `[redacted: <category>]`. The categories are
  `private-key`, `card-number`, `sin` and `api-key`, applied in that order. `RedactionResult` is
  a record of the redacted text and the categories that matched. What matches:
  - `CardNumberPattern`: 13 to 19 digits, together or in groups separated by single spaces or
    hyphens, passing Luhn.
  - `SinPattern`: 9 digits written the same way, passing Luhn.
  - `PrivateKeyPattern`: a PEM block from `-----BEGIN ... PRIVATE KEY-----` to its END line, or to
    the end of the text when an excerpt cut the block short.
  - `ApiKeyPattern`: keys with a well-known prefix (`sk-`, `ghp_` and its family, `github_pat_`,
    `AKIA`, `xoxb-` and its family, `AIza`).
  Added members: `SensitivePattern.builtIn()` (the four patterns in order),
  `Redactor.placeholder()`, `getCategories()` and `isCategoryEnabled()`, and the package-private
  `Luhn` helper both number patterns use. `setCategoryEnabled()` rejects a category that doesn't
  exist with a message listing the ones that do.
- **Reason:** the code needs concrete rules, and the CLI and the settings dialog need names to
  show and accept. A number is matched over whole digit groups so that a card number followed by
  another number on the same line is still found.
- **Impact:** F14; `privacy` package. A key with no recognizable prefix isn't found, and about one
  in ten random numbers of the right length passes Luhn and is redacted needlessly, which the
  design accepts ("False positive: harmless, since the text is only redacted").

### The privacy log's entries and file, and saved privacy settings (2026-11-09, Slice 3)
- **Original design:** `PrivacyLog` has a `logFile` with `append(entry)` and `getEntries(limit)`;
  a log entry shows "its timestamp, the excerpts sent, and any redactions". `PrivacyLogEntry` has
  no box in any diagram, and the file's place and format aren't given. `SettingsStore` has
  `loadFolderPolicy()` and `saveFolderPolicy()`, and nothing for the redaction toggles.
- **Implemented change:**
  - `PrivacyLogEntry` is a record of the timestamp, the model's name, the request's messages as
    `Excerpt`s (role, source files, text as sent), and the redacted categories. It has its own
    `Excerpt` type because `llm` depends on `privacy`, so `privacy` can't use `llm.Message`.
  - The log is `privacy-log.jsonl` in the app's data folder: one JSON object per line, oldest
    first, only ever appended to. `getEntries(limit)` returns the newest first and skips a line it
    can't read, with a warning. `append()` throws `IOException` when the file can't be written.
    Added `getLogFile()`.
  - `settings.json` gets a `privacy` section with `localOnlyFolders` and
    `disabledRedactionCategories`. `SettingsStore` gets
    `loadDisabledRedactionCategories()` and `saveDisabledRedactionCategories()` beside the two
    designed methods. `FolderPolicy` gets `getLocalOnlyFolders()` and a constructor from a list.
- **Reason:** the design leaves these open. The toggles must be saved because every CLI run is a
  new process: without saving, `findany privacy redact --disable <category>` would do nothing.
  One line per request keeps the log readable and editable by a person, and appending never
  rewrites what was recorded.
- **Impact:** F13, F14; `privacy` package, `SettingsStore`. The log has no size limit or rotation.

### The privacy decorators: where the chain is built, and what the design left open (2026-11-09, Slice 3)
- **Original design:** for a cloud backend `LlmClientFactory` builds `RedactingLlmClient` →
  `LoggingLlmClient` → `LangChain4jClient`; the factory holds the `redactor`, `folderPolicy` and
  `privacyLog`. `LoggingLlmClient` has only a `privacyLog` field. SD04 appends the log entry
  before the request is sent and replaces a local-only message with
  `"[withheld: local-only source]"`. The design doesn't say what happens when the log can't be
  written, whether the connection test is logged, or what a logged message's text holds.
- **Implemented change:**
  - `LlmClientFactory` takes the three privacy objects and a `Clock` in its constructor.
    `createClient()` wraps whatever `build()` returns when the backend is CLOUD. An earlier entry
    said `build()` itself would be wrapped; wrapping in `createClient()` keeps the tests' scripted
    clients (which replace `build()`) under the decorators, so the whole test suite now runs
    through the chain.
  - `LoggingLlmClient` also takes the conversation's `ModelSettings` (for the model's name in
    each entry) and the `Clock` (for its time). A logged message's text is its content followed
    by a `[tool call: name {arguments}]` line for each tool call it made, since those are sent too.
  - If the entry can't be written, the request is **not sent**: `LoggingLlmClient` throws
    `LlmException` naming the log file, and `ask()` then falls back to instant search with that
    reason. A cloud request must never go out unrecorded.
  - The connection test isn't logged or redacted: `LangChain4jClient.testConnection()` sends its
    own fixed one-line prompt, which holds no user data.
  - `RedactingLlmClient.WITHHELD` is the marker. A withheld message keeps its source files (they
    aren't sent; the log shows whose text was withheld) and is not scanned for patterns. A
    message is withheld whole when any one of its source files is local-only, as SD04 draws it,
    so a search result that lists one local-only file hides its other files too.
- **Reason:** the design leaves these open; each choice is the one that can't leak or go
  unrecorded.
- **Impact:** F13, F14; `llm` package, `CliApp.createService()`. From this change on, every
  request to a cloud model is redacted and written to `privacy-log.jsonl`. Dropping only the
  local-only entries of a search result needs tool results to carry per-file structure; it is in
  `TODO.md`.

## Pending (decide during implementation; write the entry if it happens)

- F03: live file watching replaced by rescans at startup, on demand, and on a timer.
- F04, F05, F10: Tier 2 features that may end up *Not implemented* in Stage 2.
- GUI: side-by-side comparison, privacy log viewer, editing exclusions in the GUI (CLI-only).
