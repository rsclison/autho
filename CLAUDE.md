<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the parts of the codebase it indexes.** The graph is faster, cheaper
(fewer tokens), and gives you structural context (callers, dependents,
test coverage) that file scanning cannot.

**Coverage limit: the graph does not parse Clojure.** It indexes the
admin UI (TypeScript/TSX), the Java client, Python and shell scripts —
not the server in `src/autho/*.clj` nor `test/autho/*.clj`. For the
Clojure code, a graph query returns nothing (not "no callers"): use the
Serena MCP tools instead (below), then Grep/Read.

## MCP Tools: Serena (Clojure code)

Serena (declared in `.mcp.json`) navigates the Clojure server through
clojure-lsp: `find_symbol` to locate a function, `find_referencing_symbols`
for its callers, `get_symbols_overview` for the content of a namespace.
Use it before Grep/Read for structural questions on `src/autho`. The first
call of a session waits for clojure-lsp to start (about a minute).

Local setup, once per machine: `uv tool install -p 3.13 serena-agent`, a
`.serena/project.yml` (git-ignored) with `languages: [clojure, typescript]`,
then `serena project index .`.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes` or `query_graph` instead of Grep
- **Understanding impact**: `get_impact_radius` instead of manually tracing imports
- **Code review**: `detect_changes` + `get_review_context` instead of reading entire files
- **Finding relationships**: `query_graph` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview` + `list_communities`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

### Key Tools

| Tool | Use when |
|------|----------|
| `detect_changes` | Reviewing code changes — gives risk-scored analysis |
| `get_review_context` | Need source snippets for review — token-efficient |
| `get_impact_radius` | Understanding blast radius of a change |
| `get_affected_flows` | Finding which execution paths are impacted |
| `query_graph` | Tracing callers, callees, imports, tests, dependencies |
| `semantic_search_nodes` | Finding functions/classes by name or keyword |
| `get_architecture_overview` | Understanding high-level codebase structure |
| `refactor_tool` | Planning renames, finding dead code |

### Workflow

1. The graph auto-updates on file changes (via hooks in
   `.claude/settings.json`). The hooks need the `code-review-graph` command
   on PATH: `uv tool install code-review-graph`.
2. Use `detect_changes` for code review.
3. Use `get_affected_flows` to understand impact.
4. Use `query_graph` pattern="tests_for" to check coverage.
