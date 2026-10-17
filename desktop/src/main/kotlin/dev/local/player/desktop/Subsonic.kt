package dev.local.player.desktop

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** Откуда брать музыку: только папка на ПК, только сервер или всё вместе. */
enum class LibrarySource {
    LOCAL, SERVER, ALL;

    val usesLocal: Boolean get() = this != SERVER
    val usesServer: Boolean get() = this != LOCAL

    companion object {
        fun fromName(name: String?): LibrarySource = entries.find { it.name == name } ?: LOCAL
    }
}

class ServerException(message: String) : Exception(message)

/**
 * Клиент Subsonic API (его реализует Navidrome) для JVM. Перенос с Android:
 * вместо android.net.Uri — обычные строки, остальное без изменений.
 * Документация: https://opensubsonic.netlify.app/docs/
 */
class SubsonicClient(
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

    /** Пинг до сервера в миллисекундах по уже открытому соединению. */
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
                val sid = s.getString("id")
                result += Track(
                    id = localId(sid),
                    title = s.optString("title").ifBlank { "Без названия" },
                    artist = s.optString("artist").ifBlank { "Неизвестный исполнитель" },
                    album = s.optString("album").ifBlank { "Неизвестный альбом" },
                    durationMs = s.optLong("duration") * 1000,
                    trackNo = s.optInt("discNumber") * 1000 + s.optInt("track"),
                    remote = true,
                    streamUrl = streamUrl(sid),
                    coverUrl = s.optString("coverArt").takeIf { it.isNotEmpty() }?.let { coverUrl(it) },
                )
            }
            if (songs.length() < PAGE) break
            offset += PAGE
        }
        return result
    }

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
        private const val CLIENT_NAME = "AkkMusicDesktop"
        private const val PAGE = 500

        /** Новый клиент из введённого пароля: токен = md5(пароль + свежая соль). */
        fun withPassword(baseUrl: String, user: String, password: String): SubsonicClient {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            val salt = bytes.joinToString("") { "%02x".format(it) }
            return SubsonicClient(baseUrl, user, md5(password + salt), salt)
        }

        /** Строковый id сервера → стабильный отрицательный Long (не пересечётся с локальными). */
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
