# CLAUDE.md — working on this plugin

A JetBrains plugin, id `dev.kosmio.hikari`, that contributes tools to IntelliJ's **bundled** MCP
server: changelist tools, and code-intelligence tools built on the IDE's indexes. See `README.md` for what it does, the tool surface, and install/connect
steps — this file is for editing the code. Most of what's below is empirical: it was learned by hitting it, and is **not** visible from
reading the source.

## Safety boundary (absolute)

**Every surface a program can reach reads and organizes only — it never mutates file contents or VCS
state.** That is every MCP tool, any HTTP endpoint, and any other programmatic interface the plugin ever
contributes, whatever the concern. **Never** expose commit / push / stash / revert / shelve, or anything
that edits file contents, through one of them. The *one* mutation such a surface makes is scheduling new
(unversioned) files for addition (add-to-VCS) so they can be grouped. The user reviews and commits in
IntelliJ.

Actions the user triggers in the IDE UI, and the setup the plugin runs when the user opens a linked
worktree, may change files and VCS state. **Code that does lives only in `dev.kosmio.hikari.worktree`, and
no toolset imports it** — that separation is what keeps a mutating helper out of reach of every MCP tool.
`grep -rn 'hikari.worktree' src/main/kotlin/dev/kosmio/hikari/changelist src/main/kotlin/dev/kosmio/hikari/analysis`
must stay empty.

## Build & test

- Java 25 (`kotlin { jvmToolchain(25) }`, which Gradle finds in `~/.jdks`); Gradle wrapper is 9.4.1 (`./gradlew`).
- `./gradlew buildPlugin verifyPlugin --no-build-cache` — keep `--no-build-cache`: when the user's IDE is
  building the same `build/` dir, the build cache fails to pack (`Could not get file mode …`). Not a code error.
- **Always run `verifyPlugin`** — it binary-checks the plugin against the newest build of every platform line
  from `sinceBuild` on (2026.2.3 and the 2026.3 EAP) and catches API drift that compilation alone misses. A
  compatibility problem fails it; internal API usage is reported without failing it (`failureLevel`).
- Artifact: `build/distributions/hikari-<version>.zip`.
- **Every code change needs a reinstall to test live**: Settings → Plugins → ⚙ → Install Plugin from Disk →
  the zip → **restart**. There is no hot reload. `./gradlew runIde` gives an isolated sandbox IDE instead.
- **Point `runIde` at a throwaway project, never one your own IDE has open.** Two IDEs sharing a project
  directory both own its `.idea/workspace.xml`, so the sandbox rewrites the changelist grouping the real IDE
  is holding. A scratch Gradle project is enough and takes seconds to make — but it must be a *configured*
  one (`settings.gradle.kts` + `build.gradle.kts` + `src/main/kotlin`): in a bare directory with no module or
  source root, `ReferencesSearch` over `projectScope` finds nothing and `call_tree` answers zero callers for a
  declaration that plainly has one. Add the path to `<sandbox>/config/options/trusted-paths.xml` first, or the
  IDE blocks on the trust dialog and never opens the MCP port.
- **`./gradlew runIde262` is the same sandbox on 2026.2.3**, the oldest supported line, where the code bridging
  2026.2 and 2026.3 takes its 2026.2 path; `runIde` runs the build target. Its directories sit beside
  `runIde`'s, suffixed `_runIde262` (`config_runIde262`, `log_runIde262`, …).
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

- `intellijIdea("263.7031.23")`, the 2026.3 EAP, **not** `create("IC", …)` — IC/IU stopped being separate Maven
  artifacts at 2025.3. Once 2026.3 is released, pin the released version instead.
- IntelliJ Platform Gradle Plugin **2.16+** requires **Gradle 9+** (hence the 9.4.1 wrapper).
- Kotlin **2.4.21** — 2026.3's bundled Kotlin plugin ships jars with metadata 2.5.0, and a compiler reads
  metadata at most one minor version ahead of its own; an older one fails with "Module was compiled with an
  incompatible version of Kotlin", or a cascade of bogus "unresolved reference" errors on platform classes.
- **JVM 25**: 2026.2 and 2026.3 run on JBR 25, and the IntelliJ Platform Gradle Plugin sets that as a *task-level*
  convention on every Kotlin compile task (`KotlinJvmToolchainConventionsKt`). It outranks a project-level
  `kotlin { compilerOptions { jvmTarget } }`, so pinning a lower target there leaves `compileJava` and
  `compileKotlin` inconsistent and the build fails.
