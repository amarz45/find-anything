---
name: log
description: Draft an entry for one of the project logs (AI collaboration, design change, or defect found). Use only when the user asks to log something.
argument-hint: "[collab|design|defect] [short note]"
---

Draft one log entry from this session. The user's arguments: $ARGUMENTS

1. Pick the log from the first argument, or from the session if none is given:
    - `collab` → `docs/logs/ai-collaboration.md`: an interaction worth showing in the report.
    - `design` → `docs/logs/design-changes.md`: a difference from the Stage 1 design.
    - `defect` → `docs/logs/test-failures.md`: a real bug found by a test or check.
2. Read that file and use its template exactly. Append the entry at the end of its "Entries"
   section (for design changes, under the right heading).
3. Fill in only what actually happened in this session. Use today's date in Toronto time.
    - For collaboration entries, the tool is "Claude Code" and the model is the one this session
      runs on. "AI contribution" describes what you generated, suggested or diagnosed, plainly,
      including mistakes the user caught.
    - Write "Human contribution" as a short factual draft of what the user decided, reviewed,
      corrected or rejected, and end it with "*(edit in your own words)*". Never invent their
      reasoning.
    - Link commits as `[short hash](https://github.com/amarz45/find-anything/commit/<full hash>)`
      when there is one.
4. Show the user the entry you added and the file it went into. Don't commit it.
