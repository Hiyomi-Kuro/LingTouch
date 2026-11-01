package com.kaori.lingtouch.shizuku

/** Whitelist command builder. Arguments are strictly validated; no free-form model text. */
object ShellCommand {
    private val packageRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val classRegex = Regex("^[A-Za-z0-9_.$]+$")
    private val settingKeyRegex = Regex("^[A-Za-z0-9_]+$")

    fun validPackage(pkg: String): Boolean = packageRegex.matches(pkg)

    fun amStart(pkg: String, className: String): String? {
        if (!validPackage(pkg)) return null
        if (!classRegex.matches(className) || className.length > 200) return null
        return "am start -n $pkg/$className"
    }

    fun amForceStop(pkg: String): String? =
        if (validPackage(pkg)) "am force-stop $pkg" else null

    fun dumpsysPackage(pkg: String): String? =
        if (validPackage(pkg)) "dumpsys package $pkg" else null

    fun settingsGet(namespace: String, key: String): String? {
        val ns = namespace.lowercase()
        if (ns !in setOf("system", "secure", "global")) return null
        if (!settingKeyRegex.matches(key)) return null
        return "settings get $ns $key"
    }

    fun pmListPackages(): String = "pm list packages -f"

    fun listUsers(): String = "pm list users"

    fun id(): String = "id -u"

    fun commandExists(binary: String): String? =
        if (Regex("^[a-z0-9_]+$").matches(binary)) "command -v $binary" else null
}
