package dev.kosmio.hikari.indexing

import java.util.regex.Pattern

/**
 * A list of exclusion globs, compiled once and matched against a path relative to the project directory, with
 * `/` as separator. The syntax is a subset of `.gitignore`'s:
 *
 * - `*` matches within one path segment, `?` one character of a segment;
 * - `**` matches across segments, and as a whole segment it also matches none, so `a/` + `**` + `/b`
 *   matches `a/b` as well as `a/x/y/b`;
 * - a pattern containing no `/` matches at any depth; one containing a `/` is anchored at the project
 *   directory, and a leading `/` only says so;
 * - a trailing `/` restricts the pattern to directories.
 *
 * Every other character is literal. Matching a directory excludes everything beneath it, since the file index
 * asks about each ancestor of a file.
 */
internal class ExclusionPatterns private constructor(private val compiled: List<Compiled>) {

    private class Compiled(val pattern: Pattern, val directoryOnly: Boolean)

    fun isEmpty(): Boolean = compiled.isEmpty()

    fun matches(relativePath: String, isDirectory: Boolean): Boolean =
        compiled.any { (isDirectory || !it.directoryOnly) && it.pattern.matcher(relativePath).matches() }

    companion object {
        val NONE = ExclusionPatterns(emptyList())

        fun of(patterns: List<String>): ExclusionPatterns =
            patterns.mapNotNull(::compile).let { if (it.isEmpty()) NONE else ExclusionPatterns(it) }

        /** One pattern per line; blank lines dropped, surrounding whitespace trimmed. */
        fun parseLines(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)

        private fun compile(raw: String): Compiled? {
            val trimmed = raw.trim()
            val directoryOnly = trimmed.endsWith("/")
            val body = trimmed.trim('/')
            if (body.isEmpty()) return null
            val anchored = trimmed.startsWith("/") || body.contains('/')
            return Compiled(Pattern.compile(toRegex(if (anchored) body else "**/$body")), directoryOnly)
        }

        private fun toRegex(glob: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                when {
                    glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3 }
                    glob.startsWith("**", i) -> { out.append(".*"); i += 2 }
                    glob[i] == '*' -> { out.append("[^/]*"); i++ }
                    glob[i] == '?' -> { out.append("[^/]"); i++ }
                    else -> { out.append(Pattern.quote(glob[i].toString())); i++ }
                }
            }
            return out.toString()
        }
    }
}
