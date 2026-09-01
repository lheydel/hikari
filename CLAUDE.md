# CLAUDE.md — working on this plugin

A JetBrains plugin, id `dev.kosmio.mcp-powerup`, that contributes tools to IntelliJ's **bundled** MCP
server: changelist tools, and code-intelligence tools built on the IDE's indexes. See `README.md` for what it does, the tool surface, and install/connect
steps — this file is for editing the code. Most of what's below is empirical: it was learned by hitting it, and is **not** visible from
reading the source.

## Safety boundary (absolute)

**Read and organize only — never mutate file contents or VCS state.** **Never** add commit / push / stash /
revert / shelve, or any tool that edits file contents — not even a private helper that could be wired up
later. The *one* mutation the plugin makes is scheduling new (unversioned) files for addition (add-to-VCS)
so they can be grouped. The user reviews and commits in IntelliJ.

The charter is per-plugin, not per-tool-family: it binds every tool this plugin ever contributes, whatever
the concern. The plugin name does not mention "changelist", so this paragraph is the signal a session
reading the plugin name gets.

## Build & test

- Java 21; Gradle wrapper is 9.4.1 (`./gradlew`).
- `./gradlew buildPlugin verifyPlugin --no-build-cache` — keep `--no-build-cache`: when the user's IDE is
  building the same `build/` dir, the build cache fails to pack (`Could not get file mode …`). Not a code error.
- **Always run `verifyPlugin`** — it binary-checks the plugin against the target IDE build(s) and catches API
  drift that compilation alone misses.
- Artifact: `build/distributions/intellij-mcp-powerup-<version>.zip`.
- **Every code change needs a reinstall to test live**: Settings → Plugins → ⚙ → Install Plugin from Disk →
  the zip → **restart**. There is no hot reload. `./gradlew runIde` gives an isolated sandbox IDE instead.
- **Exercise a tool end-to-end without installing: `./gradlew runIde --args="<project path>"`, and speak MCP to
  the sandbox.** Put
  `<application><component name="McpServerSettings"><option name="enableMcpServer" value="true"/></component></application>`
  in `.intellijPlatform/sandbox/<name>/<build>/config/options/mcpServer.xml` and the sandbox IDE serves this
  plugin's tools on a port of its own — 64343 while the real IDE holds 64342; `lsof -nP -iTCP -sTCP:LISTEN -a -p <pid>`
  names it. The transport is SSE: `GET /sse` answers `event: endpoint` with a `?sessionId=…` path (**strip the
  trailing CR** or curl rejects the URL as malformed), then POST `initialize`, `notifications/initialized` and
  `tools/call` to that path; replies arrive on the open SSE stream, not in the POST response. Registration
  failures are silent — a broken toolset just never appears in `tools/list` — so this is what catches them, and
  `.intellijPlatform/sandbox/<name>/<build>/log/idea.log` carries the reason.

## Toolchain — pinned for hard reasons (don't "upgrade" blindly)

- `intellijIdea("2026.1.3")`, **not** `create("IC", …)` — IC/IU stopped being separate Maven artifacts at 2025.3.
- IntelliJ Platform Gradle Plugin **2.16+** requires **Gradle 9+** (hence the 9.4.1 wrapper).
- Kotlin **2.3.21** — the 2026.1 platform ships kotlin-stdlib metadata 2.3.0; an older compiler can't read it
  and you get a cascade of bogus "unresolved reference" errors on platform classes.
- `sinceBuild = "261"` (2026.1+): the `project` coroutine accessor resolves to `McpCallInfoKt`, which doesn't
  exist pre-2026.1.
- Dependencies that aren't obvious: `bundledPlugin("com.intellij.mcpServer")`;
  `bundledModule("intellij.platform.vcs.impl")` (for `ChangeListManagerEx`, the patch builder, the
  line-status tracker, `ChangeListManagerImpl.editComment`); `bundledModule("intellij.platform.vcs.impl.shared")`
  (for `LocalRange`); `bundledPlugin("org.jetbrains.kotlin")` (for `toLightMethods`) and
  `bundledPlugin("com.intellij.java")` (for `PsiMethod.findSuperMethods`), used only by the Kotlin
  override hop. `kotlinx-serialization-json` is `compileOnly` — the platform ships its own; bundling clashes.
