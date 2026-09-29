---
description: Washbase setup is complete — points to where the workflow now lives
---

Washbase's setup (Phase 1 pre-flight audit and Phase 2 scaffolding) is complete. Tell the user this, then point them to:

- **`CLAUDE.md` → Workflow**: the single source of truth for how work is planned and built (Linear gates, agents, "check Linear").
- **`.claude/agents/`**: the product-manager, product-owner, senior-dev, backend-dev, web-dev, mobile-dev and build-qa agents.
- **`/scaffold`** (user-level command in `~/.claude/commands/scaffold.md`): the maintained framework for setting up a *new* project.

The original framework prompt this project was scaffolded from is in git history (`git show ad9821c:.claude/commands/init.md`).

Do not re-run setup or scaffolding in this repository.