- `sinceBuild = "262"` (2026.2+): `GitWorkingTreesService.deleteWorkingTree` exists only from 2026.2.
- **Built against 2026.3, loaded on 2026.2 too.** What 2026.3 changed in binary form, the code works around so
  one build serves both lines:
  - `ChangeListManagerImpl.executeOnUpdaterThread` takes a `Runnable` on 2026.2 and a `Function0` on 2026.3, so
    `HikariChangelistToolset.executeOnUpdaterThread` calls it through reflection, out of `verifyPlugin`'s sight;
  - `getInstanceImpl` / `getInstanceEx` sit on a Kotlin `Companion` on 2026.3, so
    `HikariChangelistToolset.changeListManagerImpl` casts `ChangeListManager.getInstance` instead;
  - `McpToolset` gained default methods on 2026.3, so the Kotlin compiler runs with
    `jvmDefault = NO_COMPATIBILITY`, which compiles no bridge to them into the toolsets;
  - `GitWorkingTreeTabActionsDataKeys` moved package on 2026.3, so `WorkingTreesCompat` creates its keys by
    their names, which both versions share;
  - `GitWorkingTreesService.deleteWorkingTree` returns `void` on 2026.2 and a `Job` on 2026.3, so
    `WorkingTreesCompat.deleteWorkingTree` calls it through reflection;
  - 2026.3 copies `.worktreeinclude` files itself when it creates a worktree, so `WorktreeIncludeActivity`
    returns at once on a 263 build.

  **When `sinceBuild` reaches 263, undo every item above**: call the platform directly (each helper carries a
  TODO) and drop the `jvmDefault` setting. Before using another git4idea or VCS API, decompile it from both
  distributions (§ Inspecting platform APIs) and compare the signatures: compiling against 2026.3 says
  nothing about 2026.2, and `verifyPlugin` is what catches a difference.
- Dependencies that aren't obvious: `bundledPlugin("com.intellij.mcpServer")`;
  `bundledModule("intellij.platform.vcs.impl")` (for `ChangeListManagerEx`, the patch builder, the
  line-status tracker, `ChangeListManagerImpl.editComment`); `bundledModule("intellij.platform.vcs.impl.shared")`
  (for `LocalRange`); `bundledPlugin("org.jetbrains.kotlin")` (for `toLightMethods`) and
  `bundledPlugin("com.intellij.java")` (for `PsiMethod.findSuperMethods`), used only by the Kotlin
  override hop. `bundledPlugin("Git4Idea")` and `bundledModule("intellij.platform.vcs.dvcs")` (for
  `com.intellij.dvcs.repo.Repository`, `GitRepository`'s supertype) for Merge and Remove.
  `kotlinx-serialization-json` is `compileOnly` — the platform ships its own; bundling clashes.
- **`verifyPlugin` reads the root descriptor only, never an optional `<depends config-file=…>` one.** So the
  Java dependency and the `supportsKotlinPluginMode` declaration that `hikari-kotlin.xml` carries are
  invisible to it, and it reports the plugin as using Java classes without declaring Java. Moving those to
  `plugin.xml` would make the whole plugin, changelist tools included, refuse to load in an IDE without Java,
  so the problem is listed in `verifier-ignored-problems.txt` (wired via `pluginVerification.ignoredProblemsFile`).
  That file reaches problems only — the matching *warning* stands on every run and does not fail the build.
- **The verifier restricts the plugin's id and its display name by two different word lists.** The id must
  not contain a product name such as "intellij" or "rider" (`PluginIdVerifierKt`). The `<name>` must not
  contain "plugin", "JetBrains", "IDEA" or a product name, matched as a case-insensitive substring
  (`PluginBeanValidatorKt.PLUGIN_NAME_RESTRICTED_WORDS`), so even a name that merely spells "idea" inside
  it fails. The repository name and the distribution archive name are not checked.
- **`<supportsKotlinPluginMode supportsK2="true"/>` goes in `plugin.xml`, not in the optional Kotlin config file
  it gates.** The loader reads `org.jetbrains.kotlin.supportsKotlinPluginMode` off the ROOT descriptor
  (`KotlinK2ModeCompatibilityCheckerKt.getSupportKotlinPluginModeEPs`) to decide whether to load that config file
  at all. Declared inside the file, the file is skipped in K2 mode behind a WARN — `… was not loaded` — and the
  override hop then reports itself unavailable with nothing to say why.

## The MCP tool API (2026.2 and 2026.3)

- A toolset class implements the marker `com.intellij.mcpserver.McpToolset`; each tool is a `suspend fun`
  annotated `@McpTool` + `@McpDescription`. Annotate **every parameter** too — those descriptions become the JSON schema.
- Tool name = method name. Errors: `mcpFail("…")` (returns Nothing).
- **Every tool returns `String`, and the text it returns is the contract.** A `@Serializable` return type is
  supported (the bundled `SearchToolset` uses one) but costs 2–4× the tokens for the same information, and a
  tool result is re-read on every later request in the session. The bundled `ReadToolset.read_file` returns a
  bare `String`. `@Serializable` survives only on tool
  *parameters* (`LineRange`), where the framework still generates an input schema.
- **The `@McpDescription` carries the output format, since no schema describes it.** Each one opens
  its output paragraph with `OUTPUT (text):` and then says what the lines and marks mean. A format change
  that does not update that sentence ships a tool whose caller cannot read it.
- **A `@Serializable` type reachable from a tool signature must not be recursive.** The framework
  post-processes the generated JSON schema by walking `properties`/`items` in lockstep with the
  `SerialDescriptor` (`com.intellij.mcpserver.impl.util.Schema_utilKt.removeRequiredForDefaultValues`), and a
  self-referential class derails that walk with `IllegalArgumentException: Element class
  kotlinx.serialization.json.JsonObject is not a JsonPrimitive`. The **whole toolset** is then dropped —
  `ReflectionToolsProvider` logs `Cannot load tools for …` and its tools never reach `tools/list`, with nothing
  visible to a caller. String returns sidestep this for results; it still binds any parameter type.
- Project: `currentCoroutineContext().project` (import `com.intellij.mcpserver.project`).
- Register each toolset with its own `<mcpToolset>` in `plugin.xml`.
- **A toolset's class name is its category in the IDE's MCP settings, so it carries the `Hikari` prefix.** The
  category's short name is the class's simple name (`ToolsetReflection_utilKt.toolConversions`), shown with
  `Toolset` removed (`McpToolCategoryKt.getPresentableName`),
  and the tool-filter UI groups by that short name (`McpToolFilterPanel`, `McpToolsDialog`). A class sharing a
  simple name with a bundled toolset — `com.intellij.mcpserver.toolsets.general.AnalysisToolset` — collapses into
  its category and hides one side's tools.
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
- **Tool results are re-read on every later request, so output size is a recurring cost.** It is the reason
  every tool answers in text rather than JSON, and the reason results carry only what the caller cannot
  already derive: `list_changelists` reports a comment's first line plus its length (the full text is one
  `get_changelist_comment` away), the move tools count what landed rather than echoing back the paths they
  were given, and `set_changelist_comment` reports only whether the write stuck. What was scheduled for
  addition, what was skipped and why, and the diff stay in full — those are the surprises.
  `SESSION_TOKEN_COST.md` has the measurements.
