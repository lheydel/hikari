# hikari

A JetBrains IDE plugin that adds tools to the IDE's built-in MCP server, for an MCP client such as
Claude Code. It exposes IntelliJ's **local changelists** — read changelist state, draft commit messages
from a changelist's diff, and organize modified files into changelists for you to review and commit —
and the IDE's **code intelligence**: a reverse call tree that answers what transitively depends on a
declaration, following what it overrides so an implementation reaches its port's callers, and a file
outline that gives you a file's declarations with their line ranges so you can read just the part you
need.

> **Read and organize only, for anything a program can reach.** No MCP tool, endpoint or other
> programmatic interface of this plugin mutates file contents or VCS state — none commits, pushes,
> stashes, reverts, shelves, or edits a file. They read state and rearrange which changelist a change
> belongs to. Scheduling *new* files for addition (add-to-VCS) into a changelist — the git equivalent of
> marking a new file to be tracked — is the single exception, and you still review and commit manually.
> That boundary is absolute, and it does not widen with the tool surface. Only an action you trigger
> yourself in the IDE, or the setup that runs when you open a linked worktree, may change files or VCS
> state.

## Why

A common flow is: the agent implements → you review the diffs in IntelliJ (per changelist, with
granular per-line selection) → you commit in IntelliJ → you push. Gaps make that awkward:

- `git diff` / `git diff --cached` doesn't reflect IntelliJ's changelist grouping (IntelliJ does partial
  commits without staging via the index), so the agent can't see "the files I'm about to commit as one unit."
- You don't want to hand-sort implementation changes into changelists, and you don't want to copy/paste
  commit messages out of a chat.

This plugin closes these gaps while keeping commit & push firmly in your hands.

The IDE's own MCP tools cover reading, searching and editing, but none of them answers *what depends on
this*: the bundled server ships no find-usages, call hierarchy or inheritance tool. An agent that just
changed a function is left grepping for its name — which finds nothing at all for an implementation whose
only caller goes through the interface it implements. `call_tree` is that missing hop.

Reading a file to find your way around it is the other cost. `outline` gives you the declarations, their
signatures and the lines they span — no bodies — so the read that follows is a targeted one.

## Requirements

- **IntelliJ IDEA 2026.2 or newer** (build `262+`), or another JetBrains IDE on the 2026.2 platform. The
  MCP server is bundled in the IDE since 2025.2, and this plugin uses its `McpToolset` API. It will not
  load on older builds — `sinceBuild` is `262`.
