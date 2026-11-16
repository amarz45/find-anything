# Stage 2 diagrams

The Stage 1 diagrams, corrected to describe the system as it is implemented. Each file has the same name and sub-folder as its Stage 1 original in [`docs/diagrams/`](../), which is unchanged, so the two versions can be opened side by side. Every title ends with ", as implemented".

The reason for each change is in section 4 of the [Stage 2 report](../../stage2-report.md). The notation and the editing tips are in the [Stage 1 README](../README.md).

Members were added to the class diagrams only where section 4 of the Stage 2 report names them, a sequence diagram calls them, or a class's part in the design can't be understood without them. Constructors, getters, constants and private helpers are not drawn.

## What changed in each diagram

| Diagram | Changes from Stage 1 |
|---|---|
| `use-case-diagram` | UC06 Compare documents is removed with its links (F10 is not implemented). The other use cases keep their numbers. |
| `cd00-overview` | Nine new dependencies: `ui` on `config`, `embedding`, `llm`, `privacy`, `search` and `storage`; `core` on `embedding`; `search` on `config`; `agent` on `privacy`. The arrows are the packages' imports in the code. The layout is reworked to fit them. |
| `cd01-ui-core` | No comparison (`compare()`, `onCompareRequested()`, `showComparison()`). No `onAddExclusion()` or `showGlobalRules()`. Added: `onRemoveFolder()`, `showConversations()`, `showFilterError()`, `showPastExchange()`, `CliApp.createService()`, and the facade's `embeddingModel`, `loadEmbeddingModel()`, `setSemanticSearchEnabled()`, `isSemanticSearchEnabled()` and `resolveModelSettings()`. |
| `cd02-storage` | No `ContentStore` and no `isReferenced()`. `SearchIndex` has the vector methods for chunks and for file names. `getFile()` returns `Optional`. `LocalEmbeddingModel` has `nativeLibraryFolder`. |
| `cd03-indexing` | `Indexer` has no `contentStore`. `FileWatcher` keeps one library watcher per folder and has `onChange()` and `onOverflow()`. The notes describe the retry limit, the two embedding steps and the quiet period. |
| `cd04-search` | No `DocumentComparator` or `DiffResult`. `SearchEngine` has `indexConfig`, and its note gives the similarity floor. |
| `cd05-agent` | No `CompareTool`, `summarizeDiff()` or `buildDiffSummaryRequest()`. `ShownPassages` is new, and `Tool.execute()` and `ToolRegistry.execute()` take it. Added: `buildSearchFirstRequest()`, `dropToolMessages()`, and the links from `AgentController` to `SearchIndex` and `FolderPolicy`, from `ReadExcerptTool` to `IndexConfig`, and from `ConversationStore` to `SettingsStore`. |
| `cd06-llm` | `LoggingLlmClient` has `modelSettings` and `clock`. The notes describe OpenRouter, the connection test and the withholding of whole messages. |
| `cd07-config-privacy` | `EnvFile` is new. `findFolder()` returns `Optional`; `isInActiveFolder()` is new. |
| `cd-full` | The union of `cd01` to `cd07`: the same class boxes, notes and relations. |
| `sd01-index-file` | No `ContentStore` lifeline. A locked file is retried three times, then marked failed. Phase 2 runs after the commit, on the index alone, and embeds file names after the chunks. |
| `sd02-instant-search` | The similarity floor, the engine turning semantic search off when the model fails, and the warning shown to the user. |
| `sd03-ask-agent-loop` | The client is created and the agent runs on the background thread. Added: `dropToolMessages()`, `ShownPassages`, the search-first step, and what is saved. |
| `sd04-send-request` | The provider is OpenRouter. |
| `sd06-preview` | `getFile()` is called in every case, before the branches. |
| `sd07-manage-folders` | Exclusion rules are added from the CLI (`findany exclude add`). Built-in exclusions can't be overridden. |
| `sd08-configure-backend` | No API key is entered: `resolveModelSettings()` supplies it from `.env`. The connection test has three outcomes. |
| `sm01-index-lifecycle` | New state Stopped. Idle prunes nothing. Embedding covers file names too. |
| `ad01-threads` | No comparison and no pruning. One watcher thread per search folder, started only by the GUI. |

There is no Stage 2 version of `sd05-compare`, because F10 Document comparison is not implemented.

## Exporting the PNGs

Each `.png` here was exported from its `.uxf` with UMLet 15.1's command line:

```
java -jar umlet.jar -action=convert -format=png -filename=<absolute path to the .uxf>
```

It writes the `.png` beside the `.uxf`. The typeface of these exports differs from that of the Stage 1 PNGs.
