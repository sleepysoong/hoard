package com.sleepysoong.hoard.tools.files

import java.io.File
import java.io.IOException

/**
 * The only directory the file tools may touch. Every model-supplied path is
 * resolved against [root] and must stay inside it after normalization *and*
 * after following symlinks (canonical path) — `..`, absolute paths elsewhere and
 * links pointing out are refused. Paths shown to the model are relative to the root.
 */
class Workspace(root: File) {
    val root: File = root.apply { mkdirs() }.canonicalFile

    /** Resolves [path] ("" / "." = root). Throws [FileToolException] if it leaves the workspace. */
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
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
            throw FileToolException("path is outside the workspace: $p (paths are relative to the workspace root)")
        }
        return canonical
    }

    /** Workspace-relative display path with '/' separators ("." for the root). */
    fun relative(file: File): String {
        val c = file.canonicalFile
        if (c == root) return "."
        return c.path.removePrefix(root.path + File.separator).replace(File.separatorChar, '/')
    }
}

class FileToolException(message: String) : Exception(message)
