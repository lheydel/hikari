package dev.kosmio.hikari.mcproxy

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * mcproxy's config file at its default path, `~/.config/mcproxy/config.toml` on every OS (mcproxy contract
 * CFG-1). `MCPROXY_CONFIG` is not read: the IDE's environment is not the one Claude Code starts mcproxy in,
 * so a path taken from it could be one mcproxy never reads.
 */
internal object McproxyConfigFile {

    val path: Path = Path.of(System.getProperty("user.home"), ".config", "mcproxy", "config.toml")

    /** The config's text, or null when it does not exist. */
    fun read(): String? =
        try {
            Files.readString(path)
        } catch (_: NoSuchFileException) {
            null
        }

    /**
     * Replaces the config with [text] through a sibling file moved into place, so a crash mid-write never
     * leaves a truncated config, on which every `mcproxy serve` fails at startup. A symlinked config is
     * written at its target. A config that exists keeps its permissions; a new one gets the umask's, as a
     * config written by hand would.
     */
    fun write(text: String) {
        val target = if (Files.isSymbolicLink(path)) path.toRealPath() else path
        val directory = Files.createDirectories(target.parent)
        val temp = directory.resolve("${target.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temp, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            if (Files.exists(target)) {
                try {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target))
                } catch (_: UnsupportedOperationException) {
                }
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Files.deleteIfExists(temp)
            throw e
        }
    }
}
