package dev.local.player.desktop

import java.io.File
import java.util.Properties

/**
 * Настройки приложения в %APPDATA%/AkkMusic/config.properties (Windows)
 * или ~/.config/AkkMusic на других системах. Пароль не храним — только
 * Subsonic-токен и соль, как в Android-версии.
 */
object AppConfig {

    private val dir: File = run {
        val base = System.getenv("APPDATA")
            ?: (System.getProperty("user.home") + File.separator + ".config")
        File(base, "AkkMusic").apply { mkdirs() }
    }
    private val file = File(dir, "config.properties")
    private val props = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it) }
    }

    /** Папка приложения — пригодится для кеша обложек. */
    fun appDir(): File = dir

    private fun get(key: String): String? = props.getProperty(key)?.takeIf { it.isNotEmpty() }
    private fun set(key: String, value: String?) {
        if (value.isNullOrEmpty()) props.remove(key) else props.setProperty(key, value)
        save()
    }

    private fun save() {
        file.outputStream().use { props.store(it, "AkkMusic") }
    }

    // ---------- Источник музыки ----------

    var source: LibrarySource
        get() = LibrarySource.fromName(get("source"))
        set(v) = set("source", v.name)

    var localFolder: String?
        get() = get("localFolder")
        set(v) = set("localFolder", v)

    // ---------- Сервер ----------

    var serverUrl: String?
        get() = get("serverUrl")
        private set(v) = set("serverUrl", v)

    var serverUser: String?
        get() = get("serverUser")
        private set(v) = set("serverUser", v)

    val serverConfigured: Boolean get() = get("serverUrl") != null && get("serverToken") != null

    fun serverClient(): SubsonicClient? {
        if (!serverConfigured) return null
        return SubsonicClient(get("serverUrl")!!, get("serverUser") ?: "", get("serverToken")!!, get("serverSalt")!!)
    }

    fun saveServer(client: SubsonicClient) {
        props.setProperty("serverUrl", client.baseUrl)
        props.setProperty("serverUser", client.user)
        props.setProperty("serverToken", client.token)
        props.setProperty("serverSalt", client.salt)
        save()
    }

    fun forgetServer() {
        listOf("serverUrl", "serverUser", "serverToken", "serverSalt").forEach { props.remove(it) }
        props.setProperty("source", LibrarySource.LOCAL.name)
        save()
    }

    // ---------- Внешний вид ----------

    var accent: String?
        get() = get("accent")
        set(v) = set("accent", v)

    var textColor: String?
        get() = get("textColor")
        set(v) = set("textColor", v)

    var bgPath: String?
        get() = get("bgPath")
        set(v) = set("bgPath", v)

    var bgDim: Int
        get() = (get("bgDim")?.toIntOrNull() ?: 55).coerceIn(0, 90)
        set(v) = set("bgDim", v.coerceIn(0, 90).toString())

    var volume: Int
        get() = get("volume")?.toIntOrNull() ?: 80
        set(v) = set("volume", v.coerceIn(0, 100).toString())
}