- **`verifyPlugin` reads the root descriptor only, never an optional `<depends config-file=…>` one.** So the
  Java dependency and the `supportsKotlinPluginMode` declaration that `mcp-powerup-kotlin.xml` carries are
  invisible to it, and it reports the plugin as using Java classes without declaring Java. Moving those to
  `plugin.xml` would make the whole plugin, changelist tools included, refuse to load in an IDE without Java,
  so the problem is listed in `verifier-ignored-problems.txt` (wired via `pluginVerification.ignoredProblemsFile`).
  That file reaches problems only — the matching *warning* stands on every run and does not fail the build.
- The plugin id must **not** contain the word "intellij" (the verifier rejects it) — hence
  `dev.kosmio.mcp-powerup`. The repository name and the distribution archive name may.
- **`<supportsKotlinPluginMode supportsK2="true"/>` goes in `plugin.xml`, not in the optional Kotlin config file
  it gates.** The loader reads `org.jetbrains.kotlin.supportsKotlinPluginMode` off the ROOT descriptor
  (`KotlinK2ModeCompatibilityCheckerKt.getSupportKotlinPluginModeEPs`) to decide whether to load that config file
  at all. Declared inside the file, the file is skipped in K2 mode behind a WARN — `… was not loaded` — and the
  override hop then reports itself unavailable with nothing to say why.

## The MCP tool API (2026.1)

- A toolset class implements the marker `com.intellij.mcpserver.McpToolset`; each tool is a `suspend fun`
  annotated `@McpTool` + `@McpDescription`. Annotate **every parameter** too — those descriptions become the JSON schema.
- Tool name = method name. Return an `@Serializable` data class (in `Models.kt`). Errors: `mcpFail("…")` (returns Nothing).
- **A return type must not be recursive.** The framework post-processes the generated JSON schema by walking
  `properties`/`items` in lockstep with the `SerialDescriptor`
  (`com.intellij.mcpserver.impl.util.Schema_utilKt.removeRequiredForDefaultValues`), and a self-referential
  `@Serializable` class derails that walk with `IllegalArgumentException: Element class
  kotlinx.serialization.json.JsonObject is not a JsonPrimitive`. The **whole toolset** is then dropped —
  `ReflectionToolsProvider` logs `Cannot load tools for …` and its tools never reach `tools/list`, with nothing
  visible to a caller. Flatten instead: `call_tree`'s `tree` is a flat breadth-first list whose nodes carry the
  index of the node they call.
- Project: `currentCoroutineContext().project` (import `com.intellij.mcpserver.project`).
- Register each toolset with its own `<mcpToolset>` in `plugin.xml`.
- The framework auto-injects a `projectPath` parameter into every tool — that's the multi-project/instance
  routing mechanism; don't declare it yourself.

## Threading — the most-bitten area

- Reads (`list_changelists`, `get_changelist_diff`): wrap in `readAction { }`.
- Whole-changelist mutations (move / create / delete): `edtWriteAction { }`. Re-fetch `Change` objects **inside**
  the action; never cache one across a suspension.
- **Scheduling unversioned files for addition** must run OFF the write lock AND off the EDT. Use
  `ScheduleForAdditionAction.Manager.addUnversionedFilesToVcsInSync(project, list, files, Consumer{})` inside
  `withContext(Dispatchers.IO)`. The public `ChangeListManagerImpl.addUnversionedFiles` uses a **modal** task
  that silently **no-ops** under a write lock — do not use it.
- **Partial (hunk) moves**: get the file's `PartialLocalLineStatusTracker` from `LineStatusTrackerManager`
  (request it + poll until `getRanges()` is populated — tracker init is async), then call
  `moveToChangelist(Range, list)` per matched hunk. Do **not** use `moveToChangelist(BitSet, …)` — that BitSet is
  **line numbers**, not range indices, so it silently moves nothing.
