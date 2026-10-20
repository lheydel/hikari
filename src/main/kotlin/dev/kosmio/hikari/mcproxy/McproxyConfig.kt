package dev.kosmio.hikari.mcproxy

/**
 * Lists an IDE's MCP URL in mcproxy's config, as a text edit: comments, blank lines, key order and other
 * groups stay byte-identical, since a TOML round-trip would drop the comments that name each IDE.
 *
 * An IDE is the trailing comment of its `upstreams` line, `# <product name>`. Every group whose section
 * has `adapter = "jetbrains"` reaches the same end state: exactly one line holding the URL with the
 * IDE's comment. A line already holding the URL is kept where it is, else a line carrying the comment
 * takes the URL in place, since the order of `upstreams` decides which IDE wins a shared tool or
 * project; else the line is appended. Any further line holding the URL or the comment is removed, as
 * mcproxy refuses a URL listed twice.
 *
 * The `upstreams` form recognised is a multi-line array of one quoted URL per line, optional trailing
 * comma and optional `# comment`, closed by a line holding `]`; any other form is refused rather than
 * guessed at.
 */
internal object McproxyConfig {

    /** The group created when the config has no jetbrains group. */
    const val DEFAULT_GROUP = "idea"

    sealed interface Status {
        /** The group is already in the end state. */
        data object Listed : Status

        /** A line carries this IDE's comment, with another URL. */
        data class ListedWithAnotherUrl(val url: String) : Status

        /** The URL is listed, but not as a single line carrying this IDE's comment. */
        data object ListedUntidy : Status

        data object NotListed : Status
    }

    sealed interface Reading {
        /** Each jetbrains group with its status, in file order; empty when the config has none. */
        data class Groups(val statuses: List<Pair<String, Status>>) : Reading

        data class Refused(val message: String) : Reading
    }

    sealed interface Edit {
        data class Written(val text: String) : Edit

        data class Refused(val message: String) : Edit
    }

    /** Where each jetbrains group of [text] stands; a missing file ([text] null) has no groups. */
    fun read(text: String?, url: String, product: String): Reading {
        if (text == null) return Reading.Groups(emptyList())
        val parsed = parse(Lines(text)).getOrElse { return Reading.Refused(it.message!!) }
        parsed.defaultGroupConflict()?.let { return Reading.Refused(it) }
        return Reading.Groups(parsed.jetbrains.map { it.name to it.status(url, product) })
    }

    /** [text] with [url] listed under `# product` in every jetbrains group, or why it cannot be. */
    fun add(text: String?, url: String, product: String): Edit {
        if (text == null) return Edit.Written(newGroup(url, product, "\n"))
        val lines = Lines(text)
        val parsed = parse(lines).getOrElse { return Edit.Refused(it.message!!) }
        if (parsed.jetbrains.isEmpty()) {
            parsed.defaultGroupConflict()?.let { return Edit.Refused(it) }
            val separator = when {
                text.isEmpty() || text.endsWith("\n\n") -> ""
                text.endsWith("\n") -> lines.eol
                else -> lines.eol + lines.eol
            }
            return Edit.Written(text + separator + newGroup(url, product, lines.eol))
        }
        // Bottom-up, so an edit never shifts the lines of a group still to be edited.
        for (group in parsed.jetbrains.asReversed()) group.edit(lines, url, product)
        return Edit.Written(lines.text())
    }

    private fun newGroup(url: String, product: String, eol: String): String =
        listOf(
            "[groups.$DEFAULT_GROUP]",
            "adapter = \"jetbrains\"",
            "upstreams = [",
            "  \"$url\", # $product",
            "]",
        ).joinToString(eol, postfix = eol)

    /** The jetbrains groups, and the adapter of every other group: null when it has no recognisable one. */
    private class Parsed(val jetbrains: List<Group>, val otherAdapters: Map<String, String?>) {