- **A result that repeats itself is the expensive mistake, not a verbose field name.** Look for repetition
  before shortening anything.
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
The same seed at each depth, at `chars / 4` per that document's convention:

| Seed | Depth | Nodes | Result |
|---|---:|---:|---:|
| `TransactionSpi.execute` | 1 | 65 | 4 547 c ≈ **1 136 t** |
| `TransactionSpi.execute` | 2 | 118 | 8 280 c ≈ **2 070 t** |

Measured against the shipped output over MCP, not a serialization sketch.

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
- **A stopped run marks every unexpanded node with `*`**, so a caller continues deterministically instead
  of reading a silently partial answer as a total one. Every line already carries its location, so a mark
  is all that is needed. The `{file, symbol}` coordinates are what the traversal re-resolves a node against
  after a cancelled read action, so resumability and cancellation-recovery are one mechanism.
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

## Outline sizing (calibrated)

Measured across the 321 source files over 4 KB in `~/Bazar/engineering/skeleton-kotlin-react`, counting
declaration nodes that pass the enclosing-declaration filter.

| | entries |
|---|---:|
| median file | 21 |
| p90 | 64 |
| widest (`schemas.test.ts`, `AuthSessionSteps.kt`) | 127 |

The widest of them serialises to 9 263 c ≈ 2 316 t, at `chars / 4` per `SESSION_TOKEN_COST.md`.

- **An outline is cheaper than the file it describes, but not by a constant factor.** The 775-line
  `OAuthConsentDecisionSteps.kt` outlines to 10 160 c against 39 047 c of source, roughly a quarter. A
  declaration-dense file inverts that: `AuthProperties.kt` is 5 284 c of source and outlines to 6 517 c,
  because a one-line property costs as much to describe as to read. That inversion is what the cap is
  for — `MAX_OUTLINE_CHARS`, 24 KB, which sits above every file measured here and so binds only on the
  pathological one.
