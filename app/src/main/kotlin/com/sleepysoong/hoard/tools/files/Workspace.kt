package com.sleepysoong.hoard.tools.files

import java.io.File
import java.io.IOException

/**
 * A folder the file tools may use besides the app workspace, e.g. the phone's
 * shared storage (/storage/emulated/0). [available] is checked on every access
 * (the user can revoke "All files access" at any time).
 */
class StorageRoot(
    val label: String,
    dir: File,
    val available: () -> Boolean = { true },
    /** Shown to the model when [available] is false: what the user has to do. */
    val unavailableReason: String = "not accessible"
) {
    val dir: File = dir.canonicalFile
}

/**
 * Where the file tools may read and write.
 *
 * - [root] (the app's private workspace) is the default: relative paths resolve there.
 * - [extraRoots] (optional, e.g. shared storage) are reached with absolute paths.
 *
 * Every model-supplied path is normalized *and* symlink-resolved (canonical path) and
 * must end up inside one of those folders: `..`, other absolute paths and links
 * pointing elsewhere are refused. Workspace files are shown relative to [root],
 * everything else as an absolute path.
 */
class Workspace(
    root: File,
    val extraRoots: List<StorageRoot> = emptyList(),
    /** Stop glob/grep walks after this many entries (a whole phone storage is big). */
    val maxWalkEntries: Int = 200_000,
    val maxWalkMillis: Long = 15_000
) {
    val root: File = root.apply { mkdirs() }.canonicalFile

    /** Tells the model where it can reach, appended to every file tool's description. */
    val note: String get() = buildString {
        append("Relative paths are in the Hoard workspace (a private folder of this app).")
        if (extraRoots.isEmpty()) {
            append(" Nothing outside it is reachable.")
        } else {
            append(" Absolute paths are also allowed under: ")
            append(extraRoots.joinToString("; ") { "${it.dir.path} (${it.label})" })
            append(". Android/data and Android/obb in shared storage are blocked by Android.")
        }
    }

    /** Resolves [path] ("" / "." / "/" = the workspace root). Throws [FileToolException] if it is not reachable. */
    fun resolve(path: String?): File {
        val p = path?.trim().orEmpty()
        if (p.contains('\u0000')) throw FileToolException("invalid path")
        val raw = when {
            p.isEmpty() || p == "." || p == "/" -> root
            File(p).isAbsolute -> File(p)
            else -> File(root, p)
        }
        val canonical = try {
            raw.canonicalFile
        } catch (e: IOException) {
            throw FileToolException("invalid path: $p")
        }
        if (canonical.isInside(root)) return canonical
        val extra = extraRoots.firstOrNull { canonical.isInside(it.dir) }
            ?: throw FileToolException(
                "path is outside the accessible locations: $p" +
                    if (extraRoots.isEmpty()) " (paths are relative to the workspace root)"
                    else " (allowed: workspace, " + extraRoots.joinToString { it.dir.path } + ")"
            )
        if (!extra.available()) throw FileToolException("${extra.label} is not accessible: ${extra.unavailableReason}")
        return canonical
    }

    /** Display path: workspace-relative with '/' ("." for the root), else absolute. */
    fun relative(file: File): String {
        val c = file.canonicalFile
        if (c == root) return "."
        if (c.isInside(root)) return c.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/')
        return c.path
    }

    fun isRoot(file: File): Boolean = file.canonicalFile.let { c -> c == root || extraRoots.any { it.dir == c } }

    private fun File.isInside(dir: File) = this == dir || path.startsWith(dir.path + File.separator)
}

class FileToolException(message: String) : Exception(message)
