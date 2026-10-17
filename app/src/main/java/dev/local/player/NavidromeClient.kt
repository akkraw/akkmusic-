package dev.local.player

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Откуда брать музыку: только телефон, только сервер или всё вместе. */
enum class LibrarySource {
    PHONE, SERVER, ALL;

    val usesPhone: Boolean get() = this != SERVER
    val usesServer: Boolean get() = this != PHONE

    companion object {
        fun fromName(name: String?): LibrarySource = entries.find { it.name == name } ?: PHONE
    }
}

/**
 * Настройки сервера. Пароль не храним: Subsonic API принимает
 * token = md5(пароль + соль), его и запоминаем вместе с солью.
 */
class ServerSettings(context: Context) {

    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    var source: LibrarySource
        get() = LibrarySource.fromName(prefs.getString(KEY_SOURCE, null))
        set(value) = prefs.edit().putString(KEY_SOURCE, value.name).apply()

    val url: String get() = prefs.getString(KEY_URL, "") ?: ""
    val user: String get() = prefs.getString(KEY_USER, "") ?: ""

    val configured: Boolean get() = url.isNotEmpty() && prefs.contains(KEY_TOKEN)

    fun client(): NavidromeClient? = if (!configured) null else NavidromeClient(
        url, user, prefs.getString(KEY_TOKEN, "")!!, prefs.getString(KEY_SALT, "")!!,
    )

    fun save(client: NavidromeClient) {
        prefs.edit()
            .putString(KEY_URL, client.baseUrl)
            .putString(KEY_USER, client.user)
            .putString(KEY_TOKEN, client.token)
            .putString(KEY_SALT, client.salt)
            .apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_URL).remove(KEY_USER).remove(KEY_TOKEN).remove(KEY_SALT)
            .putString(KEY_SOURCE, LibrarySource.PHONE.name).apply()
    }

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_URL = "url"
        const val KEY_USER = "user"
        const val KEY_TOKEN = "token"
        const val KEY_SALT = "salt"
    }
}

class ServerException(message: String) : Exception(message)

/**
 * Клиент Subsonic API (его реализует Navidrome). Сетевые вызовы — не на главном потоке.
 * Документация: https://opensubsonic.netlify.app/docs/
 */
class NavidromeClient(
    baseUrl: String,
    val user: String,
    val token: String,
    val salt: String,
) {
    val baseUrl: String = normalizeUrl(baseUrl)

    private val auth: String =
        "u=${enc(user)}&t=$token&s=$salt&v=$API_VERSION&c=$CLIENT_NAME"

    /** Проверка адреса и логина. Бросает ServerException с понятным текстом. */
    fun ping() {
        call("ping")
    }

    /**
     * Пинг до сервера в миллисекундах. Первый запрос открывает соединение (TLS),
     * его не считаем — берём лучший из следующих, по уже открытому соединению.
     */
    fun latencyMs(): Long {
        val url = URL("$baseUrl/rest/ping?$auth&f=json")
        var best = Long.MAX_VALUE
        repeat(3) { i ->
            val start = System.nanoTime()
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                if (conn.responseCode != 200) throw ServerException("Сервер ответил ${conn.responseCode}")
                // Без disconnect(): соединение остаётся открытым для следующего замера
                conn.inputStream.use { it.readBytes() }
            } catch (e: ServerException) {
                throw e
            } catch (e: Exception) {
                throw ServerException("Сервер недоступен")
            }
            if (i > 0) best = minOf(best, (System.nanoTime() - start) / 1_000_000)
        }
        return best
    }

    /** Все треки сервера. Пустой запрос search3 в Navidrome возвращает всю библиотеку. */
    fun songs(): List<Track> {
        val result = mutableListOf<Track>()
        var offset = 0
        while (true) {
            val songs = call(
                "search3",
                "query=&artistCount=0&albumCount=0&songCount=$PAGE&songOffset=$offset",
            ).optJSONObject("searchResult3")?.optJSONArray("song") ?: break
            for (i in 0 until songs.length()) {
                val s = songs.getJSONObject(i)
                val id = s.getString("id")
                result += Track(
                    id = localId(id),
                    uri = Uri.parse(streamUrl(id)),
                    title = s.optString("title").ifBlank { "Без названия" },
                    artist = s.optString("artist").ifBlank { "Неизвестный исполнитель" },
                    album = s.optString("album").ifBlank { "Неизвестный альбом" },
                    albumId = 0,
                    durationMs = s.optLong("duration") * 1000,
                    trackNo = s.optInt("discNumber") * 1000 + s.optInt("track"),
                    remoteCoverUrl = s.optString("coverArt").takeIf { it.isNotEmpty() }
                        ?.let { coverUrl(it) },
                )
            }
            if (songs.length() < PAGE) break
            offset += PAGE
        }
        return result
    }

    /** Ссылка на поток: ExoPlayer играет её как обычный HTTP-файл (перемотка тоже работает). */
    fun streamUrl(id: String): String = "$baseUrl/rest/stream?id=${enc(id)}&$auth"

    fun coverUrl(coverId: String, size: Int = 800): String =
        "$baseUrl/rest/getCoverArt?id=${enc(coverId)}&size=$size&$auth"

    private fun call(method: String, params: String = ""): JSONObject {
        val url = "$baseUrl/rest/$method?$auth&f=json" + if (params.isEmpty()) "" else "&$params"
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ServerException("Неверный адрес сервера")
        }
        val body = try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) throw ServerException("Сервер ответил ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: ServerException) {
            throw e
        } catch (e: Exception) {
            throw ServerException("Сервер недоступен: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
        val resp = try {
            JSONObject(body).getJSONObject("subsonic-response")
        } catch (e: Exception) {
            throw ServerException("По этому адресу не Navidrome/Subsonic")
        }
        if (resp.optString("status") != "ok") {
            val err = resp.optJSONObject("error")
            throw ServerException(
                when (err?.optInt("code")) {
                    40 -> "Неверный логин или пароль"
                    else -> err?.optString("message")?.ifBlank { null } ?: "Ошибка сервера"
                }
            )
        }
        return resp
    }

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT_NAME = "AkkMusic"
        private const val PAGE = 500

        /** Новый клиент из введённого пароля: считаем токен со свежей солью. */
        fun withPassword(baseUrl: String, user: String, password: String): NavidromeClient {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val salt = bytes.joinToString("") { "%02x".format(it) }
            return NavidromeClient(baseUrl, user, md5(password + salt), salt)
        }

        /**
         * Всё приложение (плейлисты, счётчики, переименования) хранит треки по Long-id.
         * Строковый id сервера превращаем в стабильный отрицательный Long —
         * с положительными id из MediaStore он не пересечётся.
         */
        fun localId(remoteId: String): Long {
            val h = MessageDigest.getInstance("SHA-256").digest(remoteId.toByteArray())
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (h[i].toLong() and 0xFF)
            return v or Long.MIN_VALUE
        }

        private fun normalizeUrl(raw: String): String {
            val u = raw.trim().trimEnd('/')
            return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
