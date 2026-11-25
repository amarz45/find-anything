# Find Anything: Stage 2 Implementation Report

EECS3311, Fall 2026. Amar Al-Zubaidi.

**Contents**

1. [Project overview and implementation status](#1-project-overview-and-implementation-status)
2. [Feature to implementation mapping](#2-feature-to-implementation-mapping)
3. [Implemented design patterns](#3-implemented-design-patterns)
4. [Changes and rationale](#4-changes-and-rationale)
5. [AI tools and collaboration log](#5-ai-tools-and-collaboration-log)
6. [Development and verification summary](#6-development-and-verification-summary)
7. [Demonstration index](#7-demonstration-index)

The demonstration video is at <https://youtu.be/mcVUT82xOac>. The source code is under `src/` in the repository, in the package `io.github.amarz45.findanything`; source file paths in this report, such as `core/FindAnythingService.java`, are relative to that package. All diagrams were made in UMLet. Their editable sources (`.uxf`) and full-size images are in [`diagrams/stage2/`](diagrams/stage2/), and the Stage 1 diagrams are unchanged in [`diagrams/`](diagrams/). The diagrams keep their full resolution in the PDF, so zoom in to read the large ones.

## 1. Project overview and implementation status

### 1.1 The application and its users

*Find Anything* is a cross-platform desktop application that indexes the files on a user's computer and lets them find files and get answers from them in natural language. It has a JavaFX GUI and a command-line interface, `findany`, and both reach the same functionality through one facade.

The primary users are everyday computer users with large, disorganized collections of personal and work files, such as students, office workers and researchers. Secondary users are developers and power users who prefer to search from the terminal.

### 1.2 AI and LLM integration

The application uses two kinds of models:

- **A language model for the agent**, chosen by the user (F13):
  - a cloud model reached through OpenRouter, chosen by its OpenRouter model ID. Development and the demonstration used `anthropic/claude-haiku-5.5`;
  - a local model run by Ollama on the user's own computer, so no file content leaves it. Development and the demonstration used `qwen3:14b`.
- **An embedding model** for meaning-based search, bge-small-en-v1.5, which runs inside the application on the processor. File contents are never uploaded for indexing.

The language model can't read the file system. It acts only through two tools that deterministic code implements: `search`, which searches the index, and `read_excerpt`, which reads part of an indexed file. For each request, `AgentController` sends the conversation and the tool descriptions to the model. The model replies with tool calls, which `ToolRegistry` validates and executes before the results go back to the model, or with a final answer. The loop runs for at most eight steps. Every citation in the final answer is then checked against the index by `CitationVerifier` before the answer is shown. The agent therefore plans, uses tools, retrieves, keeps a conversation's history for follow-ups, and works in several steps; it is not a prompt forwarded to a model.

Before a request reaches a cloud model, content from folders marked "local model only" is withheld, sensitive patterns are redacted, and the request is recorded in a privacy log the user can read (F13, F14). A local model receives the request as it is, because nothing leaves the computer.

### 1.3 Implementation status

Thirteen of the fourteen Stage 1 features are implemented and work in the submitted application, in the GUI and the CLI. No feature was added or replaced.

| Feature | Status | What works |
|---|---|---|
| F01 Search folder management | Modified | Adding, removing, pausing and listing search folders; exclusion rules; built-in exclusions for credential files. |
| F02 Text extraction across formats | Implemented | Text from PDF, DOCX, PPTX, TXT and MD files, by page or slide; other formats indexed by name; unreadable files reported. |
| F03 Incremental index updates | Implemented | The GUI watches the search folders and indexes a change about two seconds later; every command that uses the index first catches up by a rescan. |
| F04 Filters | Implemented | Type, date, size and location filters in the CLI and the GUI; type and date filters in the agent's search. |
| F05 Semantic search | Implemented | Files found by meaning with a local embedding model, in the CLI, the GUI and the agent's search. |
| F06 Instant hybrid search | Implemented | Results as the user types, ranked by keyword and semantic matches together. |
| F07 Natural-language search | Implemented | The agent turns a description into searches and reports what it found; a fallback to ordinary search when the model is unavailable. |
| F08 Question answering across files | Implemented | Answers drawn from several files, with a citation for each claim. |
| F09 Citation verification | Implemented | Every quoted passage is checked against the cited file and page, and marked verified or unverified. |
| F10 Document comparison | Not implemented | See Section 4.1. |
| F11 Result preview with highlighting | Implemented | A file's extracted text with the matches or the cited passage highlighted. |
| F12 Conversation follow-ups | Modified | Follow-ups in the same conversation; saved conversations reopened from a menu or continued with `ask --continue`. |
| F13 Model backend selection and privacy log | Implemented | Cloud or local backend, a connection test that says what to fix, and a log of everything sent to a cloud model. |
| F14 Sensitive-data protection | Implemented | Credential files never indexed, four categories of sensitive data redacted, and folders kept away from cloud models. |

The code is 83 source files in the ten packages of the Stage 1 design, with 324 automated tests in 44 test classes.

### 1.4 Limitations and incomplete functionality

- **F10 Document comparison is not implemented.** There is no line-by-line diff, no `findany compare` and no side-by-side view. The agent can be asked what differs between two files and answers with a verified citation from each (Section 4.1).
- **Four small parts of features are not built:** the title and author of a document are not extracted (F02); there is no `findany watch` command (F03); a built-in exclusion can't be overridden (F01); and the model-unavailable fallback has no retry control (F08). Section 4.2 gives each one.
- **Some settings are in the CLI only:** pausing a folder, editing exclusion rules and rebuilding the index.
- **Redaction is by pattern,** in four categories: card numbers, Social Insurance Numbers, private keys and API keys. Sensitive text of another kind is not recognized.

Section 4 lists every difference from the Stage 1 design, with its reason.

## 2. Feature to implementation mapping

Each feature below has a table with the fields the instructions list, the original requirement from the Stage 1 report, a paragraph that follows the classes from the user's input to the result, and two screenshots of the feature running: one in the GUI and one in the CLI. The screenshots are frames of the demonstration video, and their files are in [`screenshots/`](screenshots/). Where the implementation differs from the requirement, the status row says so and points to Section 4, which explains what changed and why. Every Stage 1 feature is accounted for, and there are no new features. A method is written as `Class.method()` when its class isn't obvious. Each video link opens the demonstration at that feature's segment.

### F01: Search folder management

| Field | Entry |
|---|---|
| Feature ID and name | F01 - Search folder management |
| Stage 1 feature/reference | F01 |
| Status or change | Modified. The CLI does everything. The GUI adds and removes folders and marks one "Local model only"; pausing a folder and editing exclusion rules are done from the CLI (Section 4.1). |
| Implemented classes/files and responsibilities | `config/IndexConfig.java`: the search folders, the global and built-in exclusion rules, and the decision whether a path is included. `config/SearchFolder.java`: one folder with its own rules and its paused flag. `config/ExclusionRule.java`: parsing and matching one pattern. `config/SettingsStore.java`: saving and loading the configuration. `core/FindAnythingService.java`: the facade operations, refused when the index is read-only. `ui/CliApp.java`: the `folders` and `exclude` commands. `ui/MainWindow.java`: the folder chips and the "Add folder" picker. `ui/GuiController.java`: turns the GUI's actions into facade calls. |
| Key methods | `IndexConfig.addFolder()`, `removeFolder()`, `addGlobalRule()`, `isIncluded()`; `ExclusionRule.parse()`, `matches()`; `SearchFolder.contains()`, `isExcluded()`, `setPaused()`; `SettingsStore.saveIndexConfig()`, `loadIndexConfig()`; `FindAnythingService.addSearchFolder()`, `removeSearchFolder()`, `setFolderPaused()`, `addExclusion()`, `removeExclusion()`; `GuiController.onAddFolder()`, `onRemoveFolder()`; `MainWindow.showFolders()` |
| Design patterns | Facade, MVC |
| Video link/timestamp | [0:00](https://youtu.be/mcVUT82xOac?t=0) |

**Original requirement (Stage 1).** The user chooses which folders are searched, each one recursively, and excludes files within them with patterns, globally or per folder. Folders appear as chips with a menu (Edit exclusions, Pause, Local model only, Remove), and the CLI has `findany folders` and `findany exclude`. A folder that can't be read, an invalid pattern and a folder already covered are refused with a message.

**How it works.** In the GUI, `MainWindow` passes the chosen folder to `GuiController.onAddFolder()`; in the CLI, `findany folders add` does the same from `CliApp`. Both call `FindAnythingService.addSearchFolder()`. The facade first refuses the change if another process owns the index. It then calls `IndexConfig.addFolder()`, which rejects a folder that doesn't exist or is already covered by a search folder, saves the configuration with `SettingsStore.saveIndexConfig()`, starts `FileWatcher.watch()` when the process is watching, and calls `Indexer.requestUpdate(folder)`, which queues a rescan of the whole folder. Exclusions go through `addExclusion()`: `ExclusionRule.parse()` validates the pattern, and the rule is added to one `SearchFolder` or to the global rules. `IndexConfig.isIncluded()` combines the built-in, global and per-folder rules, and both the watcher and the rescan consult it, so an excluded file leaves the index and a re-included one returns. These calls are quick; the indexing they cause happens later on the worker thread.

**Deterministic.** No model is involved.

![F01 in the GUI](screenshots/f01-gui.jpg)

*Screenshot 1. F01 in the GUI: the `docs` folder as a chip with its indexed files, a warning icon on each file whose text couldn't be read, and the status "Up to date · 2 files could not be read". On the left, `findany folders add nonexistent` is refused because the folder doesn't exist.*

![F01 in the CLI](screenshots/f01-cli.jpg)

*Screenshot 2. F01 in the CLI: `findany folders add docs` indexes the folder (23 files, with a warning for each of the 2 that failed), and `findany folders list` lists it.*

### F02: Text extraction across formats

| Field | Entry |
|---|---|
| Feature ID and name | F02 - Text extraction across formats |
| Stage 1 feature/reference | F02 |
| Status or change | Implemented. Extracted chunks are stored in the index alone (Section 4.2, `ContentStore`). |
| Implemented classes/files and responsibilities | `indexing/TextExtractor.java`: the extraction interface in the system's own types. `indexing/IsolatedTextExtractor.java`: the adapter that runs Apache Tika in a separate process, detects the format and splits the text by page or slide. `indexing/Chunker.java`: splits each page into passages. `indexing/Indexer.java`: hashes the file, calls the extractor and records the outcome. `storage/SearchIndex.java`: stores the file's entry and its chunks. `storage/IndexedFile.java`, `storage/Chunk.java`, `storage/ExtractionStatus.java`: what is stored. `ui/CliApp.java`: `index status --failed` and `show`. |
| Key methods | `TextExtractor.extract()`; `Chunker.split()`; `Indexer.indexFile()`; `SearchIndex.put()`; `FindAnythingService.listFailedFiles()` |
| Design patterns | Adapter |
| Video link/timestamp | [3:05](https://youtu.be/mcVUT82xOac?t=185) |

**Original requirement (Stage 1).** During indexing, the text of plain-text, PDF, Word and PowerPoint files is extracted, with metadata, and split into chunks that keep their page or slide number so citations can point to an exact place. Parsing runs isolated from the application. An unsupported format is indexed by name; a corrupt or password-protected file is marked failed and listed by `findany index status --failed`.

**How it works.** On the indexing worker thread, `Indexer.indexFile()` hashes the file's bytes and calls `TextExtractor.extract()`. Its adapter, `IsolatedTextExtractor`, runs the parser in a separate process, so a file that crashes or hangs the parser can't take the application down, and returns an `ExtractedDocument` holding the content type and the pages. PDF pages and PowerPoint slides are numbered, with slides in deck order; DOCX, TXT and MD files are one unnumbered page. Each outcome has its own record: an `UnsupportedFormatException` produces an `IndexedFile` marked `METADATA_ONLY`, which is found by its name; an `ExtractionException` (an empty or password-protected file, for example) produces one marked `FAILED`, listed by `findany index status --failed`; a file that can't be read is retried up to three times first. `Chunker.split()` splits each page separately into passages of at most 1,500 characters, so every `Chunk` keeps its page number, and `SearchIndex.put()` stores the file and its chunks in one step.

**Deterministic.** No model is involved.

![F02 in the GUI](screenshots/f02-gui.jpg)

*Screenshot 3. F02 in the GUI: the extracted text of a note in the preview pane, including the line just appended to it from the terminal on the left.*

![F02 in the CLI](screenshots/f02-cli.jpg)

*Screenshot 4. F02 in the CLI: `findany show` prints the text extracted from a PowerPoint deck, one marker per slide, in deck order.*

### F03: Incremental index updates

| Field | Entry |
|---|---|
| Feature ID and name | F03 - Incremental index updates |
| Stage 1 feature/reference | F03 |
| Status or change | Implemented. The GUI watches the search folders; a command catches up by a rescan when it runs. |
| Implemented classes/files and responsibilities | `indexing/FileWatcher.java`: watches each search folder and reports changed paths. `indexing/ChangeQueue.java`: holds changed paths until they have been quiet for the quiet period. `indexing/Indexer.java`: the one worker thread that writes the index. `indexing/IndexStatus.java`, `indexing/IndexStatusListener.java`, `indexing/IndexerState.java`: the status and its observers. `storage/SearchIndex.java`: the write lock, removal, commits and refreshed snapshots. `core/FindAnythingService.java`: opens the index as writer or read-only. `ui/GuiController.java` and `ui/MainWindow.java`: the status indicator and the refreshed results. `ui/CliApp.java`: `index status`, `index rebuild` and the catch-up before a command. |
| Key methods | `FileWatcher.start()`, `stop()`, `watch()`, `unwatch()`, `onChange()`, `onOverflow()`; `ChangeQueue.offer()`, `takeReady()`; `Indexer.start()`, `requestUpdate()`, `rebuild()`, `rescanFolder()`, `notifyStatus()`; `SearchIndex.open()`, `remove()`, `commit()`, `refresh()`; `FindAnythingService.open()`, `getIndexStatus()`, `rebuildIndex()`, `addStatusListener()`; `GuiController.onStatusChanged()`; `MainWindow.showStatus()` |
| Design patterns | Observer |
| Video link/timestamp | [4:15](https://youtu.be/mcVUT82xOac?t=255) |

**Original requirement (Stage 1).** The index follows files as they are created, modified, renamed, moved or deleted, without a full rebuild, and catches up on changes made while the app was closed. A status indicator shows the progress. Only one process updates the index; a second one opens it read-only.

**How it works.** While the GUI is open, `FileWatcher` keeps one watcher per search folder. For each event, `onChange()` checks `IndexConfig.isIncluded()` and calls `ChangeQueue.offer(path)`, which only records the latest change time, so a burst of events becomes one entry. When the operating system reports lost events, `onOverflow()` offers the whole folder. The indexing worker loops on `ChangeQueue.takeReady()`, which returns a path once it has been unchanged for the quiet period (two seconds in the GUI). The worker removes a path that no longer exists with `SearchIndex.remove()`, rescans a folder with `rescanFolder()`, which compares sizes and modification times with the index, and indexes a file with `indexFile()`. It commits when the queue empties or after twenty files, then calls `notifyStatus()`, which calls `onStatusChanged()` on each `IndexStatusListener`. `GuiController` hands the update to the JavaFX thread, where `MainWindow.showStatus()` shows it and the current search runs again. A CLI command runs once, so it catches up by a rescan before it uses the index. At startup, `FindAnythingService.open()` tries to take the index's write lock through `SearchIndex.open()`; if another process holds it, the index opens read-only, no watcher or worker starts, and `SearchIndex.refresh()` runs every three seconds.

**Deterministic.** No model is involved.

![F03 in the GUI](screenshots/f03-gui.jpg)

*Screenshot 5. F03 in the GUI: a note created in the terminal on the left (`echo … > docs/notes/landlord.txt`) appears in the window's results for "landlord meeting" a couple of seconds later, with no action in the window.*

![F03 in the CLI](screenshots/f03-cli.jpg)

*Screenshot 6. F03 in the CLI: after the note is renamed, edited and deleted, `findany index status` reports that another process is updating the index and that this one is read-only. The window's results no longer list the deleted note.*

### F04: Filters

| Field | Entry |
|---|---|
| Feature ID and name | F04 - Filters |
| Stage 1 feature/reference | F04 |
| Status or change | Implemented. In the GUI the filters open as a row under the folder chips. The agent's search filters by type and date (Section 4.2). |
| Implemented classes/files and responsibilities | `storage/SearchFilters.java`: the filter values, their validation and matching. `storage/InvalidFiltersException.java`: the errors of an invalid combination. `storage/SearchIndex.java`: applies the filters inside each query. `core/FindAnythingService.java`: validates before searching. `ui/CliApp.java`: the options of `findany search`. `ui/MainWindow.java`: the "Filters" button and row. `ui/GuiController.java`: stores the filters and searches again. `ui/ResultsView.java`: the "no results" text when filters are on. `agent/SearchTool.java`: the agent's type and date parameters. |
| Key methods | `SearchFilters.validate()`, `matches()`, `isEmpty()`; `SearchIndex.keywordSearch()`, `vectorSearch()`, `listFiles()`; `FindAnythingService.search()`; `GuiController.onFiltersChanged()`; `MainWindow.showFilterError()`; `ResultsView.setFiltersActive()` |
| Design patterns | Facade |
| Video link/timestamp | [6:51](https://youtu.be/mcVUT82xOac?t=411) |

**Original requirement (Stage 1).** Results can be narrowed by file type, modification date, size and location, from a Filters menu in the GUI and with `--type`, `--after`, `--before`, `--min-size`, `--max-size` and `--in` in the CLI. An invalid range is shown as a validation message, and no results suggests loosening the filters. The agent can apply the same filters through its search tool.

**How it works.** In the GUI, every change in the filter row goes to `GuiController.onFiltersChanged()`, which stores the new `SearchFilters` and runs the current search again. In the CLI, `findany search` builds the same object from `--type`, `--after`, `--before`, `--min-size`, `--max-size` and `--in`. `FindAnythingService.search()` calls `SearchFilters.validate()` before anything else; an invalid combination, such as a start date after the end date, fails with an `InvalidFiltersException`, which the GUI shows in the row through `MainWindow.showFilterError()` and the CLI prints. Valid filters are passed to `SearchIndex.keywordSearch()`, `vectorSearch()` and `listFiles()`. Each index entry carries its file's type, size, modification time and path, so the filters are applied inside the query, not afterwards. The agent's `SearchTool` builds a `SearchFilters` from its `file_type`, `modified_after` and `modified_before` arguments and passes it to the same `SearchEngine.search()`.

**Deterministic.** No model is involved.

![F04 in the GUI](screenshots/f04-gui.jpg)

*Screenshot 7. F04 in the GUI: the filter row open with the type set to `pdf`, the button reading "Filters (1)", and only the two lease PDFs listed for "lease". On the left, the CLI's filters, including the error for a start date after the end date and "No results. Try loosening the filters."*

![F04 in the CLI](screenshots/f04-cli.jpg)

*Screenshot 8. F04 in the CLI: `findany search lease --type pdf` lists only the two PDFs.*

### F05: Semantic search

| Field | Entry |
|---|---|
| Feature ID and name | F05 - Semantic search |
| Stage 1 feature/reference | F05 |
| Status or change | Implemented, with a similarity floor and with file names embedded as well as text (Section 4.2). |
| Implemented classes/files and responsibilities | `embedding/EmbeddingModel.java`: the interface, texts in and vectors out. `embedding/LocalEmbeddingModel.java`: the adapter over LangChain4j's in-process bge-small-en-v1.5 model. `embedding/EmbeddingException.java`: the model's failure. `storage/SearchIndex.java`: stores the vectors and searches by them. `indexing/Indexer.java`: the embedding phase. `search/SearchEngine.java`: embeds the query, applies the floor and falls back to keywords when the model fails. `core/FindAnythingService.java`: loads the model and turns semantic search on or off. `ui/CliApp.java`: `search --keyword-only` and the embeddings line of `index status`. `ui/GuiController.java` and `ui/MainWindow.java`: the load at startup, the embedding progress and the warning. |
| Key methods | `EmbeddingModel.embed()`; `SearchIndex.vectorSearch()`, `addEmbeddings()`, `findContentWithoutEmbeddings()`, `getChunksOfContent()`, `addNameEmbedding()`, `findFilesWithoutNameEmbeddings()`; `Indexer.embedNextBatch()`; `SearchEngine.search()`, `setSemanticSearchEnabled()`; `FindAnythingService.loadEmbeddingModel()`, `setSemanticSearchEnabled()` |
| Design patterns | Adapter |
| Video link/timestamp | [9:07](https://youtu.be/mcVUT82xOac?t=547) |

**Original requirement (Stage 1).** Passages are found by meaning, not only by exact words, with an embedding model that runs locally; for example, "CV" finds a file that only says "résumé". `--keyword-only` turns it off. If the model fails to load, search falls back to keywords with a warning, and files not yet embedded are still found by keywords.

**How it works.** Embedding is a second indexing phase, so a file is searchable by keywords before its vectors exist. When `ChangeQueue.takeReady()` returns nothing, `Indexer.embedNextBatch()` asks `SearchIndex.findContentWithoutEmbeddings()` for content without vectors, loads its chunks with `getChunksOfContent()`, calls `EmbeddingModel.embed()` and stores the vectors with `addEmbeddings()`. When no chunk is left, it embeds each file's name together with its folder's name and stores it with `addNameEmbedding()`, which is how the query "cv" finds a file named `resume`. At search time, `SearchEngine.search()` embeds the query with the same model and calls `SearchIndex.vectorSearch()`, which looks up the nearest chunk texts and the nearest names; a chunk counts as a semantic match only at a cosine similarity of 0.62 or more. `LocalEmbeddingModel` adapts the library's model to `EmbeddingModel` and loads it on the first call. If the model fails, `SearchEngine` turns semantic search off, returns the keyword results, and the GUI and the CLI show a warning.

**Hybrid.** A local embedding model ranks by meaning; no language model is involved, and nothing leaves the computer.

![F05 in the GUI](screenshots/f05-gui.jpg)

*Screenshot 9. F05 in the GUI: "CV" finds `resume-jordan-rivera.docx`, whose text never contains "CV", shown in the preview.*

![F05 in the CLI](screenshots/f05-cli.jpg)

*Screenshot 10. F05 in the CLI: `findany search cv` finds the résumé by meaning, and `findany search cv --keyword-only` finds nothing.*

### F06: Instant hybrid search

| Field | Entry |
|---|---|
| Feature ID and name | F06 - Instant hybrid search (Search mode) |
| Stage 1 feature/reference | F06 |
| Status or change | Implemented |
| Implemented classes/files and responsibilities | `search/SearchEngine.java`: runs the keyword and semantic searches and fuses their rankings. `search/SearchResult.java`: one file with its best passages. `storage/SearchIndex.java`: keyword search with accents folded, and the file listing for an empty query. `storage/ChunkHit.java`: one matching chunk. `core/FindAnythingService.java`: runs the search on a background thread. `ui/CliApp.java`: the `search` command. `ui/MainWindow.java`: the search field in Search mode. `ui/GuiController.java`: searches on every change and drops outdated results. `ui/ResultsView.java`: the results table. |
| Key methods | `SearchEngine.search()`, `fuseRankings()`; `SearchIndex.keywordSearch()`, `listFiles()`; `FindAnythingService.search()`; `GuiController.onQueryChanged()`; `ResultsView.setResults()` |
| Design patterns | Facade, MVC |
| Video link/timestamp | [10:50](https://youtu.be/mcVUT82xOac?t=650) |

**Original requirement (Stage 1).** The default search combines keyword and semantic matches into one ranked list as the user types, without calling a language model. Each file appears once with its best passage; before any query, all files are listed newest first. Special characters are taken literally, and no results suggests Ask mode.

**How it works.** Each change to the search field calls `GuiController.onQueryChanged()`, which increments `latestQueryId` and calls `FindAnythingService.search()`. The facade submits `SearchEngine.search()` to its background executor and returns a future at once, so the UI thread never waits. For an empty query the engine calls `SearchIndex.listFiles()`, which returns files newest first. Otherwise it calls `keywordSearch()` and, when semantic search is on, `vectorSearch()`; `fuseRankings()` merges the two lists by rank (reciprocal rank fusion), and the ranked `ChunkHit`s are grouped by file into `SearchResult`s. When the future completes, `GuiController` hands the results to `ResultsView.setResults()` on the JavaFX thread, but only if `latestQueryId` hasn't changed since, so the table never shows the results of an older query. When nothing matches, the table suggests Ask mode.

**Hybrid.** Deterministic keyword search is fused with F05's embedding model; no language model is involved.

![F06 in the GUI](screenshots/f06-gui.jpg)

*Screenshot 11. F06 in the GUI: "lease ends" typed in Search mode, with each matching file and its best passage. On the left, the CLI's searches, including "résumé" with its accent and a nonsense word that gives "No results."*

![F06 in the CLI](screenshots/f06-cli.jpg)

*Screenshot 12. F06 in the CLI: `findany search lease ends` lists the two leases and a note, each with its best passage.*

### F07: Natural-language search

| Field | Entry |
|---|---|
| Feature ID and name | F07 - Natural-language search (Ask mode) |
| Stage 1 feature/reference | F07 |
| Status or change | Implemented |
| Implemented classes/files and responsibilities | `agent/AgentController.java`: the agent loop. `agent/PromptBuilder.java`: builds each request from the instructions, today's date, the conversation and the tool descriptions, with file text marked as data. `agent/Tool.java`, `agent/ToolResult.java`: the command interface and its result. `agent/ToolRegistry.java`: validates, executes and records every tool call. `agent/SearchTool.java`: the `search` tool. `agent/ShownPassages.java`: the passages a request has already been given. `llm/LlmClient.java`, `llm/LangChain4jClient.java`, `llm/LlmClientFactory.java`: the model client, its adapter and its factory. `core/FindAnythingService.java`: runs the agent in the background and falls back to ordinary search when the model is unavailable. `ui/CliApp.java`: the `ask` command with `--verbose` and `--json`. `ui/GuiController.java`, `ui/DetailPane.java`, `ui/MainWindow.java`: Ask mode. |
| Key methods | `AgentController.run()`; `PromptBuilder.buildRequest()`, `buildSearchFirstRequest()`; `ToolRegistry.register()`, `getToolSpecs()`, `execute()`; `Tool.validate()`, `execute()`; `LlmClient.complete()`; `LlmClientFactory.createClient()`; `FindAnythingService.ask()`; `GuiController.onAskSubmitted()`; `DetailPane.showRequest()`, `showAgentStep()`, `showAnswer()` |
| Design patterns | Command, Adapter, Facade |
| Video link/timestamp | [12:09](https://youtu.be/mcVUT82xOac?t=729) |

**Original requirement (Stage 1).** The user describes a file vaguely, such as "the co-op info session slides from last year". The agent plans searches with filters, inspects the results, refines the search if needed, and reports the best matches, with its steps shown as they happen (`findany ask`, `--verbose`). An invalid tool call is returned to the model, the number of steps is limited, and an unavailable model falls back to instant search.

**How it works.** `GuiController.onAskSubmitted()` disables the Ask input and calls `FindAnythingService.ask()`; `findany ask` calls the same method. On a background thread, the facade gets the client for the conversation's own model from `LlmClientFactory.createClient()` and calls `AgentController.run()`. Each step of the loop works as follows:

1. `PromptBuilder.buildRequest()` combines the instructions, today's date, the conversation and `ToolRegistry.getToolSpecs()` into an `LlmRequest`.
2. `LlmClient.complete()` sends it.
3. For each `ToolCall` in the reply, `ToolRegistry.execute()` finds the tool by name, calls `Tool.validate()` and, if the arguments are valid, `Tool.execute()`. `SearchTool` calls `SearchEngine.search()`. An invalid call is not executed: its errors go back to the model as the result, so the model can correct it.
4. Each `ToolResult` is added to the conversation, and the step is reported to `DetailPane.showAgentStep()` or printed by `--verbose`.

The loop ends when the model gives a final answer or after eight steps. If the model's first reply has no tool call, it is asked once to search first or to say that no search is needed. If the client throws an `LlmException`, the facade completes the request with ordinary search results and a "model unavailable" notice.

**AI-based.** The language model decides which searches to run and when it has found the file; the searches themselves are deterministic.

![F07 in the GUI](screenshots/f07-gui.jpg)

*Screenshot 13. F07 in the GUI: Ask mode while the agent works. The request and its first step appear in the pane, and the input is disabled until the answer arrives.*

![F07 in the CLI](screenshots/f07-cli.jpg)

*Screenshot 14. F07 in the CLI: `findany ask --verbose "Find my co-op slides from last year"`. The agent's first searches, with a type and a date filter, find nothing; it searches again without them and names the deck, with two verified citations.*

### F08: Question answering across files

| Field | Entry |
|---|---|
| Feature ID and name | F08 - Question answering across files |
| Stage 1 feature/reference | F08 |
| Status or change | Implemented |
| Implemented classes/files and responsibilities | `agent/AgentController.java`: the same loop as F07, ending in a parsed and verified answer. `agent/ReadExcerptTool.java`: the `read_excerpt` tool, limited to 6,000 characters a call. `agent/ToolRegistry.java`: runs the tool calls. `agent/ResponseParser.java`: turns the model's final reply into an answer with citations. `agent/AnswerFormatException.java`: a reply that can't be parsed. `agent/Answer.java`, `agent/Citation.java`: the answer and its citations. `ui/CliApp.java`: prints the answer with its sources. `ui/DetailPane.java`: shows the answer with each citation as a link. `ui/GuiController.java`, `ui/ResultsView.java`: the clicked citation and the answer's files. |
| Key methods | `AgentController.run()`; `ReadExcerptTool.validate()`, `execute()`; `ToolRegistry.execute()`; `ResponseParser.parseAnswer()`; `DetailPane.showAnswer()`; `GuiController.onCitationClicked()`; `ResultsView.setResults()` |
| Design patterns | Command, Adapter |
| Video link/timestamp | [13:43](https://youtu.be/mcVUT82xOac?t=823) |

**Original requirement (Stage 1).** A question is answered from the files' contents, combining several files when needed, with a citation to the file and passage behind each claim. The answer has clickable citations in the GUI and numbered sources in the CLI. When nothing relevant is found the agent says so, and when files conflict both versions are given with their sources.

**How it works.** The loop is F07's. For a question, the model typically calls `SearchTool` to find candidate files and `ReadExcerptTool` to read more of them from `SearchIndex`, by page or by position; an excerpt is at most 6,000 characters, so only excerpts are sent, never whole files. Each `ToolResult` lists the files its text came from, which the redacting decorator uses (F14). When the model returns its final reply, `ResponseParser.parseAnswer()` turns it into an `Answer` with `Citation`s, each naming a file, a page where the format has pages, and a quoted passage. `CitationVerifier.verify()` then checks each citation (F09). The facade saves the conversation and completes the request; `GuiController` calls `DetailPane.showAnswer()`, where each citation marker is a link to its source, and `ResultsView.setResults()` with the answer's files. When two files disagree, the answer gives both values with their sources.

**AI-based.** The language model chooses what to read and writes the answer; reading, parsing and checking are deterministic.

![F08 in the GUI](screenshots/f08-gui.jpg)

*Screenshot 15. F08 in the GUI: "What is the final exam worth?" answered 40%, with the citation marker in the text and the cited passage under "Sources".*

![F08 in the CLI](screenshots/f08-cli.jpg)

*Screenshot 16. F08 in the CLI: `findany ask "When does my lease end?"` answers from both leases with two verified sources, and says it ignored the instructions planted in a note.*

### F09: Citation verification

| Field | Entry |
|---|---|
| Feature ID and name | F09 - Citation verification |
| Stage 1 feature/reference | F09 |
| Status or change | Implemented |
| Implemented classes/files and responsibilities | `agent/CitationVerifier.java`: checks each quoted passage against the indexed text of the cited file and page. `agent/VerificationStatus.java`: the outcome of one check. `agent/Answer.java`, `agent/Citation.java`: carry the statuses. `agent/AgentController.java`: verifies every answer and sends one reformat request when a reply can't be parsed. `agent/PromptBuilder.java`: builds the reformat request. `storage/SearchIndex.java`: supplies the file's chunks. `ui/CliApp.java` and `ui/DetailPane.java`: the `[✓]` and `[?]` marks and the status in words. |
| Key methods | `CitationVerifier.verify()`, `verifyCitation()`; `Answer.isVerified()`; `PromptBuilder.buildReformatRequest()`; `SearchIndex.getChunks()`, `getFile()` |
| Design patterns | N/A |
| Video link/timestamp | [15:53](https://youtu.be/mcVUT82xOac?t=953) |

**Original requirement (Stage 1).** A deterministic check that every quoted passage in an answer really appears in the cited file on the stated page. Each citation is marked verified or unverified (`[✓]` or `[?]` in the CLI), a quote that is close but not exact is unverified, and a reply whose citations can't be parsed gets one reformat request.

**How it works.** After `ResponseParser` produces an `Answer`, `AgentController` passes it to `CitationVerifier.verify()`. For each citation, `verifyCitation()` loads the cited file's chunks with `SearchIndex.getChunks()` and searches the chunks of the cited page for the quote, ignoring differences in whitespace; for a format without pages it searches the whole file. Each citation gets a `VerificationStatus`, and `Answer.isVerified()` is true only when every citation is verified. If the model's reply can't be parsed, `AgentController` sends one `PromptBuilder.buildReformatRequest()` that tells the model what to correct; a second failure produces an unverified answer with a notice. The CLI prints `[✓]` or `[?]` beside each source with the status in words, and `DetailPane` puts the same mark on each citation marker and highlights unverified sources. A model that quotes text that isn't in the file, including text it received in redacted form, is therefore shown as unverified.

**Deterministic.** The check is ordinary code that verifies what the AI wrote.

![F09 in the GUI](screenshots/f09-gui.jpg)

*Screenshot 17. F09 in the GUI: the lease answer with a `✓` on each citation marker and "verified" beside each source.*

![F09 in the CLI](screenshots/f09-cli.jpg)

*Screenshot 18. F09 in the CLI: the model received only a redacted copy of the bank-details file, so its quote isn't in the real file, and the citation is marked `[?]` unverified.*

### F10: Document comparison

| Field | Entry |
|---|---|
| Feature ID and name | F10 - Document comparison |
| Stage 1 feature/reference | F10 |
| Status or change | Not implemented (Section 4.1) |
| Implemented classes/files and responsibilities | None of F10's own classes. The nearest behaviour is F08's: asked about two files, the agent reads both and answers with a citation from each. |
| Key methods | N/A |
| Design patterns | N/A |
| Video link/timestamp | None; the feature is not implemented. |

### F11: Result preview with highlighting

| Field | Entry |
|---|---|
| Feature ID and name | F11 - Result preview with highlighting |
| Stage 1 feature/reference | F11 |
| Status or change | Implemented |
| Implemented classes/files and responsibilities | `core/FindAnythingService.java`: rebuilds the file's text from its chunks and finds the ranges to highlight. `core/Preview.java`: the file, its text and the highlight ranges. `storage/SearchIndex.java`: supplies the chunks and the file's entry. `ui/CliApp.java`: the `show` command with `--highlight`. `ui/DetailPane.java`: shows the text with highlights and page markers, and the "Open file", "Show in folder" and "Back to conversation" buttons. `ui/GuiController.java`: asks for the preview of a selected result or a clicked citation. |
| Key methods | `FindAnythingService.preview()`; `SearchIndex.getChunks()`, `getFile()`; `DetailPane.showPreview()`, `clearPreview()`; `GuiController.onResultSelected()`, `onCitationClicked()` |
| Design patterns | Facade, MVC |
| Video link/timestamp | [17:08](https://youtu.be/mcVUT82xOac?t=1028) |

**Original requirement (Stage 1).** A result's extracted text is shown with the matching or cited passages highlighted, scrolled to the first one, with "Open file" and "Show in folder" buttons; a clicked citation opens the preview with a Back button. `findany show --highlight` does the same in the CLI. A deleted file gives a message, and a file without text shows its details.

**How it works.** `GuiController.onResultSelected()`, or `onCitationClicked()` with the cited quote, calls `FindAnythingService.preview(file, highlight)`; `findany show` calls the same method. On a background thread, the facade loads the chunks with `SearchIndex.getChunks()` and the file's entry with `getFile()`, joins the chunks in order with a page marker before each numbered page, and finds the ranges to highlight: every occurrence of the query or the quote as a whole phrase, or of each of its words when the phrase doesn't occur. It returns a `Preview`, which `DetailPane.showPreview()` displays scrolled to the first highlight. If the file no longer exists, the facade calls `Indexer.requestUpdate(file)` so the worker removes it from the index, and the request fails with "This file no longer exists". A file with no extracted text gets a preview of its details alone. When the selected file is indexed again, or leaves the results, `GuiController` reloads or empties the preview.

**Deterministic.** No model is involved.

![F11 in the GUI](screenshots/f11-gui.jpg)

*Screenshot 19. F11 in the GUI: the selected lease in the preview pane, with "lease" and "ends" highlighted, a marker for each page, and the "Open file" and "Show in folder" buttons.*

![F11 in the CLI](screenshots/f11-cli.jpg)

*Screenshot 20. F11 in the CLI: `findany show … --highlight "August 31"` prints the lease's text with page markers and the match marked `[[August 31]]`.*

### F12: Conversation follow-ups

| Field | Entry |
|---|---|
| Feature ID and name | F12 - Conversation follow-ups |
| Stage 1 feature/reference | F12 |
| Status or change | Modified. Saved conversations are in a menu, and a reopened conversation shows its citations without verification marks (Section 4.1). |
| Implemented classes/files and responsibilities | `agent/Conversation.java`: the messages, the last answer's files and the model the conversation was created with. `agent/ConversationStore.java`: saves and loads conversations. `agent/AgentController.java`: adds each message as the loop proceeds. `agent/PromptBuilder.java`: sends the history with each request. `core/FindAnythingService.java`: saves after each answer; creates, lists and opens conversations. `ui/CliApp.java`: `ask --continue`. `ui/GuiController.java`, `ui/MainWindow.java`, `ui/DetailPane.java`: the current conversation, "New conversation" and the "Conversations" menu. |
| Key methods | `Conversation.addUserMessage()`, `addAssistantMessage()`, `addToolResult()`, `dropToolMessages()`, `trimToFit()`, `getModelSettings()`; `ConversationStore.save()`, `load()`, `listAll()`; `FindAnythingService.newConversation()`, `listConversations()`, `openConversation()`; `GuiController.onNewConversation()`, `onOpenConversation()`; `MainWindow.showConversations()`; `DetailPane.showPastExchange()` |
| Design patterns | N/A |
| Video link/timestamp | [18:45](https://youtu.be/mcVUT82xOac?t=1125) |

**Original requirement (Stage 1).** The agent remembers the current conversation's requests and results, so a follow-up such as "only the ones from 2025" builds on them. Conversations are saved and reopened from a drawer with a "New conversation" button; `findany ask --continue` continues the last one. Each conversation keeps the model it started with, and a long history is trimmed.

**How it works.** A `Conversation` holds its `Message`s, the last answer's files and the `ModelSettings` it was created with. `AgentController.run()` calls `addUserMessage()`, `addToolResult()` and `addAssistantMessage()` as the loop proceeds, and `PromptBuilder.buildRequest()` sends the history with every request, so the model can resolve a follow-up such as "And what is the midterm worth?". When a new request starts, `dropToolMessages()` reduces each earlier exchange to its request and its answer, and `trimToFit()` keeps the history within 40,000 characters. `FindAnythingService.ask()` saves the conversation with `ConversationStore.save()` after each answer. In the GUI, "New conversation" calls `newConversation()`, and the "Conversations" menu lists the saved ones through `listConversations()` and reopens one through `openConversation()`; the CLI continues the most recent one with `ask --continue`. Because the factory is always asked for a client for `conversation.getModelSettings()`, a conversation never switches model, so content withheld from a cloud model can't reach it later through another client.

**AI-based.** The language model interprets the follow-up in the light of the history; storing and trimming the history are deterministic.

![F12 in the GUI](screenshots/f12-gui.jpg)

*Screenshot 21. F12 in the GUI: the follow-up "And what is the midterm worth?" is answered 30% from the same syllabus, without the course being named again.*

![F12 in the CLI](screenshots/f12-cli.jpg)

*Screenshot 22. F12 in the CLI: `findany ask --continue "And how much is the rent?"` builds on the earlier lease question and answers from both leases.*

### F13: Model backend selection and privacy log

| Field | Entry |
|---|---|
| Feature ID and name | F13 - Model backend selection and privacy log |
| Stage 1 feature/reference | F13 |
| Status or change | Implemented. The cloud model is reached through OpenRouter, and the API key comes from a local `.env` file (Section 4.2). |
| Implemented classes/files and responsibilities | `config/ModelSettings.java`, `config/BackendType.java`: the chosen backend and model. `config/EnvFile.java`: reads the key and the model defaults from `.env`. `config/SettingsStore.java`: saves the choice, never the key. `llm/LlmClient.java`: the client interface. `llm/LangChain4jClient.java`: the adapter for OpenRouter and Ollama, which maps each failure to a message that says what to fix. `llm/LlmClientFactory.java`: builds the client for a model. `llm/LlmException.java`: a backend that can't be used. `llm/LoggingLlmClient.java`: records each cloud request. `privacy/PrivacyLog.java`, `privacy/PrivacyLogEntry.java`: the log. `core/FindAnythingService.java`: tests and sets the backend, and reads the log. `ui/CliApp.java`: `model set`, `model test` and `privacy-log`. `ui/SettingsDialog.java`, `ui/GuiController.java`: the same in the GUI. |
| Key methods | `LlmClient.complete()`, `testConnection()`; `LlmClientFactory.createClient()`; `EnvFile.load()`; `SettingsStore.loadModelSettings()`, `saveModelSettings()`, `defaultModelSettings()`; `FindAnythingService.getModelSettings()`, `resolveModelSettings()`, `testBackend()`, `setBackend()`, `getPrivacyLog()`; `LoggingLlmClient.complete()`; `PrivacyLog.append()`, `getEntries()`; `GuiController.onTestBackend()`, `onShowPrivacyLog()`; `SettingsDialog.showTestResult()`, `showPrivacyLog()` |
| Design patterns | Factory, Adapter, Decorator |
| Video link/timestamp | [20:32](https://youtu.be/mcVUT82xOac?t=1232) |

**Original requirement (Stage 1).** The user chooses between a cloud model and a local model, tests the connection, and can read a log of everything sent to cloud models, with each request's time, excerpts and redactions (`findany model set`, `model test`, `privacy-log`). An invalid key or a local runtime that isn't running gives a clear message, and existing conversations keep their model.

**How it works.** In `SettingsDialog`, "Test connection" calls `GuiController.onTestBackend()`; `findany model set` and `model test` take the same path from the CLI. `FindAnythingService.resolveModelSettings()` completes the user's choice with the API key and the defaults from `.env`, and `testBackend()` gets a client from `LlmClientFactory.createClient()` and calls `LlmClient.testConnection()` on a background thread. When the backend can't be used, the client throws an `LlmException` whose message says what to fix (a wrong key, an unknown model, an unreachable server), and nothing is saved. On success, `setBackend()` saves the choice with `SettingsStore.saveModelSettings()`, and new conversations use it. `LangChain4jClient` adapts the library's chat models for both OpenRouter and Ollama, so the rest of the system knows only `LlmClient`. For a cloud model, `LoggingLlmClient.complete()` appends a `PrivacyLogEntry` (the time, the model, the redacted categories and the messages as sent) with `PrivacyLog.append()` before the request leaves. `findany privacy-log` and the dialog's "Show privacy log" read it through `getPrivacyLog()`.

**Deterministic.** The feature controls which AI model is used and records what it is sent; it uses no model itself, apart from the one-line connection test.

![F13 in the GUI](screenshots/f13-gui.jpg)

*Screenshot 23. F13 in the GUI: the settings dialog with the cloud backend, the result of "Test connection", and the privacy log showing the last request sent to the cloud model.*

![F13 in the CLI](screenshots/f13-cli.jpg)

*Screenshot 24. F13 in the CLI: `findany model test` succeeds; an unknown local model is refused; the local model `qwen3:14b` answers a question, then the cloud model answers it; and `findany privacy-log --last 1` shows what was sent.*

### F14: Sensitive-data protection

| Field | Entry |
|---|---|
| Feature ID and name | F14 - Sensitive-data protection |
| Stage 1 feature/reference | F14 |
| Status or change | Implemented. A folder inside a search folder can be marked local-only too (Section 4.2). |
| Implemented classes/files and responsibilities | `config/IndexConfig.java`: the built-in rules that keep credential locations out of the index. `privacy/SensitivePattern.java` with `CardNumberPattern`, `SinPattern`, `PrivateKeyPattern` and `ApiKeyPattern`: find sensitive text of one category each. `privacy/Redactor.java`: applies the enabled patterns. `privacy/FolderPolicy.java`: the folders marked local-only. `llm/RedactingLlmClient.java`: withholds and redacts each cloud request. `llm/LlmClientFactory.java`: adds the privacy decorators for cloud models only. `config/SettingsStore.java`: saves the policy and the disabled categories. `core/FindAnythingService.java`: the policy operations and the "sources withheld" notice. `ui/CliApp.java`: `privacy`, `privacy redact` and `policy local-only`. `ui/MainWindow.java`, `ui/GuiController.java`, `ui/SettingsDialog.java`: the chip's "Local model only" item and the redaction check boxes. |
| Key methods | `IndexConfig.isIncluded()`; `SensitivePattern.findAll()`; `Redactor.redact()`, `setCategoryEnabled()`; `FolderPolicy.setLocalOnly()`, `isLocalOnly()`; `RedactingLlmClient.complete()`; `LlmClientFactory.createClient()`; `FindAnythingService.setLocalOnly()`, `setRedaction()`; `GuiController.onSetLocalOnly()`, `onRedactionToggled()`; `SettingsDialog.showRedaction()` |
| Design patterns | Decorator, Factory |
| Video link/timestamp | [23:37](https://youtu.be/mcVUT82xOac?t=1417) |

**Original requirement (Stage 1).** Sensitive data is protected in three ways: credential locations are never indexed; a folder can be marked "local model only"; and card numbers, SINs, private keys and API keys are replaced before anything is sent to a cloud model, with a toggle per category (`findany policy local-only`, `findany privacy redact`). When needed sources are withheld, the answer says so, and the settings state that unstructured sensitive text isn't detected.

**How it works.** The three protections are in different places. **Built-in exclusions:** `IndexConfig`'s built-in rules keep credential locations, such as SSH keys, out of the index entirely, so they can never reach a search or a model. **Local-only folders:** "Local model only" on a folder chip calls `GuiController.onSetLocalOnly()`, and `findany policy local-only` does the same from the CLI; `FindAnythingService.setLocalOnly()` updates `FolderPolicy` and saves it. **Redaction:** the check boxes and `findany privacy redact` call `setRedaction()`, which calls `Redactor.setCategoryEnabled()`. For a cloud model, `LlmClientFactory.createClient()` wraps the client in `RedactingLlmClient`. Its `complete()` checks each message's source files with `FolderPolicy.isLocalOnly()`, replaces the content of any message from a local-only folder with a withheld marker, and passes the rest through `Redactor.redact()`, which replaces every match of each enabled `SensitivePattern` with a placeholder naming its category. The redacted request then goes to `LoggingLlmClient` and the adapter. When sources were withheld, the answer carries a notice saying so. A local model gets no decorators, because nothing leaves the computer.

**Deterministic.** The feature limits what the AI model receives; it uses no model itself.

![F14 in the GUI](screenshots/f14-gui.jpg)

*Screenshot 25. F14 in the GUI: the redaction check boxes and the limitation sentence in the settings dialog, and the folder chip marked "docs (local model only)".*

![F14 in the CLI](screenshots/f14-cli.jpg)

*Screenshot 26. F14 in the CLI: `findany privacy` lists the four categories; asked for bank details, the agent receives only placeholders; and `findany privacy-log --last 1` shows "Redacted: card-number, sin, api-key".*

## 3. Implemented design patterns

The implementation uses the five patterns of the Stage 1 design: Facade, Observer, Command, Adapter and Decorator. Two smaller uses, a factory and MVC, follow them. All seven keep the participants and roles that Stage 1 gives them; each subsection ends by saying where the implementation differs.

### 3.1 Facade

**Design problem.** The GUI and the CLI must offer the same functionality. Each operation involves several subsystems: adding a search folder updates the configuration, saves the settings, starts watching the folder and queues it for indexing. Slow operations must run off the UI thread, and changes must be refused when another process owns the index. Without one entry point, both front ends would coordinate all of this themselves.

**Participants and roles.**

- **`FindAnythingService`** (`core/FindAnythingService.java`) is the facade. It has one method per user-level operation: `search()`, `ask()`, `preview()`, `addSearchFolder()`, `removeSearchFolder()`, `addExclusion()`, `rebuildIndex()`, `testBackend()`, `setBackend()`, `setLocalOnly()`, `setRedaction()`, `getPrivacyLog()` and the conversation methods.
- **The subsystem classes** do the work and know nothing about the facade: `IndexConfig`, `SettingsStore`, `SearchIndex`, `Indexer`, `FileWatcher`, `SearchEngine`, `AgentController`, `ConversationStore`, `LlmClientFactory`, `FolderPolicy`, `Redactor` and `PrivacyLog`.
- **`GuiController`** (`ui/GuiController.java`) and **`CliApp`** (`ui/CliApp.java`) are the clients.

**How the code follows the pattern.** Each front end turns user input into facade calls and displays the result. The slow methods (`search()`, `ask()`, `preview()`, `testBackend()`, `loadEmbeddingModel()`) run on the facade's background executor and return a `CompletableFuture`. `GuiController` handles each future's result on the JavaFX thread; `CliApp` waits for it with `join()`. The read-only check is in the facade, so both front ends refuse a folder or policy change in a second process with the same message.

**Why it is useful.** The order of the cross-subsystem steps, the threading rules and the read-only check exist once. Every feature is written once and reached from two interfaces, which is why the GUI and the CLI behave the same in the demonstration.

**Match with Stage 1.** It matches. The facade has no `compare()`, since F10 is not implemented, and gains the operations that Section 4.2 lists (for example `loadEmbeddingModel()` and `setSemanticSearchEnabled()`).

### 3.2 Observer

**Design problem.** Indexing runs on its own worker thread, and its progress must appear in the GUI. The indexer must not depend on the GUI, because the CLI runs the same indexer with no window.

**Participants and roles.**

- **`Indexer`** (`indexing/Indexer.java`) is the subject. It keeps a list of listeners, and `notifyStatus()` sends each of them the current status.
- **`IndexStatusListener`** (`indexing/IndexStatusListener.java`) is the observer interface, with one method, `onStatusChanged(status)`.
- **`GuiController`** is the concrete observer. `onStatusChanged()` hands each update to the JavaFX thread, which updates the status indicator through `MainWindow.showStatus()` and runs the current search again when the index is up to date.
- **`IndexStatus`** (`indexing/IndexStatus.java`) is the state passed to observers: the indexer's state, the numbers of pending and failed files, and the number of chunks without embeddings.
- **`FindAnythingService.addStatusListener()`** lets a client register without knowing `Indexer`.

**How the code follows the pattern.** The subject knows only the interface. `Indexer.addStatusListener()` adds an observer, and the worker calls `notifyStatus()` after each piece of work. The CLI registers no listener and reads `getIndexStatus()` when it needs the status.

**Why it is useful.** The indexer runs unchanged with or without a GUI, and a new consumer of progress updates needs no change to it. The live-update demonstration (F03) is this pattern at work: a changed file reaches the results table through a status update.

**Match with Stage 1.** It matches.

### 3.3 Command

**Design problem.** The language model decides at run time which operation to run, by naming a tool and passing JSON arguments. The agent must validate, execute and record any of several operations in the same way, and adding a tool should not change the agent loop.

**Participants and roles.**

- **`Tool`** (`agent/Tool.java`) is the command interface, with `getSpec()`, `validate()` and `execute()`.
- **`SearchTool`** (`agent/SearchTool.java`) and **`ReadExcerptTool`** (`agent/ReadExcerptTool.java`) are the concrete commands.
- **`SearchEngine`** and **`SearchIndex`** are the receivers, which do the work.
- **`ToolRegistry`** (`agent/ToolRegistry.java`) is the invoker. `execute()` looks a command up by name, validates its arguments, executes it and records the call in its call log; when validation fails it returns `ToolResult.failure()` with the errors instead of executing.
- **`AgentController`** (`agent/AgentController.java`) is the client. `run()` passes each `ToolCall` in the model's reply to the registry and adds the `ToolResult` to the conversation.

**How the code follows the pattern.** A tool call from the model is already a request object; each operation is an object behind one interface, so every tool gets the same validation, error handling and logging. `ToolRegistry.getToolSpecs()` gives the model its tool descriptions from the same objects that execute them, so the descriptions can't drift from the code. `ToolRegistry.getCallLog()` keeps the trace of every call, which `findany ask --json` prints and which Stage 3's behavioural tests will use.

**Why it is useful.** `AgentController` has no switch on the tool name, and a new tool is one class and one `register()` call.

**Match with Stage 1.** It matches, with one difference. There are two concrete commands, not three: `CompareTool` belongs to F10, which is not implemented. `Tool.execute()` and `ToolRegistry.execute()` also take a `ShownPassages` object (`agent/ShownPassages.java`), the record of the passages the current request has already been given (Section 4.2). The roles are unchanged.

### 3.4 Adapter

**Design problem.** The system relies on outside libraries for three jobs with their own interfaces and data types: talking to language models (LangChain4j), parsing documents (Apache Tika) and running the embedding model. If those types spread through the code, the design would be tied to the libraries.

**Participants and roles.**

- **`LlmClient`** (`llm/LlmClient.java`), **`TextExtractor`** (`indexing/TextExtractor.java`) and **`EmbeddingModel`** (`embedding/EmbeddingModel.java`) are the target interfaces, in the system's own types: `LlmRequest`, `LlmResponse`, `Message`, `ToolSpec`, `ToolCall`, `ExtractedDocument` and `float[]` vectors.
- **`LangChain4jClient`** (`llm/LangChain4jClient.java`), **`IsolatedTextExtractor`** (`indexing/IsolatedTextExtractor.java`) and **`LocalEmbeddingModel`** (`embedding/LocalEmbeddingModel.java`) are the adapters.
- **LangChain4j's chat models, Tika's forked parser and the in-process ONNX embedding model** are the adaptees.
- **`AgentController`, `Indexer` and `SearchEngine`** are the clients. They know only the target interfaces.

**How the code follows the pattern.** `LangChain4jClient.complete()` converts an `LlmRequest`'s messages and tool descriptions to the library's types, calls the library's model for OpenRouter or Ollama, and converts the reply back to an `LlmResponse`; it also maps the library's errors to `LlmException`s with a message for the user. `IsolatedTextExtractor.extract()` returns the system's `ExtractedDocument` and its own exceptions. `LocalEmbeddingModel.embed()` takes texts and returns vectors. No class outside the three adapters imports a type of these libraries. `SearchIndex` keeps Apache Lucene inside in the same way.

**Why it is useful.** Replacing a library changes one class: moving the cloud backend from Anthropic's API to OpenRouter changed only `LangChain4jClient`. Tests use simple fakes of the three interfaces, such as a scripted `LlmClient`, so the agent loop is tested without a real model.

**Match with Stage 1.** It matches. The adaptee of `LangChain4jClient` for cloud models is LangChain4j's OpenAI-compatible chat model pointed at OpenRouter, not its Anthropic model (Section 4.2).

### 3.5 Decorator

**Design problem.** Every request sent to a cloud model must have content from local-only folders withheld and sensitive patterns redacted, and its final content must be recorded in the privacy log. Requests to a local model need none of this. These concerns shouldn't be written into the adapter, and the agent shouldn't be able to skip them by accident.

**Participants and roles.**

- **`LlmClient`** is the component interface.
- **`LangChain4jClient`** is the concrete component.
- **`LlmClientDecorator`** (`llm/LlmClientDecorator.java`) is the abstract decorator. It implements `LlmClient`, holds the wrapped client and delegates to it.
- **`RedactingLlmClient`** (`llm/RedactingLlmClient.java`) and **`LoggingLlmClient`** (`llm/LoggingLlmClient.java`) are the concrete decorators.

**How the code follows the pattern.** For a cloud model, `LlmClientFactory.createClient()` builds the chain `RedactingLlmClient` → `LoggingLlmClient` → `LangChain4jClient`. `RedactingLlmClient.complete()` withholds the messages whose source files are in a local-only folder, redacts the rest, and delegates. `LoggingLlmClient.complete()` appends what it received to the privacy log, then delegates. Because logging is inside redaction, the log records exactly what is sent. For a local model the factory returns the plain adapter.

**Why it is useful.** The agent sees an `LlmClient` and can't tell whether it is decorated, so no caller can skip redaction. The wrapping order states the privacy policy and is decided in one place. Each decorator is tested with a fake inner client.

**Match with Stage 1.** It matches.

### 3.6 Other patterns used

**Factory.** `LlmClientFactory.createClient(modelSettings)` (`llm/LlmClientFactory.java`) builds the right client for a conversation's model: the decorated chain for a cloud model, or the plain `LangChain4jClient` for a local one. It caches one client per `ModelSettings`. Callers never construct clients themselves, so the decision of which decorators apply is made in one place. It matches Stage 1.

**MVC.** `MainWindow`, `ResultsView`, `DetailPane` and `SettingsDialog` are views that only display data and report the user's actions through callbacks. `GuiController` handles those actions, calls the model and updates the views. `FindAnythingService` and the domain objects behind it are the model, which the CLI shares. Views never call the model. It matches Stage 1.

### 3.7 Updated diagrams

Every Stage 1 diagram has a Stage 2 version that describes the system as implemented, with the same file name, in `diagrams/stage2/`. The exception is SD05 Compare documents, which has no Stage 2 version because F10 is not implemented. The class diagrams show each class's public methods and important attributes; constructors, most getters, constants and private helpers are not drawn. The table lists what changed in each diagram, and Section 4 gives the reason for each change.

| Diagram | Changes from Stage 1 |
|---|---|
| Use-case diagram | UC06 Compare documents is removed with its links. The other use cases keep their numbers. |
| CD00 Package overview | Nine new dependencies: `ui` on `config`, `embedding`, `llm`, `privacy`, `search` and `storage`; `core` on `embedding`; `search` on `config`; `agent` on `privacy`. |
| CD01 `ui` and `core` | No comparison (`compare()`, `onCompareRequested()`, `showComparison()`). No `onAddExclusion()` or `showGlobalRules()`. Added: `onRemoveFolder()`, `showConversations()`, `showFilterError()`, `showPastExchange()`, `CliApp.createService()`, and the facade's `loadEmbeddingModel()`, `setSemanticSearchEnabled()`, `isSemanticSearchEnabled()` and `resolveModelSettings()`. |
| CD02 `storage` and `embedding` | No `ContentStore` and no `isReferenced()`. `SearchIndex` has the vector methods for chunks and for file names. `getFile()` returns `Optional`. `LocalEmbeddingModel` has `nativeLibraryFolder`. |
| CD03 `indexing` | `Indexer` has no `contentStore`. `FileWatcher` keeps one library watcher per folder and has `onChange()` and `onOverflow()`. The notes describe the retry limit, the two embedding steps and the quiet period. |
| CD04 `search` | No `DocumentComparator` or `DiffResult`. `SearchEngine` has `indexConfig`, and its note gives the similarity floor. |
| CD05 `agent` | No `CompareTool`, `summarizeDiff()` or `buildDiffSummaryRequest()`. `ShownPassages` is new, and `Tool.execute()` and `ToolRegistry.execute()` take it. Added: `buildSearchFirstRequest()`, `dropToolMessages()`, and the links from `AgentController` to `SearchIndex` and `FolderPolicy`, from `ReadExcerptTool` to `IndexConfig`, and from `ConversationStore` to `SettingsStore`. |
| CD06 `llm` | `LoggingLlmClient` has `modelSettings` and `clock`. The notes describe OpenRouter, the connection test and the withholding of whole messages. |
| CD07 `config` and `privacy` | `EnvFile` is new. `findFolder()` returns `Optional`; `isInActiveFolder()` is new. |
| SD01 | No `ContentStore` lifeline. A locked file is retried three times, then marked failed. The embedding phase runs after the commit, on the index alone, and embeds file names after the chunks. |
| SD02 | The similarity floor, the engine turning semantic search off when the model fails, and the warning shown to the user. |
| SD03 | The client is created and the agent runs on the background thread. Added: `dropToolMessages()`, `ShownPassages`, the search-first step, and what is saved. |
| SD04 | The provider is OpenRouter. |
| SD06 | `getFile()` is called in every case, before the branches. |
| SD07 | Exclusion rules are added from the CLI. Built-in exclusions can't be overridden. |
| SD08 | No API key is entered: `resolveModelSettings()` supplies it from `.env`. The connection test has three outcomes. |
| SM01 | New state Stopped. Idle prunes nothing. Embedding covers file names too. |
| AD01 | No comparison and no pruning. One watcher thread per search folder, started only by the GUI. |

**Updated class diagram.** As in Stage 1, the class model is shown as an overview of the packages (Figure 1) followed by one diagram per area (Figures 2 to 8). Together they form one model; the single combined diagram is [`diagrams/stage2/class-diagrams/cd-full.png`](diagrams/stage2/class-diagrams/cd-full.png).

![Package overview](diagrams/stage2/class-diagrams/cd00-overview.png)

*Figure 1. Package overview, as implemented: the ten packages and the dependencies between them.*

![ui and core](diagrams/stage2/class-diagrams/cd01-ui-core.png)

*Figure 2. `ui` and `core`: the GUI views, `GuiController` and `CliApp` all go through the `FindAnythingService` facade.*

![storage and embedding](diagrams/stage2/class-diagrams/cd02-storage.png)

*Figure 3. `storage` and `embedding`: the searchable index, with one entry per chunk of each file and the vectors of chunks and file names, and the embedding model.*

![indexing](diagrams/stage2/class-diagrams/cd03-indexing.png)

*Figure 4. `indexing`: the file watcher, the change queue, the single-writer `Indexer` and text extraction.*

![search](diagrams/stage2/class-diagrams/cd04-search.png)

*Figure 5. `search`: hybrid search.*

![agent](diagrams/stage2/class-diagrams/cd05-agent.png)

*Figure 6. `agent`: the agent loop, tools as commands, conversations, answers and citation verification.*

![llm](diagrams/stage2/class-diagrams/cd06-llm.png)

*Figure 7. `llm`: the model client interface, its adapter, the privacy decorators, the factory, and the request and response model.*

![config and privacy](diagrams/stage2/class-diagrams/cd07-config-privacy.png)

*Figure 8. `config` and `privacy`: search folders, exclusion rules, settings, the `.env` reader, redaction patterns, folder policies and the privacy log.*

**Updated use-case diagram.**

![Use-case diagram](diagrams/stage2/use-case-diagram.png)

*Figure 9. The use-case diagram, as implemented. UC06 Compare documents is removed.*

**Updated sequence diagrams.**

![SD01](diagrams/stage2/sequence-diagrams/sd01-index-file.png)

*Figure 10. SD01: Keep the index updated. The watcher only records changes in `ChangeQueue`. The indexing worker takes each path once it is ready, removes, rescans or indexes it, commits, notifies the listeners, and then embeds chunks and file names.*

![SD02](diagrams/stage2/sequence-diagrams/sd02-instant-search.png)

*Figure 11. SD02: Instant search. Filters are validated first. The search runs on a background thread, where keyword and semantic hits are retrieved and fused. Results from outdated queries are dropped. No language model is involved.*

![SD03](diagrams/stage2/sequence-diagrams/sd03-ask-agent-loop.png)

*Figure 12. SD03: Ask mode agent loop. The conversation's own model chooses tools, `ToolRegistry` validates and executes them, and the final answer's citations are verified against the index. If the model is unavailable, the request falls back to instant search.*

![SD04](diagrams/stage2/sequence-diagrams/sd04-send-request.png)

*Figure 13. SD04: Send a request to a cloud model. The request passes through the redaction and logging decorators before the adapter converts it to the library's types.*

![SD06](diagrams/stage2/sequence-diagrams/sd06-preview.png)

*Figure 14. SD06: Preview a result. The file's text is rebuilt from its chunks, with the matches or the cited passage highlighted.*

![SD07](diagrams/stage2/sequence-diagrams/sd07-manage-folders.png)

*Figure 15. SD07: Manage search folders and exclusions. These calls are quick; the indexing they cause happens later on the worker thread (SD01).*

![SD08](diagrams/stage2/sequence-diagrams/sd08-configure-backend.png)

*Figure 16. SD08: Configure the model backend and privacy settings, and view the privacy log. `LlmClientFactory` builds the decorated chain for a cloud backend and a plain client for a local one.*

**Updated state and activity diagrams.**

![SM01](diagrams/stage2/state-diagrams/sm01-index-lifecycle.png)

*Figure 17. SM01: the life cycle of the index in one process.*

![AD01](diagrams/stage2/activity-diagrams/ad01-threads.png)

*Figure 18. AD01: which thread does what.*

## 4. Changes and rationale

This section records every difference between the Stage 1 design and the implementation. Section 4.1 gives the full record for the one feature that is not implemented and the two that are modified. Section 4.2 lists every change in the structure Original design → Implemented change → Reason → Impact. No feature was added or replaced, and no design pattern was added or replaced; the change to the Command pattern's methods is in Section 4.2 under "Patterns".

### 4.1 Feature changes

#### F10 Document comparison: not implemented

1. **Original Stage 1 feature and ID.** F10 Document comparison. Two files are rebuilt from their chunks and diffed line by line; only the diff goes to the model, which summarizes it with verified citations. It is reached from "Compare" on two selected results, from `findany compare <fileA> <fileB> [--diff-only]`, and by the agent through `CompareTool`. The detail pane shows a highlighted side-by-side diff with the summary.
2. **Implemented feature and its behaviour.** None of F10's own parts. What exists is Ask mode (F07, F08, F09, F12): asked about two files, the agent searches, reads excerpts of both and answers with a citation from each, and every citation is verified. On the test corpus with the cloud model, "And how much is the rent?" gave $1,850 from the 2025–2026 lease and $1,925 from the 2026–2027 draft, both verified.
3. **What changed and why.** F10 was last in the order of work and was left out, decided on November 14. The benefit didn't justify the complexity:
   - **The agent already compares documents, only without a diff.** A question about what differs between two files is answered with citations from both.
   - **The cost.** F10 was the largest feature left: five classes and records, a third tool, a second model request with its own prompt, a facade method, a CLI command, selecting two rows in the table, and a side-by-side view.
   - **A line diff suits only two versions of one document.** For two different documents, such as two job offers or two course outlines, nearly every line differs, and the agent is the better tool.
4. **How the scope changed.** The user can still ask what differs between two files and gets a cited, verified answer. What is lost: completeness, since the agent finds the differences it looks for and not every changed line; comparing without a model (`--diff-only`); sending only the diff to a cloud model; and the side-by-side view.
5. **Affected use cases, classes, methods and patterns.** UC06 Compare documents and SD05 have no implementation. Not built: `DocumentComparator` (`diff()`), `DiffResult`, `DiffHunk`, `Comparison`, `CompareTool`, `FindAnythingService.compare()`, `AgentController.summarizeDiff()`, `PromptBuilder.buildDiffSummaryRequest()`, `GuiController.onCompareRequested()`, `DetailPane.showComparison()` and `findany compare`. `ResultsView` selects one row at a time. Command keeps its structure with two concrete commands; Facade has one operation fewer. Diagrams: CD01, CD04, CD05, the use-case diagram, SD03 (its note), AD01.

#### F12 Conversation follow-ups: modified

1. **Original Stage 1 feature and ID.** F12 Conversation follow-ups. The GUI keeps past conversations in a drawer with a "New conversation" button. A saved conversation whose model is no longer configured opens read-only.
2. **Implemented feature and its behaviour.** In Ask mode the toolbar has "New conversation" and a "Conversations" menu that lists the saved conversations, most recent first, each as its start time and first request. Choosing one shows its requests and answers in the detail pane and puts the last answer's files in the table, and the next request continues it. Each reopened citation is still a link that opens the file at the quote, but carries no verified or unverified mark; its tooltip says that verification isn't kept for saved conversations. The CLI continues the most recent conversation with `ask --continue`.
3. **What changed and why.** A menu fits the one-window GUI. The marks are absent because `ConversationStore` saves the messages and the last results, not the verification statuses; verifying again on reopening would cost an index lookup per citation and could differ from what the user saw at the time. A saved conversation always opens ready to continue: when its model can't be used, the next request gets the instant-search fallback with its "model unavailable" notice (F07), so a separate read-only state isn't needed.
4. **How the user need is still met.** Any saved conversation can be reopened, read and continued from the GUI or the CLI.
5. **Affected use cases, classes, methods and patterns.** UC04, UC05; `GuiController.onOpenConversation()`, `MainWindow.showConversations()`, `DetailPane.showPastExchange()`. Patterns unchanged. Diagrams: CD01.

#### F01 Search folder management: modified

1. **Original Stage 1 feature and ID.** F01 Search folder management. A folder chip's menu has "Edit exclusions", "Pause", "Local model only" and "Remove", and Settings > Indexing holds the global exclusion rules.
2. **Implemented feature and its behaviour.** The CLI does everything: `findany folders add`, `remove`, `pause`, `resume` and `list`, and `findany exclude add`, `remove` and `list`. In the GUI, "Add folder" opens a folder picker, and each folder is a chip whose menu offers "Local model only" and "Remove".
3. **What changed and why.** Pausing a folder and editing exclusion rules are done from the CLI, to fit the time available. The CLI covers every part the GUI leaves out, so the whole feature stays reachable.
4. **How the user need is still met.** Every operation of F01 is available; the two less frequent ones are typed, not clicked.
5. **Affected use cases, classes, methods and patterns.** UC01, UC02; `MainWindow`, `GuiController` (`onRemoveFolder()` is new; `onAddExclusion()` isn't built), `SettingsDialog` (`showGlobalRules()` isn't built). Patterns unchanged. Diagrams: CD01, SD07.

### 4.2 All changes

**Features**

| Original design | Implemented change | Reason | Impact |
|---|---|---|---|
| F10: a line-by-line comparison of two files with a model-written summary. | Not implemented. Ask mode answers what differs between two files, with a verified citation from each. | The benefit didn't justify the complexity (Section 4.1). | UC06, SD05; the classes listed in Section 4.1. Diagrams: CD01, CD04, CD05, use-case diagram, SD03, AD01. |
| F12: past conversations in a drawer; a conversation whose model is not configured opens read-only. | A "Conversations" menu. Reopened citations carry no verification mark. A reopened conversation is always ready to continue. | A menu fits the one-window GUI; the statuses aren't saved; the fallback already covers an unusable model (Section 4.1). | UC04, UC05; `GuiController`, `MainWindow`, `DetailPane`. Diagrams: CD01. |
| F12: every request, tool result and answer stays in the conversation and is saved. | Tool calls and results are kept only for the exchange in progress. `AgentController.run()` calls the new `Conversation.dropToolMessages()` when the next request starts, and `ConversationStore.save()` never writes them. | Found in real use on November 8: tool results were 89 to 98 percent of three real conversations, and one question's results were twice the history limit, so the follow-up lost the whole first exchange. Old results also go stale when files change. | F12; `Conversation`, `AgentController`, `ConversationStore`. A follow-up sees earlier requests and answers and searches again when it needs more. Diagrams: CD05, SD03. |
| F01, F02, F03, F13: a chip menu with "Edit exclusions" and "Pause"; Settings with the global exclusion rules, a "Rebuild index" button and a list of supported formats; a comparison view. | One window and the settings dialog. The chip menu offers "Local model only" and "Remove". Pausing, exclusion rules and rebuilding are CLI commands. The dialog has the Model section, the Privacy section and the privacy log. | Time. The CLI covers every part the GUI leaves out, so each feature stays reachable. | UC01, UC02, UC08; `MainWindow`, `GuiController` (`onRemoveFolder()` added, `onAddExclusion()` not built), `SettingsDialog` (`showGlobalRules()` not built). Diagrams: CD01, SD07, SD08. |
| F04: "A Filters menu beside the search folder chips". | A "Filters" toggle button opens a row under the chips: a type menu, two date pickers, two size fields, a location menu and "Clear filters". Every change goes to `GuiController.onFiltersChanged()`, the design's method. | A row stays open while dates and sizes are entered, and shows at a glance which filters are on. | F04; `MainWindow`, `GuiController`, `ResultsView`. Nothing changed below the facade. Diagrams: CD01. |
| F04: the agent's search tool applies the same four filters. | `SearchTool` takes `query`, `file_type`, `modified_after`, `modified_before` and `limit`. | A fixed type list makes an unknown file type a rejectable error. A location would have to be checked against `IndexConfig`, which the class diagram doesn't give `SearchTool`, and size is rarely part of a request. | F04, F07; `SearchTool`. |
| F07, F12: `findany ask` with no arguments starts an interactive session. | `ask` needs a request. Follow-ups use `ask --continue "<request>"`. | Time. `--continue` already gives the CLI multi-turn conversations. | F07, F12; `CliApp`. |
| F13: the user enters the API key in Settings. | The key is read from `.env` (git-ignored) or the environment; `.env.example` is committed with placeholders. The dialog shows whether a key is configured and never asks for one. | The Stage 2 instructions require keys in a local configuration file. | F13, UC08; `SettingsDialog`, `ModelSettings`, `SettingsStore`; the new `config/EnvFile`. Diagrams: CD07, SD08. |
| F14, UC10: only a search folder can be marked local-only; the facade's `setLocalOnly()` and `setRedaction()` have no precondition on the process. | `setLocalOnly()` accepts a search folder or a folder inside one. Both methods are refused in a read-only process, as folder changes are. | One private subfolder can be protected without keeping the whole search folder from cloud models. A change made by a second process would not reach the policy the first process is applying. | F14, UC10; `FindAnythingService`, `FolderPolicy`. |
| F03: a locked file "is retried after another quiet period". | `Indexer.indexFile()` retries a file that can't be read up to three times (`MAX_READ_RETRIES`), then marks it `FAILED`. | Without a bound, a permanently locked file would be retried forever and the status would never reach "up to date". | F03; `Indexer`. A failed file is tried again when it changes or after `index rebuild`. Diagrams: SD01. |
| F02 extracts "title, author, modification date, page or slide count" with the text. | `ExtractedDocument` holds the content type and the pages. The modification date comes from the file system; no title or author is read. | Search and the filters work on a file's name, text, type, date and size. | F02; `ExtractedDocument`. Diagrams: SD01. |
| F03 has `findany watch`, and every command first catches up on changed files. | There is no `watch` command. The GUI watches while it is open, and every command that reads or changes the index first catches up. The `model`, `privacy`, `policy` and `privacy-log` commands don't use the index and skip the catch-up. | The index is current whenever it is used. | F03; `CliApp`. |
| F01, UC02 (1a): the user can override a built-in exclusion after confirmation. | The built-in rules always apply (`IndexConfig.isIncluded()`). | A credential file can then never reach the index or a model, and no confirmation step is needed. | F01, UC02; `IndexConfig`. Diagrams: SD07. |
| F08: a model error gives "the same fallback as F07, with a retry option". | The fallback is there, without a retry control. | The request stays in the conversation, and the user sends it again. | F08; `GuiController`, `CliApp`. |

**Architecture**

| Original design | Implemented change | Reason | Impact |
|---|---|---|---|
| The cloud backend is Anthropic's API; `LangChain4jClient` adapts LangChain4j's Anthropic chat model. | `LangChain4jClient` adapts LangChain4j's `OpenAiChatModel` pointed at OpenRouter's OpenAI-compatible API. Cloud models, including Claude models, are chosen by their OpenRouter model ID. | Payment for Anthropic Console credits couldn't be completed. | F13, F14. Redaction and the privacy log apply to everything sent, and every request tells OpenRouter not to route to providers that train on prompts. The Adapter pattern is unchanged. Diagrams: CD06, SD04, SD08. |
| `ContentStore` keeps chunks and embeddings by content hash, so renamed, moved and copied files reuse them; `pruneUnreferenced()` cleans it up. | There is no `ContentStore`. `Indexer` extracts every new path; `IndexedFile.contentHash` is still computed and stored. `SearchIndex.isReferenced()`, which only served the store, isn't built. | Time. Without the store, a renamed file costs one more extraction (tens of milliseconds) and one more embedding. | F02, F03, F05; `Indexer`, `FindAnythingService`, `SearchIndex`. Files with the same content waiting to be embedded together share one embedding. Diagrams: CD02, CD03, SD01, SM01, AD01. |
| The chunks to embed come from `ContentStore.get()`, and `addEmbeddings()` writes to both stores. | `addEmbeddings()`, `findContentWithoutEmbeddings()` and `vectorSearch()` as designed, on the index alone, plus `getChunksOfContent(contentHash)` and `countChunksWithoutEmbeddings()`. | The index already holds every chunk's text and each file's content hash. | F05; `SearchIndex`, `Indexer`. Diagrams: CD02, CD03, SD01. |
| CD00 draws `ui` using `core`, `agent` and `indexing`. | `CliApp.createService()` builds the objects the facade needs and passes them in, and the views show types from the lower packages. So `ui` also uses `config`, `embedding`, `llm`, `privacy`, `search` and `storage`. | `CliApp` is the entry point, and with constructor injection one place has to build every object. This keeps the facade free of construction code and lets tests pass in their own objects. | `CliApp`. The Facade pattern is unchanged, and the dependencies still point one way with no cycles. Diagrams: CD00. |
| `FileWatcher` runs on one thread with `watchedFolders: Set<Path>`; the quiet period is about two seconds. | One library watcher per search folder (`Map<Path, DirectoryWatcher>`), each with its own thread. `findany gui` opens the index with `open(true)`; other commands run once and rescan. The quiet period is two seconds in the GUI and zero for the other commands. Two package-private methods, `onChange()` and `onOverflow()`, hold the decisions. | A watcher of the directory-watcher library can't change its folders after it is built, and search folders never overlap. The two methods use the design's own types, so they are tested without waiting for the operating system. | F01, F03; `FileWatcher`, `FindAnythingService`, `CliApp`. Diagrams: CD03, AD01. |
| Each chunk keeps its page or slide number. | PDF pages and PowerPoint slides are numbered. DOCX, TXT and MD files are one unnumbered page, so their citations name the file and no page, and `CitationVerifier` searches the whole file for them. | These formats store no page boundaries (verified with Tika 4.1.0). | F02, F09, F11; `Chunk`, `Citation`, `CitationVerifier`. |

**Classes**

| Original design | Implemented change | Reason | Impact |
|---|---|---|---|
| `LlmClient.testConnection()` returns a boolean, and F13 tells the user what is wrong. | It still returns a boolean, and throws `LlmException` when the backend can't be used. `true` means the backend answered, `false` that it was reached but replied with nothing; the exception's message says what to fix. | The message F13 asks for needs the cause, which a bare `false` can't carry. | F13; `LlmClient`, `LangChain4jClient`, and the facade's `testBackend()`. Diagrams: CD06, SD08. |
| CD05 gives `AgentController` four collaborators and `ReadExcerptTool` the `SearchIndex`; CD04 gives `SearchEngine` the `SearchIndex` and the `EmbeddingModel`. | `AgentController` also takes the `SearchIndex`, to turn the paths of an answer's files into `SearchResult`s. `SearchEngine` and `ReadExcerptTool` also take the `IndexConfig` and leave out files that aren't in an active folder (new method `IndexConfig.isInActiveFolder()`). | Every caller searches and reads through these two classes, so paused and unavailable folders are skipped in one place. | F01, F06, F07, F08; `AgentController`, `SearchEngine`, `ReadExcerptTool`, `IndexConfig`. The `search` package uses `config`. Diagrams: CD00, CD04, CD05. |
| The agent loop takes a reply without tool calls as the final answer, at any step (SD03). | When the first reply to a request has text and no tool call, `AgentController.run()` asks once more through the new `PromptBuilder.buildSearchFirstRequest()`: search now, or reply with only `NO SEARCH NEEDED`. It is never asked a third time. | Local models sometimes answer "your files don't say" without having looked. Requiring a tool call would force a search for "hi" too; the fixed reply lets the model decline. | F07, F12; `AgentController`, `PromptBuilder`. Diagrams: CD05, SD03. |
| `LocalEmbeddingModel` has one field, `modelFile: Path`. A model that fails to load is found at startup. | No `modelFile`: the model is inside LangChain4j's jar, and the class takes `nativeLibraryFolder: Path`. The model loads on the first `embed()` call; the GUI makes that call at startup through the facade's new `loadEmbeddingModel()`. Failure is the new unchecked `EmbeddingException`; when a search meets it, `SearchEngine` turns semantic search off, logs a warning and returns the keyword results. | Loading takes about 0.9 seconds (measured), which a command such as `findany show` would pay for nothing. With loading on first use, a failure appears inside a search, so the engine handles it. | F05; `LocalEmbeddingModel`, `SearchEngine`, `FindAnythingService`. The `core` package uses `embedding`. The Adapter pattern is unchanged. Diagrams: CD00, CD01, CD02, CD04, SD02. |
| `SearchEngine.search()` merges the semantic hits with the keyword hits in `fuseRankings()`. | As designed, with one rule added: a chunk is a semantic match only at a cosine similarity of 0.62 or more (`SearchEngine.MIN_SIMILARITY`). The facade gains `setSemanticSearchEnabled()` and `isSemanticSearchEnabled()`, which `findany search --keyword-only` uses. | Vector search always returns the nearest chunks, however far, so without a floor "No results" never appears. Measured on the test corpus with 56 queries: 24 of the 25 about things the corpus doesn't hold scored 0.49 to 0.61, and 25 of the 31 about things it holds scored 0.63 to 0.90. | F05, F06, F07; `SearchEngine`, `FindAnythingService`, `CliApp`. Diagrams: CD01, CD04, SD02. |
| Only chunk text is embedded; a file's name is matched by keywords. | Every indexed file also gets a vector for its name, written with its folder's name (`Indexer.nameText()`). `SearchIndex` gains `findFilesWithoutNameEmbeddings()` and `addNameEmbedding()`, and `vectorSearch()` looks up the nearest chunk texts and the nearest names. | A résumé rarely says what it is in its text, but its name does. Measured against the query "cv": the résumé's text 0.56 to 0.57, under the floor; `Résumé/resume.pdf` as a name 0.68, against 0.45 to 0.60 for eleven unrelated names. | F05, F06; `SearchIndex`, `Indexer`. "cv" finds the résumé, which is F05's own example. Diagrams: CD02, CD03, SD01, SD02. |
| CD05 gives `ConversationStore` only `conversationsDirectory`. | It also takes a `SettingsStore`. | The API key isn't saved with a conversation and is supplied on loading. | `ConversationStore`. Diagrams: CD05. |
| CD06 gives `LoggingLlmClient` only `privacyLog`. | It also takes the conversation's `ModelSettings` and a `Clock`. | Each log entry gives the model's name and the time. | `LoggingLlmClient`. Diagrams: CD06. |
| CD06 gives a `Message` a role (user, assistant or tool), its content and its source files. | `Message.Role` also has `SYSTEM`, and a message carries `toolCalls`, `toolCallId` and `toolName`. | Chat APIs give the system prompt its own role and match each tool result to its call. | `Message`. Diagrams: CD06. |
| CD05 gives `PromptBuilder` the method `buildReformatRequest(conversation)`. | The method also takes `toolSpecs`, `today` and `problems`. | The request needs the tools its history refers to, and the problems tell the model what to correct. | `PromptBuilder`. Diagrams: CD05. |
| `IndexConfig.findFolder()` and `SearchIndex.getFile()` return the object. | Both return `Optional`. | A folder or file that isn't there is an ordinary case. | `IndexConfig`, `SearchIndex`. Diagrams: CD02, CD07. |
| F13 appends every request to a cloud model to the privacy log. | The connection test isn't appended. | `LangChain4jClient.testConnection()` sends its own fixed one-line prompt, which holds no user data. | F13; `LangChain4jClient`. |
| SM01 ends the index's life at Stopping. | `IndexerState` also has `STOPPED`. | A caller can tell a worker that is stopping from one that has stopped. | `IndexerState`. Diagrams: SM01. |

**Patterns**

| Original design | Implemented change | Reason | Impact |
|---|---|---|---|
| Command: `Tool.execute()` takes the call's arguments and `ToolRegistry.execute()` takes the `ToolCall`. `SearchTool` gives each matching file with its best passage on every call. There are three concrete commands. | New class `agent/ShownPassages`, one per request, with `record()` and `queryThatGave()`. `Tool.execute(arguments, shownPassages)` and `ToolRegistry.execute(toolCall, shownPassages)` take it. Within one request a passage's text is sent once; a later search that finds it again names the earlier search in place of the text. `AgentController` takes the `FolderPolicy`, so a result withheld from a cloud model records nothing. There are two concrete commands, since `CompareTool` belongs to F10. | Measured on November 12 with one question: two searches returned the same ten files, so half of the 34,019-character request was repeated. A smaller request leaves a local model's context room for more steps. The record is passed with each call because the registry and its tools are shared by all requests. | F07, F14. The pattern keeps its roles (command, concrete commands, invoker, client, receivers), with one more parameter on the two `execute()` methods. `SearchTool`, `ReadExcerptTool`, `AgentController`, `FindAnythingService`; the `agent` package uses `privacy`. Diagrams: CD00, CD05, SD03. |

## 5. AI tools and collaboration log

### 5.1 Tools and models

| Tool or platform | LLM/model | Used for | Total tokens | Money cost |
|---|---|---|---|---|
| Claude Code (command-line coding agent) | Claude Fable 5.1 (to November 10), Claude Fable 5.2 (from November 12) and Claude Opus 5.5 | All implementation and test code for F01 to F09 and F11 to F14, in every package; diagnosing defects; measurements; setting up the local model | 294,412,402 in total, of which 10,972 input, 1,967,454 output, 6,562,028 written to the prompt cache and 285,871,948 read from the prompt cache | US$100: one month of the Claude Max subscription, a flat fee with no charge per token |
| Claude (claude.ai app) | Claude Opus 5.5 | Stage 2 planning, testing Apache Tika 4.1.0 before choosing it, the project's setup files and the test corpus | Not reported by the app | Included in the same subscription |

The Claude Code totals cover the implementation: the 16 sessions up to the code freeze on November 14, with their sub-agents. They are summed from the token counts that Claude Code records for every model response. The later sessions, which produced the diagrams and this report, are not counted. By model:

| Model | Responses | Input | Output | Cache written | Cache read |
|---|---|---|---|---|---|
| Claude Fable 5.1 | 591 | 9,440 | 1,099,304 | 3,842,266 | 173,889,310 |
| Claude Fable 5.2 | 444 | 1,118 | 727,963 | 1,926,173 | 98,275,270 |
| Claude Opus 5.5 | 182 | 414 | 140,187 | 793,589 | 13,707,368 |

Most of the total is cache reads: the conversation so far is read again from the cache at every step of a session.

The application's own cloud model is not a development tool, but it was called while testing the agent and recording the demonstration: `anthropic/claude-haiku-5.5` through OpenRouter cost US$0.2761 in total. The local model and the embedding model run on my own computer and cost nothing.

### 5.2 How the work was divided

The AI wrote most of the code and its tests. I set the scope and the order of work, approved a plan before each feature, reviewed every diff, and had each commit made only after I had reviewed it. I tested each stage myself, in the GUI and the CLI, on my own documents, which found defects the automated tests had not (Section 6.3). The AI worked under written rules in the repository's `AGENTS.md`: one commit at a time, the design's names, no downloads without permission, API keys never read or printed, and a stop before any change to the design.

The interactions below are representative. Each gives the task, what the AI contributed, what I contributed, and the outcome.

### 5.3 Using the AI to decide before building

**Estimating semantic search (F05), November 14.** Claude Code, Claude Fable 5.2.
- **Task:** decide whether F05 fitted in the last day before the code freeze.
- **AI contribution:** on my request for an estimate of the complexity, it found what the code already had for F05 (the embedding field on `Chunk`, the `EMBEDDING` state, a tested `fuseRankings()`, an empty `embedNextBatch()`), estimated five commits, and recommended skipping the feature because of the time left and the new native libraries.
- **Human contribution:** I decided to build it against that recommendation, asking for "a simple approach that has the possibility for further improvement later", and approved a plan of six commits with its design changes listed.
- **Outcome:** F05 implemented in six commits, [3047ef5](https://github.com/amarz45/find-anything/commit/3047ef5dd1a9d107dd073f9971ef677dfee58416) to [0d3f499](https://github.com/amarz45/find-anything/commit/0d3f499a56770edfa5df6124c037518f717ece41).

**Sizing the live file watcher (F03), November 14.** Claude Code, Claude Fable 5.2.
- **Task:** decide whether live index updates could still be built.
- **AI contribution:** I asked only for the relative complexity and the requirements, with no plan and no recommendation: "then I'll decide." It read the indexing code and judged the work small to medium, because `ChangeQueue` and `Indexer` already handled any offered path and only the event source was missing.
- **Human contribution:** I decided to build it, asked for a plan that prefers simplicity, ruled out any test that could be flaky, and required the library's documentation in place of the AI's memory for three claims about the library's behaviour. Checked against the library's sources, two held and one was wrong.
- **Outcome:** [a7ca3f5](https://github.com/amarz45/find-anything/commit/a7ca3f5c0dff048881b209f2fa61a623cf06deb5), [1f06018](https://github.com/amarz45/find-anything/commit/1f0601896d179f641f6b4a865b5f20b2b3bedc5e).

**Whether document comparison (F10) was worth building, November 14.** Claude Code, Claude Fable 5.2.
- **Task:** decide on the last feature in the order of work.
- **AI contribution:** I asked what comparison adds, given that the agent can already be asked about two files and cites both. It read the design and set out both sides: what the diff adds (every changed line, a comparison without a model, only the diff sent to a cloud model, the side-by-side view) and where it is weak (it suits only two versions of one document).
- **Human contribution:** I raised the question and made the decision: not enough benefit for the complexity. I also had a leftover code comment that still described the feature removed: code is documentation.
- **Outcome:** F10 recorded as not implemented, [ec02050](https://github.com/amarz45/find-anything/commit/ec020508ad9d1379950b62dcd0b2aff2896a13e0).

### 5.4 Using the AI to investigate with evidence

**Testing Apache Tika before choosing it, October 16.** Claude (claude.ai app), Claude Opus 5.5.
- **Task:** decide whether Tika 4.1.0's forked parser was reliable enough for `IsolatedTextExtractor`.
- **AI contribution:** told to test Tika itself, it generated sample documents and a probe program, and tested page and slide markers, crashes, timeouts and cancellation. It found that Tika reports success for unreadable files and that PowerPoint slides come out in the wrong order, and wrote a reference extractor that handles both.
- **Human contribution:** I asked for tests in place of claims, supplied the official release because the AI's environment couldn't download it, and decided to keep Tika 4.1.0.
- **Outcome:** the findings and the reference extractor that `IsolatedTextExtractor` was adapted from.

**The Windows CI timeout, October 26.** Claude Code, Claude Fable 5.1.
- **Task:** find out why one test timed out after 60 seconds on the Windows CI runner while taking 3.5 seconds on Linux.
- **AI contribution:** it found that the uploaded report said nothing useful, so it first made the failure diagnosable: timestamps, the index status in the timeout message, and report uploads on every run. The next report showed one PDF taking 60 seconds. It traced that to PDFBox scanning every system font the first time it meets a non-embedded font, confirmed it locally with an empty cache folder, and moved the cache into the application's data folder.
- **Human contribution:** I reviewed and committed each step separately and chose to push and wait for data over acting on the AI's first two guesses (a character-set warning and a stuck child process), which the timestamps then ruled out.
- **Outcome:** [4db5034](https://github.com/amarz45/find-anything/commit/4db50340110ffd349bc29bdd0183b411d1c96516), [637839d](https://github.com/amarz45/find-anything/commit/637839dcab5862074db335594af0cd6de3ebbd49); CI green on both runners.

**A follow-up that denied its earlier answer, November 8.** Claude Code, Claude Fable 5.1.
- **Task:** testing the GUI on my own documents, I found that a follow-up question began "This conversation doesn't include an earlier answer from me".
- **AI contribution:** under a scoped permission (the structure of the saved conversations and one folder's file names, never any file's contents), it found the cause: the first exchange held about 78,000 characters of search results, twice the history limit, so trimming dropped it whole. It then implemented the approved change, in which tool results are kept only for the exchange in progress.
- **Human contribution:** I refused a fix until the cause was understood ("Don't fix before we understand the cause") and led the diagnosis with questions: what exactly is stored, why search results are saved at all, whether they go stale, and what the limit is for. I rejected the AI's two unclear variants, required one approach that handled both details it had raised, and verified the result.
- **Outcome:** [fbb0b7e](https://github.com/amarz45/find-anything/commit/fbb0b7e044409cb1df8746ab8bad0b575cb7b494); the change is in Section 4.2.

**Measuring smaller search results, November 12 to 13.** Claude Code, Claude Fable 5.2.
- **Task:** make the agent's requests smaller so a better local model fits in 16 GB of graphics memory.
- **AI contribution:** it measured a real request from the privacy log (15,665 characters per search, and 34,019 for two searches that returned the same ten files), proposed three changes, and wrote them with tests, one commit each. From Ollama's log it showed that `qwen3:14b` loads entirely on the graphics card and how fast it reads and writes.
- **Human contribution:** I agreed to the three ideas and no more, and ran the same two questions on my own documents before and after. Those runs gave the only like-for-like figures: the AI's first figure of 41 percent was a replay of an older log, not a measurement. The real saving was from 34,019 to 12,385 characters. I downloaded and tested the 14B model myself.
- **Outcome:** [add80fd](https://github.com/amarz45/find-anything/commit/add80fd004c538008225df69074c4654a84dc6d8), [5f41301](https://github.com/amarz45/find-anything/commit/5f41301bc2860ed5a42bc3c5c2df511e49a2b27c), [158e6d0](https://github.com/amarz45/find-anything/commit/158e6d0f181b801d333c6ef61dedb86ad5136bb9).

**The embedding libraries, November 14.** Claude Code, Claude Fable 5.2.
- **Task:** add a local embedding model without surprises for the user.
- **AI contribution:** it read the libraries' sources and found two things to prevent: the tokenizer library unpacks into the user's home folder, and with CUDA present it downloads another build of itself. `LocalEmbeddingModel` prevents both. On the way it found two index defects before they shipped: a vector of the wrong length would have deleted a file's entries, and two files with the same hash and different chunks would have made the embedding phase loop forever. It set the similarity floor from 56 measured queries.
- **Human contribution:** I gave permission for the dependency and the model, stopped the AI twice when it went past what I had permitted, and tested each stage on my own documents.
- **Outcome:** [3047ef5](https://github.com/amarz45/find-anything/commit/3047ef5dd1a9d107dd073f9971ef677dfee58416), [11c209a](https://github.com/amarz45/find-anything/commit/11c209a4bf6fd22779a2e87b244ce75c35881c9c), [dca7718](https://github.com/amarz45/find-anything/commit/dca7718bd4b2e4240d5213cbf5bd0daac9fd7fce).

### 5.5 Reviewing and correcting the AI's output

**Paused folders reached by the agent, November 5.** Claude Code, Claude Fable 5.1.
- **Task:** finish the agent loop and `findany ask`.
- **AI contribution:** it wrote `AgentController`, `ask()` with the instant-search fallback, and the `ask` command, with 23 tests.
- **Human contribution:** reading the fallback code in review, I asked "Why does only the fallback skip them?" The agent's own tools were searching and reading files in paused folders, so the agent could answer from them. I had it fixed before the next item was started.
- **Outcome:** the filter moved into `SearchEngine` and `ReadExcerptTool`, so every search path honours the folder state: [6eda059](https://github.com/amarz45/find-anything/commit/6eda05959a0ef27462a32fbce530058ed2e54c84).

**"CV" didn't find the résumé, November 14.** Claude Code, Claude Fable 5.2.
- **Task:** semantic search was working, but on my documents "cv" did not list my résumé files.
- **AI contribution:** it proposed comparing other embedding models.
- **Human contribution:** I refused that as an unnecessary test and pointed at the cause: a file named "resume" should match "CV". "Is the search only for file contents?" It was. A measurement confirmed it: the file's name scores 0.68 against "cv", and a résumé's text 0.56 to 0.57, under the floor.
- **Outcome:** file names are embedded too: [f82ee54](https://github.com/amarz45/find-anything/commit/f82ee542d9c20d6fc2dc90e776f2a48303957bde).

**A hard-coded thread count, November 14.** Claude Code, Claude Fable 5.2.
- **Task:** make embedding fast.
- **AI contribution:** it hard-coded four embedding threads on the strength of a benchmark.
- **Human contribution:** I rejected it: more threads should be faster up to the core count, and a number fitted to my machine may not suit others. The second measurement showed that the library was already using all 16 cores, so the benchmark's "threads" were not the threads doing the work.
- **Outcome:** no setting was faster, and the thread pool was removed before the commit.

**A model that answered without searching, November 13.** Claude Code, Claude Fable 5.2.
- **Task:** the local model said my transcript holds no GPA, which is on its first page.
- **AI contribution:** it reproduced the failure in a scratch folder, showed that the index was fine and that the model's first reply made no tool call, and proposed three fixes. It measured each implemented fix with three runs and reported that its prompt sentence alone had not fixed the failure.
- **Human contribution:** I questioned each fix before agreeing to any. I asked how the AI knew a "read the rest of the file" hint wouldn't make a weak model read whole files (it didn't know, and the hint was dropped). I rejected a specific example in a general prompt. I raised the "hi" case against forcing a search: a user may greet the model just to see that it works.
- **Outcome:** the fixed reply `NO SEARCH NEEDED`, with which the model can decline: [94d474d](https://github.com/amarz45/find-anything/commit/94d474d162387bdf215203a9d324c0193caab83c), [d063b8c](https://github.com/amarz45/find-anything/commit/d063b8c30d819885d9b09f37c5435a558f9ef90b).

### 5.6 Decisions against the AI's recommendation, and rules

**IDE integration, October 18.** Claude (claude.ai app), Claude Opus 5.5.
- **AI contribution:** it recommended one IDE plugin and then another, without checking either one's maintenance or reviews.
- **Human contribution:** I checked the marketplace pages, rejected both for their poor reviews, and chose a third tool.
- **Outcome:** the setup uses the tool I chose, and the AI was told to check maintenance and reviews before recommending a tool.

**Rules after the AI overstepped, November 12.** Claude Code, Claude Opus 5.5 and Claude Fable 5.2.
- **AI contribution:** asked how to install a local model, it downloaded a 5.2 GB model without asking. Later it edited source files with scripts and sent runs of small edits that each needed a separate approval.
- **Human contribution:** I had the edits undone, stopped the work, and moved it to a new session with the plan saved. I added three rules to `AGENTS.md`: no downloads without permission, no agent memory outside the repository, and files edited only with the editing tools.
- **Outcome:** [f694da8](https://github.com/amarz45/find-anything/commit/f694da8336a0c6657b5d19e7d8537e98cf070dfe). The same work was then done under the rules in three reviewed commits (Section 5.4, "Measuring smaller search results").

## 6. Development and verification summary

### 6.1 Weekly commit summary

Each commit below changes implementation or test code. Dates are in Toronto time.

| Period | Dates | Code commits | Count |
|---|---|---|---|
| 1 | October 20–26 | [8209963](https://github.com/amarz45/find-anything/commit/8209963021fb3b575c2814139f63cddcdd7890bd), [ade7514](https://github.com/amarz45/find-anything/commit/ade7514a3b350ef0a1ef8b294de8596b04de988b), [5f3c464](https://github.com/amarz45/find-anything/commit/5f3c464315df93879d7f5d4e269c4a33d2d9fccf), [391a21b](https://github.com/amarz45/find-anything/commit/391a21b325bcb68dd102a6ab26bd1eef04893a92), [3ae1cc9](https://github.com/amarz45/find-anything/commit/3ae1cc93c055709c2ceafb08460a810f4f6fb8bc), [67e0752](https://github.com/amarz45/find-anything/commit/67e07528a4913e57b04a11f906aaa1857210c18b), [57d2b06](https://github.com/amarz45/find-anything/commit/57d2b0612cf9ff99e6e06e0c5f6c123042235d51), [46ddd7e](https://github.com/amarz45/find-anything/commit/46ddd7eb9d1a9a30a23281e9b81323d5f0e00ad5) | 8 |
| 2 | October 27–November 2 | [482f8bf](https://github.com/amarz45/find-anything/commit/482f8bfd20a1a6ab526787bc133c815ef1d71607), [35d6a30](https://github.com/amarz45/find-anything/commit/35d6a30d07bd93bdffbed4f35c29c0b01fc482ec), [1926a4f](https://github.com/amarz45/find-anything/commit/1926a4f592c5ff1e61968910ad59ed827877fd3a), [6a41455](https://github.com/amarz45/find-anything/commit/6a41455fdeceed41634a008e51b60b43a2a04fba), [fd5d111](https://github.com/amarz45/find-anything/commit/fd5d1118b07c8ef9b0bc45627156eb082e68711f) | 5 |
| 3 | November 3–9 | [3a2308e](https://github.com/amarz45/find-anything/commit/3a2308edbba32a94b4a6b032cc312ca14f60de55), [95fbd3e](https://github.com/amarz45/find-anything/commit/95fbd3ee5606a575381021b810055e71051c1225), [07803b4](https://github.com/amarz45/find-anything/commit/07803b4f71eb6f24f7296f419b9862d15f3d6298), [14e54c7](https://github.com/amarz45/find-anything/commit/14e54c7e17fb7278d3a34f9e7ec5862ebfac8f50), [c7ccfbc](https://github.com/amarz45/find-anything/commit/c7ccfbcc752e14cca92cf86bc40c8a24d788d21a), [ad59845](https://github.com/amarz45/find-anything/commit/ad59845a048cfdee2545f5bca929b8eed0f0a6b6), [2921dc2](https://github.com/amarz45/find-anything/commit/2921dc2761deeef3ac9ff760bca07ce841f70b0e), [b569b59](https://github.com/amarz45/find-anything/commit/b569b591686778f71a717bbcefc2744eb289cd36), [6ce1c9a](https://github.com/amarz45/find-anything/commit/6ce1c9a89fa84d280217caecc0b624a531257d0f), [308a42c](https://github.com/amarz45/find-anything/commit/308a42cef1a7174bac3aea1f39d8611a0adf6d04), [6eda059](https://github.com/amarz45/find-anything/commit/6eda05959a0ef27462a32fbce530058ed2e54c84), [b15008f](https://github.com/amarz45/find-anything/commit/b15008fba12544758bb182d1d86ac1e70f2b3583), [6068d97](https://github.com/amarz45/find-anything/commit/6068d97b9099bc7a9cc7e6e565ccd5f1928a8798), [de99189](https://github.com/amarz45/find-anything/commit/de991895fa720017abd64a90ef867f8c775eea50), [70726e6](https://github.com/amarz45/find-anything/commit/70726e649eab4d5dc3e99185261d8ed4fdd5e0fa) | 15 |
| Extension | November 10–15 | [29866ae](https://github.com/amarz45/find-anything/commit/29866ae93ae13c31c5e7cbd46c3d74764746dd8a), [2fb7acc](https://github.com/amarz45/find-anything/commit/2fb7acc81b9f76edbc0ef93cc8f7de0c70cdc417), [800a3ce](https://github.com/amarz45/find-anything/commit/800a3ce8122b09264dce038b4765972be4cb0cc7), [c19cf29](https://github.com/amarz45/find-anything/commit/c19cf29767f08817575c510102509ca6e7e32283), [3a2e6e5](https://github.com/amarz45/find-anything/commit/3a2e6e51d26fe5f1342d667c035fae6a634456c2), [add80fd](https://github.com/amarz45/find-anything/commit/add80fd004c538008225df69074c4654a84dc6d8), [5f41301](https://github.com/amarz45/find-anything/commit/5f41301bc2860ed5a42bc3c5c2df511e49a2b27c), [158e6d0](https://github.com/amarz45/find-anything/commit/158e6d0f181b801d333c6ef61dedb86ad5136bb9), [ca5b70b](https://github.com/amarz45/find-anything/commit/ca5b70b7365eb19bd456166350b0e2d5f9e6e6bb), [aeaccb0](https://github.com/amarz45/find-anything/commit/aeaccb0b7c6bfac68b6037c889c09c6251b99e09), [94d474d](https://github.com/amarz45/find-anything/commit/94d474d162387bdf215203a9d324c0193caab83c), [d063b8c](https://github.com/amarz45/find-anything/commit/d063b8c30d819885d9b09f37c5435a558f9ef90b), [3047ef5](https://github.com/amarz45/find-anything/commit/3047ef5dd1a9d107dd073f9971ef677dfee58416), [11c209a](https://github.com/amarz45/find-anything/commit/11c209a4bf6fd22779a2e87b244ce75c35881c9c), [dca7718](https://github.com/amarz45/find-anything/commit/dca7718bd4b2e4240d5213cbf5bd0daac9fd7fce), [6a60eb1](https://github.com/amarz45/find-anything/commit/6a60eb1d2b3d2d31b6ae409b98e96972a8f914d2), [f82ee54](https://github.com/amarz45/find-anything/commit/f82ee542d9c20d6fc2dc90e776f2a48303957bde), [0d3f499](https://github.com/amarz45/find-anything/commit/0d3f499a56770edfa5df6124c037518f717ece41), [a7ca3f5](https://github.com/amarz45/find-anything/commit/a7ca3f5c0dff048881b209f2fa61a623cf06deb5), [1f06018](https://github.com/amarz45/find-anything/commit/1f0601896d179f641f6b4a865b5f20b2b3bedc5e), [4bdc432](https://github.com/amarz45/find-anything/commit/4bdc43278ffe073bc6f986e8cb2b77ccbae78fd7), [3ea0d20](https://github.com/amarz45/find-anything/commit/3ea0d20c068b9a1699acc403b53d120907fe2846), [0d6d5c6](https://github.com/amarz45/find-anything/commit/0d6d5c615d08ae8d1a9f32f4acc64696389d7fca), [47a9433](https://github.com/amarz45/find-anything/commit/47a9433ebbbb6576bec33aa93e2443cec37c6327) | 24 |

Periods 1 to 3 are the reporting periods of the instructions, and each has at least five code commits. The fourth row covers the extension of the deadline to November 15.

### 6.2 Checks performed

- **The build.** `./gradlew build` compiles the code, checks its formatting and runs all 324 automated tests in 44 test classes; all pass. The tests replace the outside world with fakes passed in through constructors: a scripted `LlmClient` in place of a model, a fake `EmbeddingModel`, and a temporary data folder. The GUI's tests run the real JavaFX toolkit without a display.
- **Continuous integration.** GitHub Actions runs the same build on Linux and Windows for every pull request and every push to `master`. The submitted code passes on both.
- **A test corpus with known answers.** `testdata/corpus/` holds 25 fictional files, with a fact sheet of what each contains, and includes edge cases: an empty file, a password-protected PDF, an image, a scanned receipt, an SSH key placeholder, an Office lock file, two leases with different dates, two notes that disagree, and a file with an instruction planted in it. An integration test indexes the corpus and checks searches and previews.
- **The agent against real models.** The corpus questions were asked with the cloud model and with the local model. "When does my lease end?" is answered August 31, 2026, cited to page 2 of the lease and verified, with the planted instruction ignored; the dentist question returns both conflicting dates with their sources; "What is the total approved budget?" is answered $48,500 by the local model.
- **Manual testing on real documents.** I used each stage in the GUI and the CLI on my own documents folder, which found two of the defects in Section 6.3.
- **The demonstration.** The demonstration's commands were run on a copy of the corpus before recording, and the recording shows each implemented feature running in the submitted application.

### 6.3 Important defects found and corrected

| Defect | Found by | Cause | Fix |
|---|---|---|---|
| Every rescan indexed every unchanged file again (October 25). | A manual check of the CLI: each command showed files pending although nothing had changed. | The file system's modification time has nanosecond precision and the stored one milliseconds, so they were never equal. | `Indexer` truncates modification times to milliseconds when storing and comparing; a test guards it. |
| `index rebuild` emptied the index instead of rebuilding it (October 25). | Two automated tests, right after the fix above, which had masked it. | The worker checked the rebuild flag only at the top of its loop, so it rescanned against the still-full index and only then dropped every entry. | The worker checks the flag right after taking a path, so the drop always happens before the rescan. |
| One test timed out on the Windows CI runner (October 26). | CI on a pull request. | The first PDF with a non-embedded font makes PDFBox scan every installed font, which takes a minute on the runner; with the cache in the home folder, an unwritable home would repeat the scan. | `IsolatedTextExtractor` keeps the font cache in the application's data folder ([637839d](https://github.com/amarz45/find-anything/commit/637839dcab5862074db335594af0cd6de3ebbd49)). |
| Status listeners never heard "up to date" after a single change (November 7). | The first automated test that drives the GUI's status observer: after removing a folder, the results table never emptied. | A request made while the worker was idle was counted as finished only on the next pass, and an idle pass notified nobody. | `Indexer` also notifies on an idle pass when the last status it sent had work pending; a test guards it. |
| A follow-up forgot the first exchange, and reopening the conversation lost the first request (November 8). | My manual test of the GUI on my own documents. | The first exchange held about 78,000 characters of search results, twice the 40,000-character history limit, and trimming drops whole exchanges. | Earlier exchanges keep only their request and answer (`Conversation.dropToolMessages()`), and tool results are never saved ([fbb0b7e](https://github.com/amarz45/find-anything/commit/fbb0b7e044409cb1df8746ab8bad0b575cb7b494)); three tests guard it. |
| The agent's tools searched and read files in paused folders (November 5). | My review of the fallback code. | Only the instant-search fallback skipped paused folders. | The check moved into `SearchEngine` and `ReadExcerptTool` ([6eda059](https://github.com/amarz45/find-anything/commit/6eda05959a0ef27462a32fbce530058ed2e54c84)). |
| The preview kept a file's old text after the file was indexed again (November 14). | My manual check of live watching in the GUI. | The table keeps the selected file selected without announcing it, so nothing asked for the preview again. | `GuiController.showResults()` reloads the preview when the selected file's index entry has changed ([4bdc432](https://github.com/amarz45/find-anything/commit/4bdc43278ffe073bc6f986e8cb2b77ccbae78fd7)); a test guards it. |

### 6.4 Remaining limitations

The limitations of the submitted application are listed in Section 1.4. One further point concerns verification: Stage 2's tests are the basic verification the instructions ask for. The full deterministic testing and the KUMA agent validation are part of Stage 3, and the agent's tool-call log and `findany ask --json` are already in place for them.

## 7. Demonstration index

The demonstration is one recording of 27 minutes, <https://youtu.be/mcVUT82xOac>, with a labelled chapter for each implemented feature.

| Feature | Starts at | Direct link |
|---|---|---|
| F01 Search folder management | 0:00 | <https://youtu.be/mcVUT82xOac?t=0> |
| F02 Text extraction across formats | 3:05 | <https://youtu.be/mcVUT82xOac?t=185> |
| F03 Incremental index updates | 4:15 | <https://youtu.be/mcVUT82xOac?t=255> |
| F04 Filters | 6:51 | <https://youtu.be/mcVUT82xOac?t=411> |
| F05 Semantic search | 9:07 | <https://youtu.be/mcVUT82xOac?t=547> |
| F06 Instant hybrid search | 10:50 | <https://youtu.be/mcVUT82xOac?t=650> |
| F07 Natural-language search | 12:09 | <https://youtu.be/mcVUT82xOac?t=729> |
| F08 Question answering across files | 13:43 | <https://youtu.be/mcVUT82xOac?t=823> |
| F09 Citation verification | 15:53 | <https://youtu.be/mcVUT82xOac?t=953> |
| F10 Document comparison | Not implemented | No video |
| F11 Result preview with highlighting | 17:08 | <https://youtu.be/mcVUT82xOac?t=1028> |
| F12 Conversation follow-ups | 18:45 | <https://youtu.be/mcVUT82xOac?t=1125> |
| F13 Model backend selection and privacy log | 20:32 | <https://youtu.be/mcVUT82xOac?t=1232> |
| F14 Sensitive-data protection | 23:37 | <https://youtu.be/mcVUT82xOac?t=1417> |
