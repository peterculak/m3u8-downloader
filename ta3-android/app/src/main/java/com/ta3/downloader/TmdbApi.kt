package com.ta3.downloader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Thin TMDB v3 client used to browse movies/series in the Prehraj tab. */
object TmdbApi {
    private const val BASE = "https://api.themoviedb.org/3"
    // Czech titles match prehraj.to uploads far better than English ones.
    private const val LANG = "cs-CZ"

    enum class Category(val path: String) { TRENDING("trending"), POPULAR("popular"), TOP_RATED("top_rated") }

    val isConfigured: Boolean get() = BuildConfig.TMDB_API_KEY.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        if (!isConfigured) throw Exception("TMDB API key missing (add tmdb.api.key to local.properties)")
        val url = StringBuilder("$BASE$path?api_key=${BuildConfig.TMDB_API_KEY}&language=$LANG")
        params.forEach { (k, v) -> url.append('&').append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8")) }
        val req = Request.Builder().url(url.toString()).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("TMDB HTTP ${resp.code}")
            JsonParser.parseString(resp.body?.string() ?: "{}").asJsonObject
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun parseItems(json: JsonObject, type: String): List<TmdbItem> =
        json.getAsJsonArray("results")?.mapNotNull { el ->
            val o = el.asJsonObject
            val isTv = type == "tv"
            val title = o.str(if (isTv) "name" else "title")
            if (title.isEmpty()) return@mapNotNull null
            TmdbItem(
                id = o.get("id").asInt,
                mediaType = type,
                title = title,
                originalTitle = o.str(if (isTv) "original_name" else "original_title").ifEmpty { title },
                year = o.str(if (isTv) "first_air_date" else "release_date").take(4),
                posterPath = o.str("poster_path").ifEmpty { null },
                overview = o.str("overview"),
                rating = o.get("vote_average")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
            )
        } ?: emptyList()

    suspend fun list(type: String, category: Category, page: Int = 1): List<TmdbItem> =
        withContext(Dispatchers.IO) {
            val path = if (category == Category.TRENDING) "/trending/$type/week" else "/$type/${category.path}"
            parseItems(get(path, mapOf("page" to "$page")), type)
        }

    suspend fun byGenre(type: String, genreId: Int, page: Int): List<TmdbItem> =
        withContext(Dispatchers.IO) {
            parseItems(
                get("/discover/$type", mapOf(
                    "with_genres" to "$genreId", "sort_by" to "popularity.desc", "page" to "$page",
                    "vote_count.gte" to "20"
                )), type
            )
        }

    suspend fun genres(type: String): List<TmdbGenre> = withContext(Dispatchers.IO) {
        get("/genre/$type/list").getAsJsonArray("genres")?.map {
            val o = it.asJsonObject
            TmdbGenre(o.get("id").asInt, o.str("name"))
        } ?: emptyList()
    }

    suspend fun seasons(tvId: Int): List<TmdbSeason> = withContext(Dispatchers.IO) {
        get("/tv/$tvId").getAsJsonArray("seasons")?.map { it.asJsonObject }
            ?.filter { it.get("season_number").asInt > 0 }   // skip "Specials"
            ?.map { TmdbSeason(it.get("season_number").asInt, it.get("episode_count")?.asInt ?: 0) }
            ?: emptyList()
    }

    suspend fun episodes(tvId: Int, season: Int): List<TmdbEpisode> = withContext(Dispatchers.IO) {
        get("/tv/$tvId/season/$season").getAsJsonArray("episodes")?.map {
            val o = it.asJsonObject
            TmdbEpisode(o.get("episode_number").asInt, o.str("name"))
        } ?: emptyList()
    }
}
