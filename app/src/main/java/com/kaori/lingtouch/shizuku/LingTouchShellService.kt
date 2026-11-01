package com.kaori.lingtouch.shizuku

import android.os.Binder
import android.os.Parcel
import android.system.Os
import java.util.concurrent.TimeUnit

/**
 * Runs inside Shizuku's UserService process with shell (uid 2000) or root (uid 0) identity.
 * Only commands constructed and validated by the app are ever sent here.
 */
class LingTouchShellService : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            ShellProtocol.TRANSACTION_EXEC -> {
                data.enforceInterface(ShellProtocol.DESCRIPTOR)
                val command = data.readString().orEmpty()
                val result = execute(command)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }
            ShellProtocol.TRANSACTION_UID -> {
                data.enforceInterface(ShellProtocol.DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(runCatching { Os.getuid() }.getOrDefault(-1))
                return true
            }
            ShellProtocol.TRANSACTION_DESTROY, ShellProtocol.SHIZUKU_DESTROY_TRANSACTION -> {
                runCatching { System.exit(0) }
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }

    private fun execute(command: String): String {
        if (command.length > MAX_COMMAND_LENGTH) return "ERROR:command too long"
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (output.length < MAX_OUTPUT) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    output.append(buffer, 0, read)
                }
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "ERROR:timeout\n${output.toString().take(MAX_OUTPUT)}"
            }
            "EXIT:${process.exitValue()}\n${output.toString()}"
        } catch (t: Throwable) {
            "ERROR:${t.message ?: t.javaClass.simpleName}"
        }
    }

    private companion object {
        const val MAX_COMMAND_LENGTH = 4096
        const val MAX_OUTPUT = 200_000
    }
}
