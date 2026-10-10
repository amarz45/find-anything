# Find Anything

Find Anything is a cross-platform desktop application (JavaFX GUI and a `findany` CLI) that indexes the files on your computer and lets you find files and get answers from them in natural language. An AI agent plans searches, reads the relevant excerpts, compares documents, and answers questions with citations that are checked against the files. The language model can be a cloud model or a local one, and sensitive content is withheld or redacted before anything reaches a cloud model.

This is the EECS3311 (Fall 2026) course project.

## Stage 1: design

**[Read the Stage 1 design report](docs/stage1-report.md)**

The report covers the project overview, feature specifications, UML class, use-case, and sequence diagrams, design patterns, use-case descriptions, feature-to-design traceability, and feature implementation explanations. A PDF copy is in [`docs/stage1-report.pdf`](docs/stage1-report.pdf).

## Repository layout

```
README.md                this file
docs/
  stage1-report.md       the Stage 1 design report (the main version)
  stage1-report.pdf      PDF copy of the report, built from the Markdown file
  pdf.yaml               settings for building the PDF
  table-widths.lua       small filter that sizes table columns in the PDF
  diagrams/              UMLet sources (.uxf) and images (.png) of every diagram
```

## Building the report as a PDF

The PDF is built from `docs/stage1-report.md` with [Pandoc](https://pandoc.org) and the [Typst](https://typst.app) PDF engine. Both are single programs with no other dependencies. Typst includes its own fonts, so the PDF looks the same on every computer.

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

The build has been tested with Pandoc 3.1.3 and 3.6.4, and Typst 0.13.1.

### 2. Build

From the `docs` folder, run:

```
cd docs
pandoc -d pdf.yaml
```

This writes `docs/stage1-report.pdf`. The command is the same in PowerShell, Command Prompt, and Unix shells. It has to run from `docs` so that the diagram paths in the report resolve.

All the settings, such as paper size, margins, and font size, are in `docs/pdf.yaml`. Rebuild and commit the PDF whenever the report or a diagram changes.

The diagrams keep their full resolution in the PDF, so zoom in to read the large ones. On GitHub, clicking a diagram in the report opens it at full size.

## Editing the diagrams

Every diagram was made in [UMLet](https://www.umlet.com). Open a `.uxf` file in `docs/diagrams/` with UMLet, edit it, and then export it to PNG next to the source with the same name: File > Export as… > PNG. The report shows the PNG files, so a diagram change appears in the report (and, after a rebuild, in the PDF) without editing the Markdown. See [`docs/diagrams/README.md`](docs/diagrams/README.md) for the notation and editing tips.
