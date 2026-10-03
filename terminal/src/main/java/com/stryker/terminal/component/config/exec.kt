package com.stryker.terminal.component.config

import android.content.Context
import java.io.File

object ExecPolicy {

    private val STREAMS = arrayOf("tcp:", "pty:", "ssh:", "unix:")

    data class Launch(val program: String, val argv: List<String>)

    @JvmStatic
    fun rewrite(context: Context, program: String?, argv: List<String>): Launch {
        val path = program ?: return Launch("/system/bin/sh", listOf("/system/bin/sh"))
        if (path.isEmpty()) return Launch(path, argv)
        if (STREAMS.any { path.startsWith(it) }) return Launch(path, argv)
        if (!path.startsWith(NeoTermPath.ROOT_PATH)) return Launch(path, argv)

        val file = File(path)

        packaged(context, file.name)?.let { return Launch(it, argv) }

        val interpreter = shebang(file)
        if (interpreter != null) {
            val tail = if (argv.size > 1) argv.subList(1, argv.size) else emptyList()
            return Launch(
                interpreter[0],
                listOf(File(interpreter[0]).name) + interpreter.drop(1) + path + tail
            )
        }

        if (isElf(file)) return Launch("/system/bin/sh", listOf("sh"))

        return Launch(path, argv)
    }

    private fun packaged(context: Context, name: String): String? {
        return try {
            val dir = context.applicationInfo?.nativeLibraryDir ?: return null
            val lib = File(dir, "lib" + name.replace('-', '_').replace('.', '_') + ".so")
            if (lib.canExecute()) lib.absolutePath else null
        } catch (e: Exception) {
            null
        }
    }

    private fun shebang(file: File): List<String>? {
        return try {
            val head = ByteArray(256)
            val read = file.inputStream().use { it.read(head) }
            if (read < 3 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return null
            val line = String(head, 0, read, Charsets.UTF_8)
                .substringBefore('\n')
                .substring(2)
                .trim()
            if (line.isEmpty()) return null
            val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.isEmpty() || !parts[0].startsWith("/")) null else parts
        } catch (e: Exception) {
            null
        }
    }

    private fun isElf(file: File): Boolean {
        return try {
            val head = ByteArray(4)
            val read = file.inputStream().use { it.read(head) }
            read == 4 && head[0] == 0x7F.toByte()
                    && head[1] == 'E'.code.toByte()
                    && head[2] == 'L'.code.toByte()
                    && head[3] == 'F'.code.toByte()
        } catch (e: Exception) {
            false
        }
    }
}