- **Refresh** (`refresh_changelists`): `VfsUtil.markDirtyAndRefresh(false, true, true, baseDir)` +
  `VcsDirtyScopeManager.getInstance(project).markEverythingDirty()` + `ChangeListManagerEx.waitForUpdate()`, all on
  `Dispatchers.IO`. It returns a bare confirmation, not counts: the unversioned-files list settles a beat after
  `waitForUpdate()`, so any count read in the same call is racy — callers read `list_changelists` afterward.
- **Analysis reads: `smartReadAction(project) { }`, one small unit of work per call.** Not the plain
  `readAction` the changelist tools use — reference search under `DumbService` returns nothing or throws
  during the reindex a `refresh_changelists` triggers, and an empty result is indistinguishable from
  "no callers". Each is a `com.intellij.openapi.application` top-level function, compiled into
  `CoroutinesKt` — `ActionsKt` in the same package holds only the blocking `runReadAction` family.
  - The platform already retries: `smartReadAction` is cancelled and **restarted** when a write action
    arrives, which is what `ReadAction$CannotReadException` reports. So the retry needs no code — but it
    restarts the *whole lambda*, so a traversal wrapped in one read action can livelock against a user who
    is typing. Wrap one node's search, not the traversal.
  - `smartReadActionBlocking` is the non-cancellable variant: it makes the writer wait instead. Right for a
    single short read, wrong for a traversal — it would stall the IDE's writes for the duration.
  - **PSI does not survive the boundary.** A cancelling write can invalidate any element held across read
    actions, so the frontier between units is `SmartPointerManager.createPointer(element)` (deref with
    `SmartPsiElementPointer.getElement()`), or re-resolved from `{file, symbol}` coordinates. This is the
    same rule as "never cache a `Change` across a suspension", for the same reason.

## Behavioral gotchas (verified live, not inferable from code)

- **Resolve requested paths against the change set, not the VFS.** Deleted and moved files have no live
  `VirtualFile`, so `findFileByPath` returns null and a naive resolver wrongly skips them. Index
  `ChangeListManager`'s changes by project-relative path; only fall back to a VFS + `isUnversioned` check for new files.
- **`LocalChangeList.getChanges()` yields one `ChangeListChange` per holding list**, and
  `ChangeListWorker.moveChangesTo` relocates only the hunks of the list that entry names. That is the whole
  mechanism behind the `from` parameter: pass the entry whose list is `from` and just its hunks move. It is
  also the trap — indexing the changes by path with last-one-wins hands the platform an entry already in the
  target, which moves nothing and still looks like it succeeded. Verify a move by checking the source no
  longer holds the path, never by re-reading the change you passed.
- **The whole-file tools refuse a file split across changelists rather than consolidating it.** Passing
  every entry to `moveChangesTo` would consolidate it, but the hunks in the other changelists usually
  belong to a parallel session. Callers scope with `from` or go hunk-level with `move_lines_to_changelist`.
- **Tool results are re-read on every later request, so output size is a recurring cost.**
  Results carry only what the caller cannot already derive: `list_changelists` reports a comment's first
  line plus its length (the full text is one `get_changelist_comment` away), the move tools report
  `movedCount` rather than echoing the paths that were passed in, and `set_changelist_comment` reports
  whether the write stuck. `addedFiles`, `skippedFiles` and the diff stay in full — those are the
  surprises. `SESSION_TOKEN_COST.md` has the measurements.
- **`set_changelist_comment` pre-fills the commit dialog only for a NAMED changelist.** The default ("Changes")
  ignores its description — the dialog shows the last commit message instead. Always group into a named changelist.
- **Agent file edits bypass IntelliJ's VFS.** Anything written to disk outside the IDE is invisible until a
  re-scan, so the workflow is `refresh_changelists` → then read/move.
- `editComment` is on the concrete `ChangeListManagerImpl` (`getInstanceImpl`), not the `ChangeListManager`/`…Ex`
  interfaces.

## Traversal budgets (calibrated)

