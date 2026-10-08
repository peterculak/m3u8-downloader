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
    // Everything shown in the UI is Slovak. (Czech titles are looked up separately — see czechTitle — because
    // prehraj.to uploads are usually named in Czech.)
    private const val LANG = "sk-SK"

    enum class Category(val path: String) { TRENDING("trending"), POPULAR("popular"), TOP_RATED("top_rated") }

    val isConfigured: Boolean get() = BuildConfig.TMDB_API_KEY.isNotEmpty()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject {
        if (!isConfigured) throw Exception("TMDB API key missing (add tmdb.api.key to local.properties)")
        val url = StringBuilder("$BASE$path?api_key=${BuildConfig.TMDB_API_KEY}&language=${params["language"] ?: LANG}")
        params.filterKeys { it != "language" }.forEach { (k, v) -> url.append('&').append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8")) }
        val req = Request.Builder().url(url.toString()).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("TMDB HTTP ${resp.code}")
            JsonParser.parseString(resp.body?.string() ?: "{}").asJsonObject
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""

    private fun parseItem(o: JsonObject, type: String): TmdbItem? {
        val isTv = type == "tv"
        val title = o.str(if (isTv) "name" else "title")
        if (title.isEmpty()) return null
        return TmdbItem(
            id = o.get("id").asInt,
            mediaType = type,
            title = title,
            originalTitle = o.str(if (isTv) "original_name" else "original_title").ifEmpty { title },
            year = o.str(if (isTv) "first_air_date" else "release_date").take(4),
            posterPath = o.str("poster_path").ifEmpty { null },
            backdropPath = o.str("backdrop_path").ifEmpty { null },
            overview = o.str("overview"),
            rating = o.get("vote_average")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
        )
    }

    private fun parseItems(json: JsonObject, type: String): List<TmdbItem> =
        json.getAsJsonArray("results")?.mapNotNull { parseItem(it.asJsonObject, type) } ?: emptyList()

    /** Movies and series matching [query] (people are dropped), most relevant first. */
    suspend fun search(query: String, page: Int): TmdbSearchPage = withContext(Dispatchers.IO) {
        val json = get("/search/multi", mapOf("query" to query, "page" to "$page", "include_adult" to "false"))
        val items = json.getAsJsonArray("results")?.mapNotNull {
            val o = it.asJsonObject
            val type = o.str("media_type")
            if (type == "movie" || type == "tv") parseItem(o, type) else null
        } ?: emptyList()
        TmdbSearchPage(items, page, json.get("total_pages")?.takeIf { !it.isJsonNull }?.asInt ?: 1)
    }

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
        get("/genre/$type/list", mapOf("language" to "sk-SK")).getAsJsonArray("genres")?.map {
            val o = it.asJsonObject
            TmdbGenre(o.get("id").asInt, o.str("name"))
        } ?: emptyList()
    }

    /** Runtime, genres and a Slovak description for the detail page. */
    suspend fun details(type: String, id: Int): TmdbDetails = withContext(Dispatchers.IO) {
        val o = get("/$type/$id", mapOf("language" to "sk-SK"))
        TmdbDetails(
            genres = o.getAsJsonArray("genres")?.map { it.asJsonObject.str("name") } ?: emptyList(),
            runtimeMinutes = o.get("runtime")?.takeIf { !it.isJsonNull }?.asInt
                ?: o.getAsJsonArray("episode_run_time")?.firstOrNull()?.asInt ?: 0,
            tagline = o.str("tagline"),
            overview = o.str("overview")
        )
    }

    /** Czech title, used only to widen the prehraj.to search. */
    suspend fun czechTitle(type: String, id: Int): String = withContext(Dispatchers.IO) {
        val o = get("/$type/$id", mapOf("language" to "cs-CZ"))
        o.str(if (type == "tv") "name" else "title")
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
