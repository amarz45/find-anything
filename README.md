# Find Anything

Find Anything is a cross-platform desktop application (JavaFX GUI and a `findany` CLI) that indexes the files on your computer and lets you find files and get answers from them in natural language. An AI agent plans searches, reads the relevant excerpts, and answers questions with citations that are checked against the files. The language model can be a cloud model or a local one, and sensitive content is withheld or redacted before anything reaches a cloud model.

This is the EECS3311 (Fall 2026) course project.

## Stage 2: implementation

**[Read the Stage 2 implementation report](docs/stage2-report.md)** (PDF copy:
[`docs/stage2-report.pdf`](docs/stage2-report.pdf)).
**[Watch the demonstration video](https://youtu.be/mcVUT82xOac)**, which has a chapter for each
feature.

Thirteen of the fourteen designed features are implemented: indexing, search by keywords and by
meaning, filters, the agent (`ask`), the GUI and the privacy features. Text is extracted from PDF,
DOCX, PPTX, TXT and MD files; a file of another format is found by its name. While the GUI is
open it watches the search folders, so a changed file is indexed a couple of seconds later; the
command line picks changes up by a rescan when it runs. One part of the design is left out:
document comparison with a diff (F10). The agent can still be asked what differs between two files
and answers with citations from both.

### Prerequisites

- Git.
- Nothing else for building: the Gradle wrapper downloads Gradle, and Gradle downloads JDK 25 if it
  isn't installed. The first build also downloads the libraries, about 180 MB of which are the
  embedding model for search by meaning and what runs it.
- An [OpenRouter](https://openrouter.ai) API key for the cloud model, or [Ollama](https://ollama.com)
  for a local model.

### Local configuration (API keys)

Real keys go only in a local `.env` file, which Git ignores. Start from the committed example:

```
cp .env.example .env
```

Then open `.env` and replace the placeholders with your own key and model names. Never commit
`.env` or put a key anywhere else.

The app reads `.env` from its data folder (`FINDANY_HOME`) and from the directory it is started in,
and environment variables override both. The key is never written anywhere else: `findany model
set` saves only the backend, the model name and the base URL.

For the cloud backend, create the key at [openrouter.ai/keys](https://openrouter.ai/keys) and copy
a model ID that supports tool calling from [openrouter.ai/models](https://openrouter.ai/models).
Every request tells OpenRouter not to route to providers that train on prompts; prompt logging is
an account setting, so turn it off in your OpenRouter account's privacy settings. For the
local backend, install [Ollama](https://ollama.com), run `ollama pull <model>` and leave
`OLLAMA_BASE_URL` at its default unless Ollama runs on another machine.

### Build and run

```
./gradlew build                      # compile, check formatting, run tests
./gradlew run --args="--help"        # run the app with findany arguments
./gradlew run --args="gui"           # open the window
./gradlew installDist                # make the launcher: build/install/findany/bin/findany
```

On Windows, use `gradlew.bat` in Command Prompt, or `.\gradlew` in PowerShell. `build` does not
refresh the launcher under `build/install`; run `installDist` again after changing the code.

### Running `findany` from a terminal

`installDist` makes a standalone launcher: `build/install/findany/bin/findany` (macOS and Linux)
and `build\install\findany\bin\findany.bat` (Windows). The examples below call it `findany`. To
type it that way, add an alias in bash or zsh, from the repository root:

```
alias findany="$PWD/build/install/findany/bin/findany"
```

or add `build/install/findany/bin` to your `PATH`. `findany --help` lists the commands, and
`findany --version` prints the version.

### A working example on the test files

The repository includes 25 fictional test files in `testdata/corpus/`. From the repository root,
with a cloud model set up in `.env` (see above):

```
export FINDANY_HOME="$PWD/build/demo-home"   # keep the demo apart from your own index
findany folders add testdata/corpus          # index the test files
findany model set cloud                      # test the OpenRouter model from .env, then use it
findany ask "When does my lease end?"
```

The answer is August 31, 2026, cited to page 2 of `testdata/corpus/housing/lease-2025-2026.pdf`.
It usually also says that it ignored the instruction planted in `notes/note-from-unknown-sender.md`. Each
source is marked `[✓]` when its quoted passage was found in the file and `[?]` when it wasn't.
On Windows PowerShell, set the data folder with `$env:FINDANY_HOME = "$PWD\build\demo-home"`.

### Asking the agent: `findany ask`

```
findany ask [--continue] [--json] [--verbose] <request>...
```

| Option | What it does |
|---|---|
| `<request>` | The task, in your own words. Quote it, so the shell passes characters such as `?` unchanged. |
| `--continue` | Continue the most recent conversation, so the request can build on its earlier answers. Without it, a new conversation starts. |
| `--json` | Print the result as JSON: the conversation's ID, the request, the answer, whether every citation was verified, each citation with its file, page, quote and status, the files found, any notices, the model, the agent's tool calls, and whether ordinary search stood in for an unavailable model. |
| `--verbose` | Also print each step of the agent (each search and excerpt it reads) on standard error. |

To run the agent on a task written in a file, put `@` before the file's name:

```
echo "When does my lease end?" > task.txt
findany ask @task.txt
findany ask --json @task.txt > result.json
```

In such a file, `'` and `"` mark quoted text, so an apostrophe is dropped ("What's" is read as
"What s"). For a task with apostrophes, let the shell read the file instead (bash and zsh):

```
findany ask "$(cat task.txt)"
```

If the model can't be reached, `ask` says so and shows ordinary search results instead.

### The window

`findany gui` opens the window: a Search/Ask switch and one input field, the search folders as
chips (add one with "Add folder…", or mark one "Local model only" from its menu), the results on
the left and, on the right, a file's preview with the matches highlighted or the conversation with
the agent. In Ask mode, Enter sends the request; the answer's citations are marked verified or
unverified and open the file at the quoted passage. "Settings…" chooses and tests the model
backend, turns redaction categories on or off, and shows the privacy log. The GUI tests run the
JavaFX toolkit without a display.

### All commands

```
findany folders add ~/Documents          # add a folder; it is indexed right away
findany search lease ends                # by keywords and by meaning; accents are ignored
findany search cv                        # finds resume.pdf: names are matched by meaning too
findany search cv --keyword-only         # the query's words only
findany search --type pdf --after 2026-01-01   # filters: type, date, size (--max-size), folder (--in)
findany show ~/Documents/lease.pdf --highlight "August 31"
findany index status --failed            # what is indexed, and which files couldn't be read
findany exclude add "*.tmp"              # keep files out (globally, or with --folder)
findany folders list
findany model set cloud                  # test the OpenRouter model from .env, then use it
findany model set local --model llama3.1 # or a model from Ollama
findany model test                       # check the current backend
findany ask "When does my lease end?"    # the agent searches, reads and answers with citations
findany ask --continue "And the rent?"   # a follow-up in the same conversation
findany privacy                          # what is redacted before a request reaches a cloud model
findany policy local-only ~/Documents/Taxes   # withhold a folder's text from cloud models
findany privacy-log --last 5             # what was sent to cloud models, newest first
```

Before a request reaches a cloud model, text from folders marked "local model only" is withheld,
and card numbers, SINs, private keys and API keys are replaced with placeholders. Other sensitive
text is not detected: exclude such a file, or mark its folder local model only. Every cloud request
is recorded in `privacy-log.jsonl` in the data folder. A local model gets the text as it is, since
nothing leaves the computer.

Search by meaning uses a small embedding model (bge-small-en-v1.5, for English) that runs inside
the app on the processor. It comes with the build, so nothing is downloaded when the app runs and
no file text leaves the computer for it. After a file is indexed, its passages and its name are
embedded in the background; until then it is found by keywords. A command shows the progress and
waits for it ("Embedding... 120 passages left"), and the window's status shows the same while you
keep searching. If the model can't be loaded on a computer, search goes on with keywords only and
says so.

Every command that uses the index first catches up on files that changed since the last run. If another Find Anything
process is updating the index, the command runs read-only and says so. The index and settings
live in `FINDANY_HOME` (default: the platform's app-data folder, for example
`~/.local/share/find-anything`); set that variable to try the app on test data without touching
your own index.

## Stage 1: design

**[Read the Stage 1 design report](docs/stage1-report.md)**

The report covers the project overview, feature specifications, UML class, use-case, and sequence diagrams, design patterns, use-case descriptions, feature-to-design traceability, and feature implementation explanations. A PDF copy is in [`docs/stage1-report.pdf`](docs/stage1-report.pdf).

## Repository layout

```
README.md                this file
AGENTS.md                instructions for AI coding agents working on this repository
build.gradle.kts         the Gradle build (versions in gradle/libs.versions.toml)
.env.example             template for the local, git-ignored .env file with API keys
src/                     application and test source code
testdata/                fictional sample files for testing and demos, and their fact sheet
docs/
  stage1-report.md       the Stage 1 design report (the main version)
  stage1-report.pdf      PDF copy of the report, built from the Markdown file
  stage2-report.md       the Stage 2 implementation report (the main version)
  stage2-report.pdf      PDF copy of the report, built from the Markdown file
  notes/handoff.md       the state of the work and what comes next, updated every session
  notes/                 working notes (Tika findings)
  pdf.yaml               settings for building the Stage 1 PDF
  stage2-pdf.yaml        settings for building the Stage 2 PDF
  table-widths.lua       small filter that sizes table columns in the PDFs
  diagrams/              UMLet sources (.uxf) and images (.png) of every Stage 1 diagram
  diagrams/stage2/       the same diagrams as implemented in Stage 2
```

## Building the reports as PDFs

Each PDF is built from its Markdown file (`docs/stage1-report.md`, `docs/stage2-report.md`) with [Pandoc](https://pandoc.org) and the [Typst](https://typst.app) PDF engine. Both are single programs with no other dependencies. Typst includes its own fonts, so the PDF looks the same on every computer.

### 1. Install Pandoc (3.1.3 or newer) and Typst

| System | Command |
|---|---|
| Windows | `winget install --id JohnMacFarlane.Pandoc` and `winget install --id Typst.Typst` |
| macOS (Homebrew) | `brew install pandoc typst` |
| Linux | Install `pandoc` with your package manager if it provides version 3.1.3 or newer; otherwise download it from the [Pandoc releases page](https://github.com/jgm/pandoc/releases). Download Typst from the [Typst releases page](https://github.com/typst/typst/releases), or install it with `cargo install --locked typst-cli`. |

Check that both are on your `PATH`:

```
pandoc --version
typst --version
```

The build has been tested with Pandoc 3.1.3 and 3.6.4 with Typst 0.13.1, and with Pandoc 3.7.0.2 with Typst 0.15.1.

### 2. Build

From the `docs` folder, run:

```
cd docs
pandoc -d pdf.yaml           # writes docs/stage1-report.pdf
pandoc -d stage2-pdf.yaml    # writes docs/stage2-report.pdf
```

The commands are the same in PowerShell, Command Prompt, and Unix shells. They have to run from `docs` so that the diagram paths in the reports resolve.

All the settings, such as paper size, margins, and font size, are in `docs/pdf.yaml` and `docs/stage2-pdf.yaml`. Rebuild and commit a PDF whenever its report or a diagram changes.

The diagrams keep their full resolution in the PDF, so zoom in to read the large ones. On GitHub, clicking a diagram in the report opens it at full size.

## Editing the diagrams

Every diagram was made in [UMLet](https://www.umlet.com). Open a `.uxf` file in `docs/diagrams/` with UMLet, edit it, and then export it to PNG next to the source with the same name: File > Export as… > PNG. The report shows the PNG files, so a diagram change appears in the report (and, after a rebuild, in the PDF) without editing the Markdown. See [`docs/diagrams/README.md`](docs/diagrams/README.md) for the notation and editing tips.