Measured with `run_inspection_kts` against `~/Bazar/engineering/skeleton-kotlin-react`, indexed and warm
except where noted.

**The budget binds on output size, not on wall-clock.** A slow call costs the caller once; a large result is
re-read on every later request in the session, which is the `carried` cost `SESSION_TOKEN_COST.md` measures.
Serialising the same traversal both ways, at `chars / 4` per that document's convention:

| Seed | Depth | Nodes | `tree` | `grouped` |
|---|---:|---:|---:|---:|
| `TransactionSpi.execute` | 1 | 64 | 16 775 c ≈ 4 194 t | 5 251 c ≈ **1 313 t** |
| `TransactionSpi.execute` | 2 | 117 | 30 119 c ≈ 7 530 t | 7 601 c ≈ **1 900 t** |
| `Button` | 1 | 43 | 7 725 c ≈ 1 931 t | 2 420 c ≈ **605 t** |
| `Button` | 2 | 109 | 19 732 c ≈ 4 933 t | 5 523 c ≈ **1 381 t** |

`grouped` runs 3–4× cheaper and carries the same caller set — it drops per-node paths, line numbers and tree
structure, keeping module, file name, counts and declaration names. So `grouped` is the default and the tool
description steers callers to it; `tree` is opt-in, for when the file locations are the point. The file name
is what keeps `grouped` usable on the frontend, where a whole workspace is one IntelliJ module and grouping
by module alone says nothing.

- **`format: grouped | tree` is a caller argument, not a mode the tool picks.** At `grouped` sizes, output
  stops being the binding constraint at the depths that matter, so there is no width threshold at which the
  traversal refuses to expand.
- **Depth is the knob that bounds cost.** Warming does not rescue a wide traversal: `TransactionSpi.execute`
  at depth 5 costs 23 854 ms with every index hot and still leaves 59 nodes unexpanded. Only the seed's own
  search gets cheap warm (449 ms → 43 ms); every element found at depth pays its own first touch, so level 1
  stays near 156 ms per search. Cost tracks distinct elements searched.
- **Level 1 is cheap and always exhaustive** — 43 ms warm, 449 ms cold for the wide seed. Never cap it. It is
  also the hop grep cannot reach: `ProjectCreateFeatureImpl.invoke` has zero textual references to its caller
  and is reachable only through the port it overrides.
- **A long read action gets cancelled.** `CannotReadException` killed the depth-5 wide traversal repeatedly
  here; at tens of seconds, a write arriving mid-run is likely rather than rare. The platform retries this
  for free — the constraint it puts on the design is granularity and PSI lifetime, in § *Threading*.
- **A stopped run returns its frontier as re-seedable `{file, symbol}` pairs**, so a caller can continue
  deterministically instead of receiving a silently partial answer. The same coordinates are what the
  traversal re-resolves against after a cancelled unit, so resumability and cancellation-recovery are one
  mechanism rather than two.
- Seed distribution, for sizing: domain ports measure 1–3 first-level callers (`UserCommands.kt`, 13–366 ms
  each); `TransactionSpi`'s own methods measure 68 / 5 / 2 / 1; the `ui` package's components run 0–43 raw
  references, median around 10, with `Field` (162) and `Button` (171) above it.
- Counts are of **callable** nodes, after the enclosing-declaration filter, not of raw references. `Button` is
  171 raw references but 43 nodes.

`DefinitionsScopedSearch` costs about what `ReferencesSearch` costs (2 ms against 6 ms on the same warm
seed), so implementation lookup does not need its own cap.

Fanning searches across cores buys little — 10 257 ms against 12 448 ms sequential on eleven cores, with the
parallel pass run first so the warm advantage went to the sequential one. Measured inside an inspection's
read action, which may serialise the children, so treat it as a lower bound rather than a settled ceiling.

## Analysis tools — language-specific surfaces

