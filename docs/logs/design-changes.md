# Design changes

Source for sections 3 and 4 of the Stage 2 report. An entry belongs here when the Stage 1
design would describe the code incorrectly: the code does something differently, leaves out
something the design has, or changes a class the design draws. Members and values added within
the design (constructors, getters, helpers, limits, message texts) are not design changes and
aren't listed.

## Templates

**Feature change** (added, replaced, removed, or substantially modified):

```
### Fxx: short title
1. Original Stage 1 feature and ID (or "New"):
2. Implemented feature and its behavior:
3. What changed and why:
4. How the user need is still met, or how the scope changed:
5. Affected use cases, classes, methods, and design patterns:
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
  provider; redaction and the privacy log still apply to everything sent. Every request tells
  OpenRouter not to route to providers that train on prompts. The Adapter pattern is unchanged.
  Diagrams: cd06 (its note), SD08.

### API key in a local `.env` file
- **Original design:** F13 has the user enter the API key in Settings.
- **Implemented change:** the key is read from `.env` (git-ignored) or the environment;
  `.env.example` is committed with placeholders. The settings dialog shows whether a key is
  configured and never asks for one.
- **Reason:** the Stage 2 instructions require keys in a local configuration file.
- **Impact:** F13, UC08; `SettingsDialog`, `ModelSettings`, `SettingsStore`; the new
  `config/EnvFile` reads the file. Diagrams: SD08.

### Files without pages: DOCX, TXT, MD
- **Original design:** each chunk keeps its page or slide number.
- **Implemented change:** PDF pages and PowerPoint slides are numbered; DOCX, TXT and MD are one
  unnumbered "page", so their citations name the file but no page. `CitationVerifier` searches the
  whole file for them.
- **Reason:** these formats store no page boundaries (verified with Tika 4.1.0).
- **Impact:** F02, F09, F11; `Chunk`, `Citation`, `CitationVerifier`.

## Made during implementation

### `ContentStore` postponed (2026-10-25)
- **Original design:** `Indexer.indexFile()` looks the content hash up in `ContentStore` and
  reuses stored chunks and embeddings for renamed, moved or copied files; `pruneUnreferenced()`
  cleans it up.
- **Implemented change:** Stage 2 has no `ContentStore`. `Indexer` takes no `contentStore` and
  extracts every new path again; `IndexedFile.contentHash` is still computed and stored, so
  adding the store later changes nothing in the index. `SearchIndex.isReferenced(contentHash)`,
  which only served the store, isn't built.
- **Reason:** time. Without the store, a renamed file costs one more extraction (tens of
  milliseconds) and one more embedding (about 27 passages a second).
- **Impact:** F02, F03, F05; `Indexer`, `FindAnythingService`, `SearchIndex` (see "Vectors in
  `SearchIndex`" below). A renamed or copied file is extracted again. It is embedded again too,
  unless a file with the same content is waiting to be embedded at the same time: those share
  one embedding. Diagrams: cd02, cd03, SD01, SM01, AD01.

### Bounded retry for locked files (2026-10-25)
- **Original design:** F03: "A file is locked while it's being written: it's retried after another
  quiet period."
- **Implemented change:** `Indexer.indexFile()` retries a file that can't be read up to three
  times (`MAX_READ_RETRIES`), then marks it `FAILED` so `index status --failed` shows it.
- **Reason:** without a bound, a permanently locked file would be retried forever and keep the
  status from ever reaching "up to date".
- **Impact:** F03, `Indexer`. A failed file is tried again when it changes or after
  `index rebuild`. Diagrams: SD01.

### `testConnection()` says why it failed (2026-10-29)
- **Original design:** `LlmClient.testConnection()` returns a boolean (cd06, SD08), and F13 and
  UC08 (3a) tell the user that the key is invalid, or that the local runtime isn't running, with
  setup instructions.
- **Implemented change:** `testConnection()` still returns a boolean, and throws `LlmException`
  when the backend can't be used. `true` means the backend answered, `false` that it was reached
  but replied with nothing; the exception's message says what to fix (wrong key, unknown model,
  unreachable server, timeout, rate limit, no credits).
- **Reason:** the message F13 asks for needs the cause, which a bare `false` can't carry.
- **Impact:** F13; `LlmClient`, `LangChain4jClient`, `FindAnythingService.testBackend()` (the
  future fails with the exception). Diagrams: cd06, SD08.

### The agent's search filters by type and date only (2026-11-04)
- **Original design:** F04: "The agent can apply the same filters through its search tool": type,
  modification date, size and location.
- **Implemented change:** `SearchTool` takes `query`, `file_type`, `modified_after`,
  `modified_before` and `limit`. `file_type` is one of the formats whose text is indexed (pdf,
  docx, pptx, txt, md). There are no size or location parameters.
- **Reason:** a fixed type list makes "unknown file type" a rejectable error (F07) instead of a
  search that silently finds nothing. A location has to be checked against `IndexConfig`, which
  the class diagram doesn't give `SearchTool`, and size is rarely part of a request.
- **Impact:** F04, F07; `SearchTool`. The CLI's and the GUI's filters are complete.

### New links to `SearchIndex` and `IndexConfig` (2026-11-05)
- **Original design:** cd05 gives `AgentController` a `ToolRegistry`, `PromptBuilder`,
  `ResponseParser` and `CitationVerifier`, and `ReadExcerptTool` the `SearchIndex`. cd04 gives
  `SearchEngine` the `SearchIndex` and the `EmbeddingModel`.
- **Implemented change:**
  - `AgentController` also takes the `SearchIndex`, to turn the paths of an answer's files into
    the `SearchResult`s that `Answer.sourceFiles` and `Conversation.lastResults` hold.
  - `SearchEngine` and `ReadExcerptTool` also take the `IndexConfig` and leave out files that
    aren't in an active folder (new method `IndexConfig.isInActiveFolder(Path)`).
- **Reason:** the facade, the instant-search fallback and the agent's tools all search and read
  through these two classes, so paused and unavailable folders are skipped in one place for
  every caller.
- **Impact:** F01, F06, F07, F08; `AgentController`, `SearchEngine`, `ReadExcerptTool`,
  `IndexConfig`. The `search` package now uses `config`. Diagrams: cd00, cd04, cd05.

### Small changes to designed classes (2026-10-25 to 2026-11-09)
Each line gives the original design, the implemented change and the reason.
- cd05 gives `ConversationStore` only `conversationsDirectory`. It also takes a `SettingsStore`,
  because the API key isn't saved with a conversation and is supplied on loading.
- cd06 gives `LoggingLlmClient` only `privacyLog`. It also takes the conversation's
  `ModelSettings` and a `Clock`, so each log entry gives the model's name and the time.
- cd06 gives a `Message` a role (user, assistant, or tool), its content and its source files.
  `Message.Role` also has `SYSTEM`, and a message carries `toolCalls`, `toolCallId` and
  `toolName`, because chat APIs give the system prompt its own role and match each tool result
  to its call.
- cd05 has `PromptBuilder.buildReformatRequest(conversation)`. It is
  `buildReformatRequest(conversation, toolSpecs, today, problems)`, because the request needs
  the tools its history refers to, and the problems tell the model what to correct.
- cd07 and cd02 have `IndexConfig.findFolder()` and `SearchIndex.getFile()` return the object.
  Both return `Optional`, because a folder or file that isn't there is an ordinary case.
- F13 appends every request to a cloud model to the privacy log. The connection test isn't
  appended: `LangChain4jClient.testConnection()` sends its own fixed one-line prompt, which holds
  no user data.
- SM01 ends the index's life at Stopping. `IndexerState` also has `STOPPED`, so a caller can
  tell a worker that is stopping from one that has stopped.

Diagrams: cd02, cd05, cd06, cd07, SM01.

### `findany ask` has no interactive session (2026-11-05)
- **Original design:** `findany ask` with no arguments starts an interactive session;
  `--continue` continues the last conversation (F07, F12).
- **Implemented change:** `ask` needs a request. Follow-ups use `ask --continue "<request>"`,
  which continues the most recent conversation.
- **Reason:** time. `--continue` already gives the CLI multi-turn conversations.
- **Impact:** F07, F12; `CliApp`.

### F12: saved conversations in a menu, reopened without verification marks (2026-11-07)
1. **Original Stage 1 feature and ID:** F12 Conversation follow-ups. The GUI keeps past
   conversations in a drawer with a "New conversation" button. A saved conversation whose model
   is no longer configured opens read-only.
2. **Implemented feature and its behavior:** in Ask mode the toolbar has "New conversation" and a
   "Conversations" menu that lists the saved conversations, most recent first, each as its start
   time and first request. Choosing one shows its requests and answers in the detail pane, puts
   the last answer's files in the table, and the next request continues it. Each reopened citation
   is still a link that opens the file at the quote, but carries no verified or unverified mark;
   its tooltip says that verification isn't kept for saved conversations. The CLI continues the
   most recent conversation with `ask --continue`.
3. **What changed and why:** a menu instead of a drawer fits the one-window GUI. The marks are
   missing because `ConversationStore` saves the messages and the last results, not the
   verification statuses; verifying again when a conversation is reopened would cost an index
   lookup per citation and could differ from what the user saw at the time. A saved conversation
   always opens ready to continue: when its model can't be used, the next request gets the
   instant-search fallback with its "model unavailable" notice (F07), so a separate read-only
   state isn't needed.
4. **How the user need is still met:** any saved conversation can be reopened, read and continued
   from the GUI or the CLI.
5. **Affected use cases, classes, methods, and design patterns:** UC04, UC05;
   `GuiController.onOpenConversation()`, `MainWindow.showConversations()`,
   `DetailPane.showPastExchange()`. Patterns unchanged. Diagrams: cd01.

### Conversations keep requests and answers, not old tool results (2026-11-08)
- **Original design:** F12 keeps everything: "Each request, tool result, and answer is added to
  the conversation, which is saved after every answer. The whole conversation, trimmed to fit, is
  sent with each new request, so the model can refer to earlier results." SD03 draws the same.
- **Implemented change:** tool calls and tool results are kept only for the exchange in progress.
  When the next request starts, `AgentController.run()` calls the new
  `Conversation.dropToolMessages()`, which reduces every earlier exchange to its request and its
  closing reply. `ConversationStore.save()` never writes tool calls or results, so a saved
  conversation holds only requests and answers (plus the model and the last answer's files).
- **Reason:** found in real use on 2026-11-08 (see `test-failures.md`). Tool results were 89 to
  98 percent of three real conversations, and one question's search results were twice the
  history limit, so the follow-up lost the whole first exchange. Old results also go stale when
  files change and are sent again on every later call. The answer already says what was found,
  and a follow-up that needs more searches or reads again, which uses the files as they are now.
- **Impact:** F12; `Conversation`, `AgentController`, `ConversationStore`. A follow-up sees
  earlier requests and answers but not earlier search results or excerpts. Diagrams: cd05, SD03.

### Privacy policies: a folder inside a search folder, and only the process that owns the index (2026-11-09)
- **Original design:** UC10's one precondition is that the folder is a search folder;
  `FindAnythingService.setLocalOnly(folder, localOnly)` marks it and
  `setRedaction(category, enabled)` sets a toggle.
- **Implemented change:**
  - `setLocalOnly()` accepts a search folder or a folder inside one. Removing a search folder
    removes the marks on it and inside it.
  - `setLocalOnly()` and `setRedaction()` are refused in a read-only process, as folder changes
    are (UC01).
- **Reason:** one private subfolder can be protected without keeping the whole search folder
  away from cloud models. A change made by a second process would be saved without reaching the
  policy the first process is applying, and the user would believe a folder is protected when it
  is not.
- **Impact:** F14, UC10; `FindAnythingService`, `FolderPolicy`.

### The GUI is one window, and some settings stay in the CLI (2026-11-07 to 2026-11-10)
- **Original design:** the GUI has a conversation drawer (F12) and a comparison view
  (`DetailPane.showComparison()`). A folder chip's menu has "Edit exclusions", "Pause", "Local
  model only" and "Remove". Settings has a Model section (backend, API key or model name, Test
  connection), a Privacy section, a privacy log viewer, Settings > Indexing with the global
  exclusion rules (`SettingsDialog.showGlobalRules()`), a "Rebuild index" button, and a list of
  the supported formats (F02).
- **Implemented change:** one window (search bar with the Search/Ask switch, folder chips, the
  results table, the detail pane, the status label) and the settings dialog.
  - The chip menu offers "Local model only" and "Remove". Pausing a folder and editing exclusion
    rules are done with `findany folders` and `findany exclude`, so
    `GuiController.onAddExclusion()` and `SettingsDialog.showGlobalRules()` aren't built.
  - Rebuilding the index is done with `findany index rebuild`.
  - The settings dialog has no list of the supported formats.
  - The settings dialog has the Model section, the Privacy section and the privacy log. The API
    key isn't entered there (see "API key in a local `.env` file").
  - The conversation drawer is a menu (see F12), and there is no comparison view (see F10).
  - `GuiController` gains `onRemoveFolder()` for the chip menu's "Remove".
- **Reason:** time. The CLI covers every part the GUI leaves out, so each feature stays
  reachable.
- **Impact:** F01, F02, F03, F13; UC01, UC02, UC08; `MainWindow`, `GuiController`,
  `SettingsDialog`. Patterns unchanged. Diagrams: cd01, SD07, SD08.

### A search passage is given once per request: `ShownPassages` (2026-11-12)
- **Original design:** `SearchTool` gives each matching file with its best passage on every
  call. `Tool.execute()` takes the call's arguments and `ToolRegistry.execute()` takes the
  `ToolCall` (cd05, SD03). `AgentController` has no `FolderPolicy`.
- **Implemented change:** within one request, a passage's text is sent once. When a later search
  finds the same passage as its file's best one, the file is still listed, and its label line
  names the earlier search in place of the text:
  `Best passage (page 1): already given above, in the results for "cumulative GPA".`
  - New class `agent/ShownPassages`, one per request: `record(query, results)` and
    `queryThatGave(file, chunk)`.
  - `Tool.execute(arguments, shownPassages)` and `ToolRegistry.execute(toolCall, shownPassages)`
    take the record. `SearchTool` reads and fills it; `ReadExcerptTool` ignores it.
  - `AgentController` takes the `FolderPolicy` in its constructor and `run()` creates the
    record. A result that a cloud model has withheld from it (F14) records nothing, so its
    passages are given in full by a later search.
- **Reason:** measured on 2026-11-12 with "What's my cumulative GPA?": two searches returned the
  same ten files, so half of the 34,019-character request was repeated. A smaller request leaves
  a local model's context room for more steps. The record is passed with each call because the
  registry and its tools are shared by all requests.
- **Impact:** F07, F14. The Command pattern keeps its roles, with one more parameter on
  `Tool.execute()` and `ToolRegistry.execute()`; `SearchTool`, `ReadExcerptTool`,
  `AgentController`, `FindAnythingService`; the `agent` package now uses `privacy`. Diagrams:
  cd00, cd05, SD03.

### F04's filters open as a row, not a menu (2026-11-13)
- **Original design:** "A Filters menu beside the search folder chips offers a type dropdown, a
  date range picker, a size range, and a location."
- **Implemented change:** a "Filters" toggle button after the folder chips opens a row under
  them: a type menu with a checkbox per type, two date pickers, two size fields, a location menu
  and "Clear filters". Every change goes to `GuiController.onFiltersChanged()`, the design's
  method. The CLI options are as designed.
- **Reason:** a row stays open while dates and sizes are entered, and shows at a glance which
  filters are on.
- **Impact:** F04; `MainWindow`, `GuiController`, `ResultsView`. Nothing changed below the
  facade. Diagrams: cd01.

### A first reply without a search is sent back once (2026-11-13)
- **Original design:** the agent loop takes a reply without tool calls as the model's final
  answer, at any step (SD03).
- **Implemented change:** when the first reply to a request has text and no tool call,
  `AgentController.run()` asks the model once more, through the new
  `PromptBuilder.buildSearchFirstRequest()`: search now if the request is about the user's files,
  or reply with only `NO SEARCH NEEDED` if it needs nothing from them. Then:
  - the model calls a tool: the loop runs the call as its first step and goes on as before;
  - the model replies `NO SEARCH NEEDED`, sends nothing, or can't be reached: its first reply is
    the answer;
  - the model replies with other text and no tool call: that second reply is the answer. It is
    never asked a third time.
- **Reason:** local models sometimes answer "your files don't say" without having looked.
  Requiring a tool call on the first step would force a search for "hi" too; the fixed reply lets
  the model decline.
- **Impact:** F07, F12; `AgentController`, `PromptBuilder`. Diagrams: cd05, SD03.

### `LocalEmbeddingModel`: no model file, and loaded on first use (2026-11-14)
- **Original design:** `LocalEmbeddingModel` has one field, `modelFile: Path`. A model that fails
  to load is found at startup, and the facade turns semantic search off.
- **Implemented change:**
  - **No `modelFile`.** The model is inside LangChain4j's
    `langchain4j-embeddings-bge-small-en-v15` jar. `LocalEmbeddingModel` takes
    `nativeLibraryFolder: Path` instead: where the tokenizer library unpacks its native code, a
    folder under the app's data folder.
  - **Loading** happens on the first `embed()` call. The facade holds the `EmbeddingModel`, and
    the GUI makes that call at startup through the new
    `FindAnythingService.loadEmbeddingModel()`; a CLI command loads the model only if it embeds
    something.
  - **Failure** is the new unchecked `EmbeddingException`. When a search meets it,
    `SearchEngine` turns semantic search off itself, logs one warning and returns the keyword
    results; the GUI and the CLI show the warning.
- **Reason:** loading takes about 0.9 seconds (measured), which a command such as `findany show`
  would pay for nothing. With loading on first use, a failure appears inside a search, so the
  engine is where it is handled.
- **Impact:** F05; `LocalEmbeddingModel`, `SearchEngine`, `FindAnythingService`. The `core`
  package now uses `embedding`. The Adapter pattern is unchanged. Diagrams: cd00, cd01, cd02,
  cd04, SD02.

### Vectors in `SearchIndex`, with no `ContentStore` (2026-11-14)
- **Original design:** the chunks to embed come from `ContentStore.get()`, and
  `SearchIndex.addEmbeddings()` writes to both stores.
- **Implemented change:** `addEmbeddings()`, `findContentWithoutEmbeddings()` and
  `vectorSearch()` as designed, on the index alone, plus two new methods:
  - `getChunksOfContent(contentHash)`: the chunks of an indexed file with that hash that have no
    vectors. It stands in for `ContentStore.get()`, and `Indexer.embedNextBatch()` calls it.
  - `countChunksWithoutEmbeddings()`: the number for `IndexStatus.chunksWithoutEmbeddings`.
- **Reason:** `ContentStore` is postponed (see its entry), and the index already holds every
  chunk's text and each file's content hash.
- **Impact:** F05; `SearchIndex`, `Indexer`. Diagrams: cd02, cd03, SD01.

### Hybrid search has a similarity floor (2026-11-14)
- **Original design:** `SearchEngine.search()` embeds the query, calls `vectorSearch()` and merges
  the semantic hits with the keyword hits in `fuseRankings()`.
- **Implemented change:** as designed, with one rule added: a chunk is a semantic match only at a
  cosine similarity of 0.62 or more (`SearchEngine.MIN_SIMILARITY`). The facade gains
  `setSemanticSearchEnabled(enabled)` and `isSemanticSearchEnabled()`, which
  `findany search --keyword-only` uses.
- **Reason:** vector search always returns the nearest chunks, however far, so without a floor
  every query lists unrelated files and "No results" never appears. Measured with the real model
  on `testdata/corpus` (56 queries): 24 of the 25 queries about things the corpus doesn't hold
  scored 0.49 to 0.61, and 25 of the 31 about things it holds scored 0.63 to 0.90.
- **Impact:** F05, F06, F07; `SearchEngine`, `FindAnythingService`, `CliApp`. Diagrams: cd01,
  cd04.

### File names are embedded too, so "CV" finds `resume.pdf` (2026-11-14)
- **Original design:** only chunk text is embedded. A file's name is matched by keywords. F05's
  example: "CV" finds a file that only says "résumé".
- **Implemented change:** every indexed file also gets a vector for its name, written with its
  folder's name, as in `Résumé/resume.pdf` (`Indexer.nameText()`). `SearchIndex` gains
  `findFilesWithoutNameEmbeddings(limit)` and `addNameEmbedding(file, vector)`, and
  `vectorSearch()` looks up the nearest chunk texts and the nearest names with the one query
  vector. `Indexer.embedNextBatch()` embeds names once no chunk is left to embed.
- **Reason:** a résumé rarely says what it is in its text, but its name does. Measured with the
  model (cosine similarity to the query "cv"): the résumé's text 0.56 to 0.57, under the floor;
  `Résumé/resume.pdf` as a name 0.68, against 0.45 to 0.60 for eleven unrelated names.
- **Impact:** F05, F06; `SearchIndex`, `Indexer`. On `testdata/corpus`, "cv", "my CV" and
  "curriculum vitae" each put `resume-jordan-rivera.docx` first, which is F05's example;
  "picture" finds `photo.png`, a file with no text. Diagrams: cd02, cd03, SD01, SD02.

### F10: document comparison is not implemented (2026-11-14)
1. **Original Stage 1 feature and ID:** F10 Document comparison. Two files are rebuilt from their
   chunks and diffed line by line; only the diff goes to the model, which summarizes it with
   verified citations. It is reached from "Compare" on two selected results, from
   `findany compare <fileA> <fileB> [--diff-only]`, and by the agent through `CompareTool`. The
   detail pane shows a highlighted side-by-side diff with the summary.
2. **Implemented feature and its behavior:** none of F10's own parts. What exists is Ask mode
   (F07, F08, F09, F12): asked about two files, the agent searches, reads excerpts of both and
   answers with a citation from each, and every citation is verified. On `testdata/corpus` with
   the cloud model (2026-11-05), "And how much is the rent?" gave $1,850 from the 2025–2026 lease
   and $1,925 from the 2026–2027 draft, both verified.
3. **What changed and why:** F10 was last in the order of work and is left out, decided on
   2026-11-14. The benefit doesn't justify the complexity:
   - **The agent already compares documents, only without a diff.** A question about what differs
     between two files is answered today with citations from both.
   - **The cost.** F10 is the largest feature that was left: five classes and records, a third
     tool, a second model request with its own prompt, a facade method, a CLI command, selecting
     two rows in the table, and a side-by-side view.
   - **A line diff suits only two versions of one document.** For two different documents (two
     job offers, two course outlines) nearly every line differs, and the agent is the better
     tool.
4. **How the scope changed:** the user can still ask what differs between two files and gets a
   cited, verified answer. What is lost:
   - **Completeness:** the agent reads the best passage of each file and excerpts of at most
     6,000 characters, so it finds the differences it looks for, not every changed line.
   - **Comparing without a model:** `--diff-only`, the "no differences" result for identical
     texts, and the diff that is still shown when the model is unavailable.
   - **Sending only the diff to a cloud model:** the agent sends passages of both files (redacted
     or withheld by F14 as for any request).
   - **The side-by-side view.**
5. **Affected use cases, classes, methods, and design patterns:** UC06 Compare documents and SD05
   have no implementation. Not built: `DocumentComparator` (`diff()`), `DiffResult`, `DiffHunk`,
   `Comparison`, `CompareTool`, `FindAnythingService.compare()`,
   `AgentController.summarizeDiff()`, `PromptBuilder.buildDiffSummaryRequest()`,
   `GuiController.onCompareRequested()`, `DetailPane.showComparison()` and `findany compare`.
   `ResultsView` selects one row at a time. Command keeps its structure with two concrete
   commands, `SearchTool` and `ReadExcerptTool`; Facade has one operation fewer. Diagrams: cd01,
   cd04, cd05, the use-case diagram, SD03 (its note), SD05, AD01.

### `FileWatcher`: one watcher per folder, watching from the GUI (2026-11-14)
- **Original design:** `FileWatcher` "runs on its own thread", with
  `watchedFolders: Set<Path>`, `start()`, `stop()`, `watch()` and `unwatch()`. Only the process
  with the write lock watches, and the facade has `open(watchFiles)`. A changed path is
  processed after a quiet period of about two seconds.
- **Implemented change:**
  - **The quiet period** is two seconds in the GUI (`CliApp.GUI_QUIET_PERIOD`) and zero for the
    other commands, which run once and exit.
  - **One library watcher per search folder.** `watchedFolders` is a
    `Map<Path, DirectoryWatcher>`, and each watched folder has its own thread, not one thread for
    all.
  - **The GUI watches.** `findany gui` opens the index with `open(true)`; every other command
    runs once and exits, so it rescans instead. As designed, only the process with the write
    lock watches.
  - **Two package-private methods hold the decisions:** `onChange(path, directory, deleted)` and
    `onOverflow(folder)`.
- **Reason:** a watcher of the directory-watcher library can't change its folders after it is
  built, and search folders never overlap, so one watcher per folder is the simplest form that
  supports adding and removing folders. The two methods are in the design's own types, so they
  are tested without waiting for the operating system.
- **Impact:** F01, F03; `FileWatcher`, `FindAnythingService`, `CliApp`. Diagrams: cd03.

### `CliApp` builds the system's objects, so `ui` uses more packages
- **Original design:** cd00 draws `ui` using `core`, `agent` and `indexing`.
- **Implemented change:** `CliApp.createService()` builds the objects the facade needs (the
  settings and conversation stores, the extractor, the embedding model, the client factory, the
  redactor, the privacy log) and passes them to `FindAnythingService`. The views also show types
  from the lower packages. So `ui` also uses `config`, `embedding`, `llm`, `privacy`, `search`
  and `storage`.
- **Reason:** `CliApp` is the entry point, and with constructor injection one place has to build
  every object; putting it there keeps the facade free of construction code and lets tests pass
  in their own objects.
- **Impact:** `CliApp`; the Facade pattern is unchanged. Diagrams: cd00.

### Four small parts of features are not built
Each line gives the original design, the implemented change and the reason.
- F02 extracts "title, author, modification date, page or slide count" with the text.
  `ExtractedDocument` holds the content type and the pages; the modification date comes from
  the file system, and no title or author is read, because search and the filters work on a
  file's name, text, type, date and size.
- F03 has `findany watch`, which "keeps the index updated until stopped", and has every command
  first catch up on changed files. There is no `watch` command: the GUI watches the search
  folders while it is open, and every command that reads or changes the index first catches up,
  so the index is current whenever it is used. The `model`, `privacy`, `policy` and
  `privacy-log` commands don't use the index and skip the catch-up.
- F01 and UC02 (1a) let the user override a built-in exclusion after explicit confirmation. The
  built-in rules always apply (`IndexConfig.isIncluded()`), so a credential file can never reach
  the index or a model, and no confirmation step is needed.
- F08's model error is "the same fallback as F07, with a retry option". The fallback is there,
  without a retry control: the request stays in the conversation and the user sends it again.

Diagrams: SD01 (the extractor's return), SD07 (its note on overriding a built-in exclusion).
