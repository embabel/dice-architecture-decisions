# Design

The workbench pairs an ivory decision record with a dark proof graph. The graph shows sources, conditions, and rule outcomes in three columns. Chat and the node inspector open the same evidence. The approved ADR sits beside the decision title and can be inspected directly.

## Interaction

- Selecting a node highlights the paths immediately connected to it. The inspector shows the condition status, rule inputs, Prolog text, and original excerpts where available.
- The checkpoint controls replace the graph and start a fresh conversation. The replay drill switch marks its paths as hypothetical and leaves the approved ADR in view.
- A chat citation selects its node and moves focus to the inspector. Selecting **Ask about this** puts that node in focus for the next question.
- On narrow screens the chat and graph stack. All nodes and controls remain keyboard-accessible. Text and line styles carry status alongside color.