- **A capped outline reports `truncated` and counts what it dropped**, and the entries kept are a prefix
  of document order, so every remaining `parent` index still points at an entry that is present.

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
- **A structure-view model builds with no editor and no open file:**
  `LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile)` answers a
  `TreeBasedStructureViewBuilder` whose `createStructureViewModel(null)` works from a background read
  action — `KotlinFirStructureViewFactory$1` for Kotlin, `TypeScriptStructureViewBuilderFactory$1` for
  TypeScript. Dispose the model when done. `ItemPresentation.presentableText` must be read while the
  model is alive, and it is where the signature comes from: Kotlin answers `execute(suspend () -> T): T`,
  while TypeScript answers the bare `Button` for a component bound to a variable — a language that has no
  signature to give is not an error, it is that language's structure view.
- **The structure view is not a declaration list, and `PsiNameIdentifierOwner` is exactly the filter that
  makes it one.** It also emits the `PsiFile` root, `JSXXmlLiteralExpression` for a component's returned
  markup, and one `ES6ExportSpecifier` per re-export — on `Button.tsx` those are all it emits beyond the
  declarations. Every node that survives the filter is also found by the traversal's own `declarationsOf`
  walk, which is what makes the `#N` ordinals agree between the tools: the 775-line
  `OAuthConsentDecisionSteps.kt` yields 97 outline entries, all of whose symbols resolve.
- **`TestSourcesFilter.isTestSources` classifies nothing in this project.** It returns false for every node of
  the `TransactionSpi.execute` tree, including the step definitions, because they live in a Gradle *main*
  source set at `platform/server/test/src/main/kotlin`. Test detection has to key on path or package, and it
  is per-language: TypeScript tests sit beside their sources as `*.test.tsx`.

## Source layout

- `src/main/kotlin/dev/kosmio/hikari/ProjectPaths.kt` — project-relative path helpers, shared by every toolset this plugin contributes.
- `src/main/kotlin/dev/kosmio/hikari/changelist/HikariChangelistToolset.kt` — the changelist `McpToolset`, its tools, private helpers.
- `src/main/kotlin/dev/kosmio/hikari/changelist/Models.kt` — `@Serializable` result DTOs for the changelist tools.
- `src/main/kotlin/dev/kosmio/hikari/analysis/HikariAnalysisToolset.kt` — the analysis `McpToolset`.
- `src/main/kotlin/dev/kosmio/hikari/analysis/CallTreeTraversal.kt` — the traversal engine behind `call_tree`.
- `src/main/kotlin/dev/kosmio/hikari/analysis/OutlineBuilder.kt` — the structure-view walk behind `outline`.
- `src/main/kotlin/dev/kosmio/hikari/analysis/DeclarationSymbols.kt` — the symbol grammar `outline` emits and `call_tree` accepts; each must derive it from here or they stop composing.
- `src/main/kotlin/dev/kosmio/hikari/analysis/SourceFiles.kt` — resolving a requested path and reporting how fresh the IDE's copy of it is.
- `src/main/kotlin/dev/kosmio/hikari/analysis/Models.kt` — `@Serializable` result DTOs for the analysis tools.
- `src/main/kotlin/dev/kosmio/hikari/analysis/SuperDeclarationProvider.kt` — the override hop's extension point.
- `src/main/kotlin/dev/kosmio/hikari/analysis/kt/KotlinSuperDeclarationProvider.kt` — its Kotlin implementation.
- `src/main/kotlin/dev/kosmio/hikari/worktree/MergeAndRemoveWorktreeAction.kt` — the Worktrees tab action, and when it is enabled.
- `src/main/kotlin/dev/kosmio/hikari/worktree/WorktreeMergeService.kt` — the merge-and-remove steps.
- `src/main/kotlin/dev/kosmio/hikari/worktree/WorkingTreesCompat.kt` — the git4idea worktree APIs that differ between 2026.2 and 2026.3.
- `src/main/kotlin/dev/kosmio/hikari/worktree/WorktreeIncludeActivity.kt` — the `.worktreeinclude` copy on opening a linked worktree, 2026.2 only.
- `src/main/kotlin/dev/kosmio/hikari/worktree/WorktreeGit.kt` — running git through git4idea.
- `src/main/resources/META-INF/plugin.xml` — the `<mcpToolset>` registrations, the extension point, the optional `<depends>`, the worktree action, its notification group and the `.worktreeinclude` startup activity.
- `src/main/resources/META-INF/hikari-kotlin.xml` — loaded only when the Kotlin plugin is present.

## Inspecting platform APIs

When unsure of a signature, decompile against the resolved SDK rather than guessing:
`javap -p -cp "<idea>/lib/*.jar" <fqcn>`, where `<idea>` is under
`~/.gradle/caches/<gradle>/transforms/**/transformed/idea-<version>/`. For readable Java, the IDE's own
`mcp__idea__read_file` reads `…/lib/foo.jar!/pkg/Bar.class`.
