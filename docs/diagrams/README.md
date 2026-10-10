# Diagrams

All diagrams are made in [UMLet](https://www.umlet.com). Each `.uxf` file is the editable source, and the `.png` file with the same name is its exported image, which the [Stage 1 report](../stage1-report.md) shows.

| Path | Diagrams |
|---|---|
| `use-case-diagram.uxf` | Use cases UC01–UC11, with the actors and the include and extend relationships |
| `class-diagrams/` | `cd00-overview` (the packages and their dependencies), `cd01`–`cd07` (one view per area), and `cd-full` (all 58 classes in one diagram) |
| `sequence-diagrams/` | SD01–SD08 |
| `activity-diagrams/` | AD01: how work moves between threads |
| `state-diagrams/` | SM01: the index lifecycle |

## Notation

- **Classes:**
  - Interfaces are marked «interface», and the names of interfaces and abstract classes are in italics.
  - Stereotypes such as «Facade» or «Command» above a name give the class's role in a design pattern.
  - Static members are underlined.
- **Classes from other packages:** in `cd01`–`cd07`, a grey class named `package.Class` belongs to another package and is drawn only to show the relationship. In `cd-full`, every class appears once, inside its own package.
- **Sequence diagrams:**
  - A filled arrowhead is a call, an open arrowhead on a solid line is an asynchronous message (work handed to another thread), and a dashed line is a return.
  - Fragments are frames with the guard in the top-left corner, and else branches are separated by dashed lines.
  - A thick line with a bold label marks which thread the following steps run on.

## Editing tips

- Relations are attached to element borders, so moving a class or a state drags its lines along.
- In sequence diagrams, messages are separate lines, so moving a lifeline doesn't move them. Select a whole column (the lifeline, its activation bars, and the message ends) and move it together.
- Some relation labels contain a line like `cpos=0,-20`. It moves the label (here, 20 pixels up) so it doesn't sit on the line. Delete it to put the label back in UMLet's default position.
- After editing, export the PNG again (File > Export as… > PNG) with the same name, so the report shows the change.