        /** Why [DEFAULT_GROUP] cannot be created, when the config has no jetbrains group to edit instead. */
        fun defaultGroupConflict(): String? {
            if (jetbrains.isNotEmpty() || DEFAULT_GROUP !in otherAdapters) return null
            val adapter = otherAdapters[DEFAULT_GROUP]?.let { "\"$it\"" } ?: "unset"
            return "Group `$DEFAULT_GROUP` exists with adapter $adapter, not \"jetbrains\", and no other group " +
                "has adapter \"jetbrains\" to list this IDE in."
        }
    }

    private fun parse(lines: Lines): Result<Parsed> {
        val jetbrains = mutableListOf<Group>()
        val otherAdapters = mutableMapOf<String, String?>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            // A section is consumed whole below, so a line reached here before any header is at the root.
            if (!HEADER.matches(line)) {
                if (ROOT_GROUPS_KEY.containsMatchIn(line)) {
                    return refused("`groups` is set by a key at the root; this page edits only `[groups.<name>]` sections.")
                }
                index++
                continue
            }
            val end = lines.sectionEnd(index)
            val header = GROUP_HEADER.matchEntire(line)
            if (header == null) {
                if (GROUPS_TABLE.containsMatchIn(line)) {
                    return refused("`${line.trim()}` is not a `[groups.<name>]` section; this page edits only those.")
                }
                index = end
                continue
            }
            val name = header.groupValues[1].ifEmpty { header.groupValues[2] }
            val adapter = (index + 1 until end).firstNotNullOfOrNull { ADAPTER.matchEntire(lines[it])?.groupValues?.get(1) }
            if (adapter == "jetbrains") {
                jetbrains += parseGroup(lines, name, index + 1, end).getOrElse { return Result.failure(it) }
            } else {
                otherAdapters[name] = adapter
            }
            index = end
        }
        return Result.success(Parsed(jetbrains, otherAdapters))
    }

    private fun parseGroup(lines: Lines, name: String, start: Int, end: Int): Result<Group> {
        val open = (start until end).firstOrNull { UPSTREAMS_KEY.containsMatchIn(lines[it]) }
            ?: return refused(name, "has no `upstreams`")
        if (!UPSTREAMS_OPEN.matches(lines[open])) return refused(name, "writes `upstreams` in another form")
        val entries = mutableListOf<Entry>()
        for (index in open + 1 until end) {
            val line = lines[index]
            if (UPSTREAMS_CLOSE.matches(line)) return Result.success(Group(name, entries, index))
            val entry = ENTRY.matchEntire(line)
            when {
                entry != null -> entries += Entry(index, entry)
                BLANK_OR_COMMENT.matches(line) -> {}
                else -> return refused(name, "has an `upstreams` line in another form: `${line.trim()}`")
            }
        }
        return refused(name, "never closes `upstreams` with a line holding `]`")
    }

    private fun <T> refused(name: String, problem: String): Result<T> =
        refused("Group `$name` $problem. The form this page edits is:\n$EXPECTED_FORM")

    private fun <T> refused(message: String): Result<T> = Result.failure(IllegalArgumentException(message))

    private class Group(val name: String, val entries: List<Entry>, val closeIndex: Int) {

        fun status(url: String, product: String): Status {
            val holding = entries.filter { it.url == url }
            val named = entries.filter { it.comment == product }
            return when {
                holding.size == 1 && named == holding -> Status.Listed
                holding.isNotEmpty() -> Status.ListedUntidy
                named.isNotEmpty() -> Status.ListedWithAnotherUrl(named.first().url)
                else -> Status.NotListed
            }
        }

        fun edit(lines: Lines, url: String, product: String) {
            val kept = entries.firstOrNull { it.url == url } ?: entries.firstOrNull { it.comment == product }
            if (kept == null) {
                val last = entries.lastOrNull()
                if (last != null && !last.hasComma) lines[last.index] = last.withComma(lines[last.index])
                lines.insert((last?.index ?: (closeIndex - 1)) + 1, "${last?.indent ?: "  "}\"$url\", # $product")
                return
            }
            lines[kept.index] = kept.rewritten(lines[kept.index], url, product)
            entries.filter { it !== kept && (it.url == url || it.comment == product) }
                .asReversed()
                .forEach { lines.remove(it.index) }
        }
    }

    private class Entry(val index: Int, private val match: MatchResult) {
        val indent: String = match.groupValues[1]
        val url: String = match.groupValues[2]
        val hasComma: Boolean = match.groups[3] != null
        val comment: String? = match.groups[4]?.value?.removePrefix("#")?.trim()
        private val urlRange: IntRange = match.groups[2]!!.range

        /** [line] holding [url] and carrying `# product`; the URL precedes the comment, so it is replaced last. */
        fun rewritten(line: String, url: String, product: String): String {
            val commentRange = match.groups[4]?.range
            val commented = when {
                comment == product -> line
                commentRange != null -> line.replaceRange(commentRange, "# $product")
                else -> line.trimEnd() + " # $product"
            }
            return commented.replaceRange(urlRange, url)
        }

        /** [line] with a comma after the closing quote of its URL. */
        fun withComma(line: String): String {
            val afterQuote = urlRange.last + 2
            return line.substring(0, afterQuote) + "," + line.substring(afterQuote)
        }
    }

    /**
     * The text's lines, read and written without their terminators. Each line keeps the `\r` it was read
     * with, so [text] restores a file of mixed line endings byte for byte; an inserted line takes [eol].
     */
    private class Lines(text: String) {
        val eol: String = if ("\r\n" in text) "\r\n" else "\n"
        private val lines: MutableList<String> = text.split("\n").toMutableList()

        val size: Int get() = lines.size

        operator fun get(index: Int): String = lines[index].removeSuffix("\r")

        operator fun set(index: Int, content: String) {
            lines[index] = if (lines[index].endsWith("\r")) "$content\r" else content
        }

        fun insert(index: Int, content: String) = lines.add(index, content + eol.removeSuffix("\n"))

        fun remove(index: Int) = lines.removeAt(index)

        /** The index after the last line of the section the header at [header] opens. */
        fun sectionEnd(header: Int): Int = (header + 1 until size).firstOrNull { HEADER.matches(get(it)) } ?: size

        fun text(): String = lines.joinToString("\n")
    }

    private val EXPECTED_FORM = """
        [groups.$DEFAULT_GROUP]
        adapter = "jetbrains"
        upstreams = [
          "http://127.0.0.1:64342/stream", # IntelliJ IDEA
          "http://127.0.0.1:64522/stream", # RustRover
        ]
    """.trimIndent()

    private const val COMMENT = """(?:#.*)?"""
    private val HEADER = Regex("""\s*\[.*""")
    private val GROUP_HEADER = Regex("""\s*\[\s*groups\s*\.\s*(?:([A-Za-z0-9_-]+)|"([^"\\]*)")\s*]\s*$COMMENT""")
    private val GROUPS_TABLE = Regex("""^\s*\[\[?\s*"?groups\b""")
    private val ROOT_GROUPS_KEY = Regex("""^\s*"?groups"?\s*[.=]""")
    private val ADAPTER = Regex("""\s*adapter\s*=\s*["']([^"'\\]*)["']\s*$COMMENT""")
    private val UPSTREAMS_KEY = Regex("""^\s*"?upstreams"?\s*[.=]""")
    private val UPSTREAMS_OPEN = Regex("""\s*upstreams\s*=\s*\[\s*$COMMENT""")
    private val UPSTREAMS_CLOSE = Regex("""\s*]\s*$COMMENT""")
    private val ENTRY = Regex("""(\s*)"([^"\\]*)"\s*(,)?\s*(#.*?)?\s*""")
    private val BLANK_OR_COMMENT = Regex("""\s*$COMMENT""")
}