- **`PsiNameIdentifierOwner` is the language-neutral enclosing-declaration filter, and it needs no
  per-language provider.** Nothing at platform level means "callable" — `TypeScriptFunctionImpl` carries only
  `PsiElement`, `PsiNamedElement`, `PsiNameIdentifierOwner`, `NavigationItem`, `NavigatablePsiElement`,
  `StubBasedPsiElement`, `PsiExternalReferenceHost` — but "callable" is not what the filter has to decide.
  Excluding files and markup is, and `PsiNameIdentifierOwner` does exactly that: a `PsiFile` is not one, and
  neither is a JSX literal. On the `Button` seed it yields `TypeScriptFunction`, `TypeScriptFunctionExpression`,
  `TypeScriptVariable`, `JSProperty`, `JSDefinitionExpression` and no file or JSX node; the language-specific
  `JSFunction` yields a strict subset of that, dropping variable- and property-bound components.
- **`PsiNamedElement` is not a usable substitute.** A `PsiFile` is a `PsiNamedElement`, so a first-named-parent
  filter reports files as callers — on the `Button` seed that is how a large share of its references resolve.
  JSX literals resolve the same way.
- **Reject a name that starts with `<`, on top of requiring a non-null `name`.** A synthetic element gets a
  placeholder name in angle brackets rather than no name: `KtFunctionLiteral.getName()` answers
  `<anonymous>`, so a null check alone lets every Kotlin lambda through. They are dead ends — nothing
  references a lambda — and they dominate the result where a codebase nests calls in lambdas: the
  `TransactionSpi.execute` seed at depth 2 carries 136 nodes with lambdas admitted and 117 without, and its
  test side drops from 26 nodes, mostly step-definition lambdas, to 12 real declarations. The `KtProperty` nodes, which
  are real call sites, survive either way. Skip the placeholder segments when building a qualified name too,
  so a property declared inside a lambda reads `UserDeleteFeatureImpl.invoke.deleted`.
- **`TestSourcesFilter.isTestSources` classifies nothing in this project.** It returns false for every node of
  the `TransactionSpi.execute` tree, including the step definitions, because they live in a Gradle *main*
  source set at `platform/server/test/src/main/kotlin`. Test detection has to key on path or package, and it
  is per-language: TypeScript tests sit beside their sources as `*.test.tsx`.

## Source layout

- `src/main/kotlin/dev/kosmio/mcppowerup/ProjectPaths.kt` — project-relative path helpers, shared by every toolset this plugin contributes.
- `src/main/kotlin/dev/kosmio/mcppowerup/changelist/ChangelistToolset.kt` — the changelist `McpToolset`, its tools, private helpers.
- `src/main/kotlin/dev/kosmio/mcppowerup/changelist/Models.kt` — `@Serializable` result DTOs for the changelist tools.
- `src/main/kotlin/dev/kosmio/mcppowerup/analysis/AnalysisToolset.kt` — the analysis `McpToolset`.
- `src/main/kotlin/dev/kosmio/mcppowerup/analysis/CallTreeTraversal.kt` — the traversal engine behind `call_tree`.
- `src/main/kotlin/dev/kosmio/mcppowerup/analysis/Models.kt` — `@Serializable` result DTOs for the analysis tools.
- `src/main/kotlin/dev/kosmio/mcppowerup/analysis/SuperDeclarationProvider.kt` — the override hop's extension point.
- `src/main/kotlin/dev/kosmio/mcppowerup/analysis/kt/KotlinSuperDeclarationProvider.kt` — its Kotlin implementation.
- `src/main/resources/META-INF/plugin.xml` — the `<mcpToolset>` registrations, the extension point, the optional `<depends>`.
- `src/main/resources/META-INF/mcp-powerup-kotlin.xml` — loaded only when the Kotlin plugin is present.

## Inspecting platform APIs

When unsure of a signature, decompile against the resolved SDK rather than guessing:
`javap -p -cp "<idea>/lib/*.jar" <fqcn>`, where `<idea>` is under
`~/.gradle/caches/<gradle>/transforms/**/transformed/idea-<version>/`. For readable Java, the IDE's own
`mcp__idea__read_file` reads `…/lib/foo.jar!/pkg/Bar.class`.