- Works on IDEA Community and Ultimate (since 2025.3 they're a single distribution).

## Tools

### Changelists

| Tool | Arguments | Purpose |
|---|---|---|
| `list_changelists` | — | Read every local changelist with its files, plus the project's unversioned (new, not-yet-tracked) files. One `# name [flags] — first line of comment (Nc)` line per changelist, then one indented `<T> <project-relative path>` line per file, T being M/A/D/M for modified/added/deleted/moved. New files are listed under `# unversioned` with a `?`. Read a comment in full with `get_changelist_comment`. |
| `refresh_changelists` | — | Re-scan the working tree and recompute changelist state. **Call this after editing files outside the IDE** (e.g. via Claude's own tools), before `list_changelists` — IntelliJ doesn't see external edits until it refreshes. |
| `get_changelist_diff` | `name` | Unified diff for one changelist (capped at 200 KB), for drafting a commit message. |
| `create_changelist` | `name`, `comment?`, `files?`, `setActive?`, `from?` | Create a changelist and put files into it: modified files are moved **whole**; **unversioned (new) files are scheduled for addition** and named in the output. A headline counts what landed; each refused file gets a `SKIPPED` line with its reason. Non-matching / missing files are skipped; the call still succeeds. |
| `move_to_changelist` | `name`, `files`, `from?` | Put files into an **existing** changelist (modified → moved whole, new → scheduled for addition). `from` scopes the move to one changelist's changes — see [Parallel sessions](#parallel-sessions). A headline counts what landed; everything else gets a `SKIPPED` line with a reason (`split_across_changelists`, `not_in_source`, `move_incomplete`, …) and, where it matters, which changelists still hold it. Fails if the changelist is missing (never auto-creates) or read-only. |
| `move_lines_to_changelist` | `name`, `file`, `lineRanges` | Move only specific **hunks** of one file into an existing changelist, leaving its other changes in place. See [Parallel sessions](#parallel-sessions). |
| `get_changelist_comment` | `name` | Read one changelist's full comment. `list_changelists` only broadcasts each comment's first line, so fetch the whole text here when extending an existing message. |
| `set_changelist_comment` | `name`, `comment` | Set a changelist's comment, which IntelliJ uses as the **default commit message** — so the commit dialog is pre-filled. **Use a *named* changelist:** the default `Changes` ignores its comment (the dialog shows the last commit message instead). |
| `delete_changelist` | `name`, `moveContentsTo?` | Delete a changelist, relocating its files first (default target `"Default"`). Refuses to delete `Default`. |

### Code intelligence

| Tool | Arguments | Purpose |
|---|---|---|
| `call_tree` | `file`, `symbol`, `depth?` | Reverse call tree from one declaration: what calls it, transitively, in a single call. At every node it follows both the references to the declaration and the declaration it **overrides**, so an implementation reaches the callers of the port it implements. Project sources only. `depth` defaults to 2 (max 5). Indentation means "calls the line above"; marks are `*` never expanded, `^` reached because the seed overrides it, `(test)` test source. |
| `outline` | `file` | Every declaration in one file as `START-END signature`, indented under whatever it is nested in — never any body text. Read it instead of a whole file, then read just the range you want. Seed `call_tree` with the leading identifier of a line's signature, or with the `[symbol]` in brackets where one is given (which means the bare name would not resolve). |

Reading a `call_tree` result:

- **Results are plain text, not JSON.** Line 1 gives the seed, its caller count, the depth and the status
  (`complete`, `stopped_at_depth`, `stopped_unresolved`); line 2 says whether the override hop resolved.
- **A `*` on a symbol means it was never expanded**, so a stopped run is never mistaken for a total answer.
  Re-run deeper, or seed a fresh `call_tree` on any marked node, to continue.
- **Locations are compressed.** A `dirs:` table numbers each directory as `D<n>`, and a node's location is
  `D<n>/<file>:<line>` — or `D<n>.<ext>:<line>`, which means the file is the declaration's own outermost
  segment plus that extension, so `Foo.bar  D2.kt:9` is `D2/Foo.kt` line 9.
- **Zero callers is not dead code.** `@Scheduled` tasks, `@EventListener` handlers, `@Bean` endpoints and
  route files have no callers by construction, and static search cannot tell them from genuinely unused
  code. That reading only applies when **`superHop.available`** is true; when it is false, no override hop
  is registered for that language and an empty result says nothing either way.
- **Test-source callers are reported apart from production ones**, and they expand like any other node —
  they are a real answer to "what depends on this", they just must not read as production impact.
  Classification keys on path and file name, not on the source root's kind, which misses test code that
  lives in a Gradle *main* source set.
- **A `STALE:` line** appears when the IDE has not re-scanned the seed file since it changed on disk. Call
  `refresh_changelists` first when you have edited files outside the IDE: without it, the answer may be
  built from the file as the IDE last saw it. `outline` reports the same line, for the same reason.

`outline` builds on the IDE's own structure view, so it covers every language the IDE understands and each
gives its own idea of a signature. A trailing `... N more not listed` line means the output cap bound, and
reading the file directly is the way to get the rest.

They compose: outline a file, then pass an entry's `symbol` straight to `call_tree` to ask what
depends on that declaration.

Each family registers as its own `McpToolset`; they appear alongside the IDE's built-in MCP tools.

**Every tool answers in plain text, not JSON.** A tool result is re-read on every later request in a
session, so its size is a recurring cost rather than a one-off; text carries the same information at
roughly a third of the tokens. Each tool's description states the format its output uses.

## Intended workflow

1. The agent finishes implementing.
2. It calls `refresh_changelists` (its edits were written to disk outside the IDE), then `list_changelists`,
   and **leaves user-curated buckets alone** (anything read-only, or named like "do not commit", "local",
   "wip-keep").
3. It groups the new changes into logical **named** changelists with `create_changelist` /
   `move_to_changelist` — one per intended commit. (Not the default `Changes`: the commit dialog ignores
   the default changelist's description, so a comment set there won't pre-fill — see below.)
4. It drafts a message from `get_changelist_diff` and writes it with `set_changelist_comment`, so your
   commit dialog opens pre-filled — no copy/paste.
5. **You** review in IntelliJ, reshuffle freely, and commit per group. **You** push. The plugin never does.

### Parallel sessions

When several agent sessions share one working tree, a single file can hold changes from more than one
session, and its hunks end up spread over several changelists. Moving such a file whole would drag another
session's work along, so the whole-file tools refuse it: it comes back as a `SKIPPED` line reading
`split_across_changelists`, naming every changelist that holds a piece. Ways forward:

- **`from`** — `move_to_changelist(name, files, from = "Changes")` takes only the changes that changelist
  holds. The hunks sitting in other changelists stay exactly where they are. Use this when your work is
  cleanly separated by changelist and you just want to relabel your bucket. `from` filters the whole call
  uniformly: anything it does not hold is skipped as `not_in_source` — a file that lives entirely in
  another changelist, and a new/unversioned file, which no changelist holds (so a scoped call never
  schedules one for addition; do that in a separate unscoped call).
- **`move_lines_to_changelist`** — hunk-level precision when it is not. Read `get_changelist_diff` to find
  the line numbers of *your* hunks (the `+` side of each `@@` header) and pass those 1-based ranges. Only
  the hunks overlapping your ranges move; the rest stay put.

Each rides on IntelliJ's partial-changelist (per-hunk) tracking. `move_lines_to_changelist` is best-effort:
tracker initialization is asynchronous and hunk boundaries come from a slightly different diff engine than
the unified diff, so rare edge cases may move a neighbouring hunk. Its result reports exactly which hunks
moved and which requested ranges matched nothing.

## Build

```bash
./gradlew buildPlugin
```

Produces the installable archive at `build/distributions/hikari-<version>.zip`.

Other useful tasks:

```bash
./gradlew verifyPlugin   # plugin-structure + binary-compatibility check against the target IDE
./gradlew runIde         # launch a sandbox IDE with the plugin loaded (isolated from your real IDE)
```

## Install

If this plugin is installed under an earlier name — **MCP Powerup** (id `dev.kosmio.mcp-powerup`) or
**Changelist MCP** (id `dev.kosmio.changelist-mcp`) — uninstall it first and restart: a different id makes
the IDE treat it as a separate plugin, and with both installed, each registers the same MCP tool names and
the MCP server sees a collision at runtime that nothing reports.

Then, in your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the built `.zip`, and restart.

## Connect an MCP client

The plugin's tools are served by the IDE's built-in MCP server, so connecting is the same as for any
JetBrains MCP tool:

1. **Settings → Tools → MCP Server** → enable the server.
2. **Auto-configure Claude Code** from that page (the IDE detects the client and writes the correct SSE
   entry itself), or add it manually with the URL shown there:
   ```bash
   claude mcp add --transport sse intellij http://localhost:<port>/sse
   ```
3. Verify with `claude mcp list`, then `/mcp` in a session to see the changelist tools alongside the built-ins.

> When several projects/instances are open, MCP calls resolve the target by **project path** — pass the
> working directory as `projectPath` (the framework injects this argument into every tool).

## Limitations

- **Single VCS root.** Path resolution uses `project.basePath`; multi-root projects are out of scope.
- **No streaming.** Large diffs are truncated at 200 KB with a marker, and an `outline` that would
  grow past 24 KB of declaration text ends with a `... N more not listed` line.
- **Partial moves are best-effort** — see [Parallel sessions](#parallel-sessions).
- **The override hop is contributed per language, and Kotlin is the language contributed.** Elsewhere
  `call_tree` reports `superHop.available: false` and walks references only.
- **`outline` needs the IDE to contribute a structure view for the file's language**, which it does for
  every language it parses; for one where it does not, the tool says so rather than returning an empty
  outline.

## Development notes

The build targets a specific toolchain (each pinned for a concrete reason):

- IntelliJ Platform Gradle Plugin **2.16.0** → requires **Gradle 9+** (wrapper is 9.4.1).
- **Kotlin 2.3.21** — the 2026.2 platform ships kotlin-stdlib metadata 2.4.0, one minor version ahead,
  which is as far ahead as a compiler reads.
- **JVM 25**, the runtime 2026.2 ships with (`kotlin { jvmToolchain(25) }`).
- Platform dependency is `intellijIdea("2026.2")` (the `IC`/`IU` artifacts merged since 2025.3).
- Depends on the bundled `com.intellij.mcpServer` plugin and the bundled modules
  `intellij.platform.vcs.impl` (for `ChangeListManagerEx`, the patch builder, the line-status tracker) and
  `intellij.platform.vcs.impl.shared` (for `LocalRange`).
- Depends on the bundled `org.jetbrains.kotlin` plugin (for `toLightMethods`) and `com.intellij.java`
  (for `PsiMethod.findSuperMethods`), only for the Kotlin override hop. That hop is registered from
  an optional config file, so the plugin loads regardless of whether they're present — and because the
  plugin verifier reads only the root descriptor, the Java dependency declared in that optional file is
  listed in `verifier-ignored-problems.txt`.
- `kotlinx-serialization-json` is `compileOnly` — the platform ships its own copy; bundling ours would clash.

Source layout:

```
src/main/kotlin/dev/kosmio/hikari/
  ProjectPaths.kt                       # project-relative path helpers, shared by every toolset this plugin contributes
  changelist/
    ChangelistToolset.kt                # the McpToolset with all changelist tool methods
    Models.kt                           # @Serializable result/DTO types
  analysis/
    AnalysisToolset.kt                  # the McpToolset with call_tree and outline
    CallTreeTraversal.kt                # the traversal engine behind call_tree
    OutlineBuilder.kt                   # the structure-view walk behind outline
    DeclarationSymbols.kt               # the symbol grammar the tools name declarations with
    SourceFiles.kt                      # locating a file and reporting how fresh the IDE's copy of it is
    Models.kt                           # @Serializable result/DTO types
    SuperDeclarationProvider.kt         # the per-language override hop's extension point
    kt/
      KotlinSuperDeclarationProvider.kt # its Kotlin implementation
src/main/resources/META-INF/plugin.xml
src/main/resources/META-INF/hikari-kotlin.xml         # loaded only when the Kotlin plugin is present
```
