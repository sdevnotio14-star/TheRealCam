package com.therealcam.core

import java.util.concurrent.TimeUnit

/** Runs fixed scripts through `su` (Magisk / KernelSU). Nothing user-provided is ever executed. */
object RootShell {
    data class Result(val ok: Boolean, val output: String)

    fun run(script: String, timeoutS: Long = 20): Result = try {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") }
        // The root manager may show a grant dialog; give the user time to answer it.
        val watchdog = Thread { if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) p.destroy() }.apply { isDaemon = true; start() }
        val out = p.inputStream.bufferedReader().readText().trim()
        watchdog.join(1000)
        Result(runCatching { p.exitValue() == 0 }.getOrDefault(false), out)
    } catch (e: Exception) {
        Result(false, e.message ?: "su недоступен")
    }
}

/**
 * Xiaomi/Redmi/POCO and other Qualcomm (CAF) firmwares show the extra camera modules only to packages
 * listed in `vendor.camera.aux.packagelist`. With root the app adds itself to that list. resetprop
 * keeps the change in memory only: it is gone after a reboot and re-applied at the next app start.
 */
object RootCamera {
    private val LISTS = listOf("vendor.camera.aux.packagelist", "camera.aux.packagelist")
    /** Changed only when they already exist, and never written to disk. */
    private val PERSIST_LISTS = listOf("persist.vendor.camera.privapp.list", "persist.camera.privapp.list")

    fun enable(pkg: String): RootShell.Result = RootShell.run(
        buildString {
            appendLine("P='$pkg'")
            appendLine("echo \"uid=$(id -u)\"")
            appendLine(
                "add() { V=\$(getprop \"\$1\"); case \",\$V,\" in *\",\$P,\"*) ;; *) " +
                    "if [ -n \"\$V\" ]; then N=\"\$V,\$P\"; else N=\"\$P\"; fi; " +
                    "resetprop \"\$1\" \"\$N\" 2>/dev/null || setprop \"\$1\" \"\$N\";; esac; echo \"\$1=\$(getprop \"\$1\")\"; }",
            )
            appendLine(
                "addp() { V=\$(getprop \"\$1\"); [ -z \"\$V\" ] && return 0; case \",\$V,\" in *\",\$P,\"*) ;; *) " +
                    "resetprop \"\$1\" \"\$V,\$P\" 2>/dev/null;; esac; echo \"\$1=\$(getprop \"\$1\")\"; }",
            )
            LISTS.forEach { appendLine("add $it") }
            PERSIST_LISTS.forEach { appendLine("addp $it") }
        },
    )

    fun disable(pkg: String): RootShell.Result = RootShell.run(
        buildString {
            appendLine("P='$pkg'")
            appendLine(
                "del() { V=\$(getprop \"\$1\"); [ -z \"\$V\" ] && return 0; " +
                    "N=\$(echo \",\$V,\" | sed \"s/,\$P,/,/g; s/^,//; s/,\$//\"); [ \"\$N\" = \"\$V\" ] && return 0; " +
                    "if [ -z \"\$N\" ]; then resetprop -d \"\$1\" 2>/dev/null; else resetprop \"\$1\" \"\$N\" 2>/dev/null || " +
                    "case \"\$1\" in persist.*) ;; *) setprop \"\$1\" \"\$N\";; esac; fi; echo \"\$1=\$(getprop \"\$1\")\"; }",
            )
            (LISTS + PERSIST_LISTS).forEach { appendLine("del $it") }
        },
    )

    /** Root granted and the main list now contains [pkg]. */
    fun applied(r: RootShell.Result, pkg: String): Boolean =
        r.output.lineSequence().any { it.trim() == "uid=0" } &&
            r.output.lineSequence().any { it.startsWith("${LISTS[0]}=") && pkg in it.substringAfter('=').split(',') }
}
