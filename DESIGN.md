# Design

The dark canvas is the workbench's main surface. Four lanes show sources, conditions, rule outcomes, and recorded decisions. D3 draws the connections from the same graph the backend returns. Blue edges connect decision versions, mint marks proven conditions, and dashed amber and violet represent unmet and hypothetical conditions.

- Select a node to highlight its links and open the evidence inspector. Zoom buttons and the reset control complement canvas panning. Node controls and citations work with the keyboard.
- Checkpoint controls clear the conversation. The hypothetical replay input changes the rule evaluation for the selected checkpoint. The approved ADR remains available.
- The inspector shows the version history for recorded decisions. The editor uses the currently selected node as the initial evidence link. Restore adds a new node so the superseded version stays visible.
- Chat sits beside the canvas at desktop widths. Graph, inspector, and chat stack as the viewport narrows. Status text and edge styles carry meaning alongside color.
