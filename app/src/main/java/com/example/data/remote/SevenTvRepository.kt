package com.example.data.remote

import android.util.LruCache
import com.example.data.model.EmoteSearchPage
import com.example.data.model.EmoteVariant
import com.example.data.model.SevenTvConnection
import com.example.data.model.SevenTvEmote
import com.example.data.model.SevenTvEmoteSet
import com.example.data.model.SevenTvUser
import com.example.data.model.UserSearchPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val isNetwork: Boolean = false) : ApiResult<Nothing>()
}

class SevenTvRepository(private val api: SevenTvApiService) {

    companion object {
        private const val LEGACY_V3_GQL_URL = "https://7tv.io/v3/gql"
        private const val LEGACY_V4_GQL_URL = "https://7tv.io/v4/gql"
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val userSearchCache = LruCache<String, UserSearchPage>(30)
    private val userDetailCache = LruCache<String, SevenTvUser>(30)
    private val emoteSearchCache = LruCache<String, EmoteSearchPage>(50)

    fun clearMemoryCache() {
        userSearchCache.evictAll()
        userDetailCache.evictAll()
        emoteSearchCache.evictAll()
    }

    suspend fun searchStreamers(
        query: String,
        page: Int = 1,
        limit: Int = 30
    ): ApiResult<UserSearchPage> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val cacheKey = "$trimmed|$page|$limit"
        userSearchCache.get(cacheKey)?.let { return@withContext ApiResult.Success(it) }

        try {
            // If user entered a direct 7TV ULID (26 alphanumeric chars), try fetching directly first
            if (page == 1 && trimmed.length == 26 && trimmed.all { it.isLetterOrDigit() }) {
                val directRes = retryCall { api.getUserById(trimmed) }
                if (directRes != null && directRes.isSuccessful) {
                    val body = directRes.body()?.string()
                    if (!body.isNullOrBlank()) {
                        val user = parseRestUser(JSONObject(body))
                        val result = UserSearchPage(listOf(user), page = 1, hasMore = false)
                        userSearchCache.put(cacheKey, result)
                        return@withContext ApiResult.Success(result)
                    }
                }
            }

            // Try v3 GQL with pagination args first
            val gqlV3Paged = JSONObject().apply {
                put(
                    "query",
                    """
                    query SearchUsers(${'$'}query: String!, ${'$'}page: Int, ${'$'}limit: Int) {
                      users(query: ${'$'}query, page: ${'$'}page, limit: ${'$'}limit) {
                        id
                        username
                        display_name
                        avatar_url
                        connections {
                          id
                          platform
                          username
                          display_name
                        }
                      }
                    }
                    """.trimIndent()
                )
                put("variables", JSONObject().apply {
                    put("query", trimmed)
                    put("page", page)
                    put("limit", limit)
                })
            }

            var response = retryCall { api.queryGraphQlV3(gqlV3Paged.toString().toRequestBody(jsonMediaType)) }
            var bodyStr = response?.body()?.string()

            // Fallback if v3 GQL users() does not accept page/limit arguments
            if (response == null || !response.isSuccessful || hasGraphQlErrors(bodyStr, "users")) {
                val gqlV3Simple = JSONObject().apply {
                    put(
                        "query",
                        """
                        query SearchUsers(${'$'}query: String!) {
                          users(query: ${'$'}query) {
                            id
                            username
                            display_name
                            avatar_url
                            connections {
                              id
                              platform
                              username
                              display_name
                            }
                          }
                        }
                        """.trimIndent()
                    )
                    put("variables", JSONObject().apply { put("query", trimmed) })
                }
                response = retryCall { api.queryGraphQlV3(gqlV3Simple.toString().toRequestBody(jsonMediaType)) }
                bodyStr = response?.body()?.string()

                if (response == null || !response.isSuccessful || hasGraphQlErrors(bodyStr, "users")) {
                    val legacyRes = retryCall { api.postGraphQl(LEGACY_V3_GQL_URL, gqlV3Simple.toString().toRequestBody(jsonMediaType)) }
                    val legacyBody = legacyRes?.body()?.string()
                    if (legacyRes != null && legacyRes.isSuccessful && !hasGraphQlErrors(legacyBody, "users")) {
                        response = legacyRes
                        bodyStr = legacyBody
                    }
                }
            }

            if (response != null && response.isSuccessful && !bodyStr.isNullOrBlank() && !hasGraphQlErrors(bodyStr, "users")) {
                val parsed = parseUsersFromGql(JSONObject(bodyStr), page, limit)
                userSearchCache.put(cacheKey, parsed)
                return@withContext ApiResult.Success(parsed)
            }

            // Secondary fallback to v4 GQL search endpoint
            val v4Result = trySearchUsersV4(trimmed, page, limit)
            if (v4Result != null) {
                userSearchCache.put(cacheKey, v4Result)
                return@withContext ApiResult.Success(v4Result)
            }

            ApiResult.Error(extractGraphQlError(response, bodyStr))
        } catch (e: IOException) {
            ApiResult.Error(e.message ?: "Network error", isNetwork = true)
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unexpected error")
        }
    }

    suspend fun getStreamerWithEmoteSets(userId: String, targetSetId: String? = null): ApiResult<SevenTvUser> = withContext(Dispatchers.IO) {
        userDetailCache.get(userId)?.let { cached ->
            if (targetSetId == null || cached.emoteSets.firstOrNull { it.id == targetSetId }?.emotes?.isNotEmpty() == true) {
                return@withContext ApiResult.Success(cached)
            }
        }

        try {
            val response = retryCall { api.getUserById(userId) }
                ?: return@withContext ApiResult.Error("Network error", isNetwork = true)

            if (!response.isSuccessful) {
                return@withContext ApiResult.Error("HTTP ${response.code()}")
            }

            val raw = response.body()?.string()
            if (raw.isNullOrBlank()) {
                return@withContext ApiResult.Error("Empty response")
            }

            val json = JSONObject(raw)
            var user = parseRestUser(json)

            // Register any extra emote_set_ids referenced by connections as metadata shells (fetch lazily when selected)
            val knownSetIds = user.emoteSets.map { it.id }.toMutableSet()
            val extraSetIds = user.connections.mapNotNull { it.emoteSetId }.filter { it.isNotBlank() && it !in knownSetIds }
            if (extraSetIds.isNotEmpty()) {
                val extraShells = extraSetIds.distinct().map { setId ->
                    SevenTvEmoteSet(id = setId, name = "Channel Set", capacity = 0, emotes = emptyList())
                }
                user = user.copy(emoteSets = user.emoteSets + extraShells)
            }

            // Fetch ONLY the active/target set if its emotes were empty (avoid downloading all sets at once!)
            val activeSet = if (targetSetId != null) {
                user.emoteSets.firstOrNull { it.id == targetSetId }
            } else {
                user.emoteSets.firstOrNull()
            }
            if (activeSet != null && activeSet.emotes.isEmpty()) {
                fetchEmoteSetInternal(activeSet.id)?.let { loaded ->
                    val updatedSets = user.emoteSets.map { if (it.id == loaded.id) loaded else it }
                    user = user.copy(emoteSets = updatedSets)
                }
            }

            userDetailCache.put(userId, user)
            ApiResult.Success(user)
        } catch (e: IOException) {
            ApiResult.Error(e.message ?: "Network error", isNetwork = true)
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unexpected error")
        }
    }

    suspend fun loadEmoteSetOnDemand(userId: String, setId: String): ApiResult<SevenTvEmoteSet> = withContext(Dispatchers.IO) {
        try {
            val loaded = fetchEmoteSetInternal(setId)
                ?: return@withContext ApiResult.Error("HTTP 404")
            userDetailCache.get(userId)?.let { cached ->
                val updatedSets = cached.emoteSets.map { if (it.id == loaded.id) loaded else it }
                userDetailCache.put(userId, cached.copy(emoteSets = updatedSets))
            }
            ApiResult.Success(loaded)
        } catch (e: IOException) {
            ApiResult.Error(e.message ?: "Network error", isNetwork = true)
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unexpected error")
        }
    }

    suspend fun searchEmotes(
        query: String,
        page: Int = 1,
        limit: Int = 30
    ): ApiResult<EmoteSearchPage> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val cacheKey = "$trimmed|$page|$limit"
        emoteSearchCache.get(cacheKey)?.let { return@withContext ApiResult.Success(it) }

        try {
            val gqlV3Standard = JSONObject().apply {
                put(
                    "query",
                    """
                    query SearchEmotes(${'$'}query: String!, ${'$'}page: Int, ${'$'}limit: Int) {
                      emotes(query: ${'$'}query, page: ${'$'}page, limit: ${'$'}limit) {
                        count
                        items {
                          id
                          name
                          flags
                          animated
                          owner {
                            id
                            username
                            display_name
                          }
                          host {
                            url
                            files {
                              name
                              static_name
                              width
                              height
                              size
                              format
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent()
                )
                put("variables", JSONObject().apply {
                    put("query", trimmed)
                    put("page", page)
                    put("limit", limit)
                })
            }

            val gqlV3Minimal = JSONObject().apply {
                put(
                    "query",
                    """
                    query SearchEmotesMinimal(${'$'}query: String!, ${'$'}page: Int, ${'$'}limit: Int) {
                      emotes(query: ${'$'}query, page: ${'$'}page, limit: ${'$'}limit) {
                        count
                        items {
                          id
                          name
                          flags
                          animated
                          host {
                            url
                            files {
                              name
                              width
                              height
                              format
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent()
                )
                put("variables", JSONObject().apply {
                    put("query", trimmed)
                    put("page", page)
                    put("limit", limit)
                })
            }

            val gqlV3NoPaging = JSONObject().apply {
                put(
                    "query",
                    """
                    query SearchEmotesSimple(${'$'}query: String!) {
                      emotes(query: ${'$'}query) {
                        count
                        items {
                          id
                          name
                          flags
                          animated
                          host {
                            url
                            files {
                              name
                              width
                              height
                              format
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent()
                )
                put("variables", JSONObject().apply { put("query", trimmed) })
            }

            var response = retryCall { api.queryGraphQlV3(gqlV3Standard.toString().toRequestBody(jsonMediaType)) }
            var bodyStr = response?.body()?.string()

            if (response == null || !response.isSuccessful || hasGraphQlErrors(bodyStr, "emotes")) {
                response = retryCall { api.queryGraphQlV3(gqlV3Minimal.toString().toRequestBody(jsonMediaType)) }
                bodyStr = response?.body()?.string()
            }

            if (response == null || !response.isSuccessful || hasGraphQlErrors(bodyStr, "emotes")) {
                response = retryCall { api.queryGraphQlV3(gqlV3NoPaging.toString().toRequestBody(jsonMediaType)) }
                bodyStr = response?.body()?.string()
            }

            if (response == null || !response.isSuccessful || hasGraphQlErrors(bodyStr, "emotes")) {
                val legacyRes = retryCall { api.postGraphQl(LEGACY_V3_GQL_URL, gqlV3Minimal.toString().toRequestBody(jsonMediaType)) }
                val legacyBody = legacyRes?.body()?.string()
                if (legacyRes != null && legacyRes.isSuccessful && !hasGraphQlErrors(legacyBody, "emotes")) {
                    response = legacyRes
                    bodyStr = legacyBody
                }
            }

            if (response != null && response.isSuccessful && !bodyStr.isNullOrBlank() && !hasGraphQlErrors(bodyStr, "emotes")) {
                val parsed = parseEmotesFromGql(JSONObject(bodyStr), page, limit)
                emoteSearchCache.put(cacheKey, parsed)
                return@withContext ApiResult.Success(parsed)
            }

            // Secondary fallback to v4 GQL if v3 rejects or is unavailable
            val v4Page = trySearchEmotesV4(trimmed, page, limit)
            if (v4Page != null) {
                emoteSearchCache.put(cacheKey, v4Page)
                return@withContext ApiResult.Success(v4Page)
            }

            ApiResult.Error(extractGraphQlError(response, bodyStr))
        } catch (e: IOException) {
            ApiResult.Error(e.message ?: "Network error", isNetwork = true)
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Unexpected error")
        }
    }

    private suspend fun fetchEmoteSetInternal(setId: String): SevenTvEmoteSet? {
        return try {
            val res = retryCall { api.getEmoteSetById(setId) } ?: return null
            if (!res.isSuccessful) return null
            val raw = res.body()?.string() ?: return null
            parseEmoteSet(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun trySearchUsersV4(query: String, page: Int, limit: Int): UserSearchPage? {
        return try {
            val gql = JSONObject().apply {
                put(
                    "query",
                    """
                    query SearchUsersV4(${'$'}query: String!, ${'$'}page: Int, ${'$'}perPage: Int) {
                      users {
                        search(query: ${'$'}query, page: ${'$'}page, perPage: ${'$'}perPage) {
                          items {
                            id
                            mainConnection {
                              platform
                              platformId
                              platformUsername
                              platformDisplayName
                              platformAvatarUrl
                            }
                          }
                          totalCount
                        }
                      }
                    }
                    """.trimIndent()
                )
                put("variables", JSONObject().apply {
                    put("query", query)
                    put("page", page)
                    put("perPage", limit)
                })
            }
            val res = retryCall { api.queryGraphQlV4(gql.toString().toRequestBody(jsonMediaType)) } ?: return null
            val raw = res.body()?.string() ?: return null
            if (!res.isSuccessful || hasGraphQlErrors(raw, "users")) return null
            val data = JSONObject(raw).optJSONObject("data") ?: return null
            val usersObj = data.optJSONObject("users") ?: return null
            val searchObj = usersObj.optJSONObject("search") ?: return null
            val items = searchObj.optJSONArray("items") ?: JSONArray()
            val totalCount = searchObj.optInt("totalCount", items.length())
            val list = buildList {
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val conn = obj.optJSONObject("mainConnection")
                    val username = conn?.optString("platformUsername").orEmpty().ifBlank { id }
                    val displayName = conn?.optString("platformDisplayName").orEmpty().ifBlank { username }
                    val avatar = normalizeUrl(conn?.optString("platformAvatarUrl"))
                    val connections = if (conn != null) {
                        listOf(
                            SevenTvConnection(
                                id = conn.optString("platformId"),
                                platform = conn.optString("platform"),
                                username = username,
                                displayName = displayName
                            )
                        )
                    } else emptyList()
                    add(SevenTvUser(id = id, username = username, displayName = displayName, avatarUrl = avatar, connections = connections))
                }
            }
            UserSearchPage(list, page, (page * limit) < totalCount)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun trySearchEmotesV4(query: String, page: Int, limit: Int): EmoteSearchPage? {
        return try {
            val gqlEmotesSearch = JSONObject().apply {
                put(
                    "query",
                    """
                    query SearchEmotesV4(${'$'}query: String!, ${'$'}page: Int, ${'$'}perPage: Int) {
                      emotes {
                        search(query: ${'$'}query, page: ${'$'}page, perPage: ${'$'}perPage) {
                          items {
                            id
                            defaultName
                            flags {
                              animated
                            }
                            images {
                              url
                              mime
                              size
                              scale
                              width
                              height
                              frameCount
                            }
                          }
                          totalCount
                        }
                      }
                    }
                    """.trimIndent()
                )
                put("variables", JSONObject().apply {
                    put("query", query)
                    put("page", page)
                    put("perPage", limit)
                })
            }

            val gqlSearchAll = JSONObject().apply {
                put(
                    "query",
                    """
                    query SearchAllEmotesV4(${'$'}query: String!, ${'$'}page: Int, ${'$'}perPage: Int) {
                      search {
                        all(query: ${'$'}query, page: ${'$'}page, perPage: ${'$'}perPage) {
                          emotes {
                            items {
                              id
                              defaultName
                              flags {
                                animated
                              }
                              images {
                                url
                                mime
                                size
                                scale
                                width
                                height
                                frameCount
                              }
                            }
                            totalCount
                          }
                        }
                      }
                    }
                    """.trimIndent()
                )
                put("variables", JSONObject().apply {
                    put("query", query)
                    put("page", page)
                    put("perPage", limit)
                })
            }

            parseV4EmotesResponse(
                retryCall { api.queryGraphQlV4(gqlEmotesSearch.toString().toRequestBody(jsonMediaType)) },
                page,
                limit
            ) ?: parseV4EmotesResponse(
                retryCall { api.queryGraphQlV4(gqlSearchAll.toString().toRequestBody(jsonMediaType)) },
                page,
                limit
            ) ?: parseV4EmotesResponse(
                retryCall { api.postGraphQl(LEGACY_V4_GQL_URL, gqlEmotesSearch.toString().toRequestBody(jsonMediaType)) },
                page,
                limit
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseV4EmotesResponse(
        res: retrofit2.Response<okhttp3.ResponseBody>?,
        page: Int,
        limit: Int
    ): EmoteSearchPage? {
        val raw = res?.body()?.string() ?: return null
        if (!res.isSuccessful) return null
        return try {
            val root = JSONObject(raw)
            val data = root.optJSONObject("data") ?: return null
            val searchObj = data.optJSONObject("emotes")?.optJSONObject("search")
                ?: data.optJSONObject("search")?.optJSONObject("all")?.optJSONObject("emotes")
                ?: return null
            val items = searchObj.optJSONArray("items") ?: JSONArray()
            val totalCount = searchObj.optInt("totalCount", items.length())
            val list = buildList {
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank()) continue
                    val name = obj.optString("defaultName").ifBlank { obj.optString("name").ifBlank { id } }
                    val flagsObj = obj.optJSONObject("flags")
                    val animated = flagsObj?.optBoolean("animated", false) ?: obj.optBoolean("animated", false)
                    val images = obj.optJSONArray("images") ?: JSONArray()
                    val variants = buildList {
                        for (j in 0 until images.length()) {
                            val img = images.optJSONObject(j) ?: continue
                            val url = normalizeUrl(img.optString("url")) ?: continue
                            val mime = img.optString("mime")
                            val format = mime.substringAfter('/').uppercase()
                            add(
                                EmoteVariant(
                                    name = "${img.optInt("scale", 1)}x",
                                    staticName = null,
                                    format = format,
                                    width = img.optInt("width"),
                                    height = img.optInt("height"),
                                    sizeBytes = img.optLong("size"),
                                    url = url
                                )
                            )
                        }
                    }
                    val (preview, highRes, fallbacks) = selectUrlsFromVariants(id, animated, variants)
                    val bestFormat = variants.firstOrNull { it.url == highRes }?.format.orEmpty()
                    add(
                        SevenTvEmote(
                            id = id,
                            name = name,
                            animated = animated,
                            flags = 0,
                            ownerName = null,
                            previewUrl = preview,
                            highResUrl = highRes,
                            fallbackUrls = fallbacks,
                            updatedAt = 0L,
                            originalFormat = bestFormat
                        )
                    )
                }
            }
            EmoteSearchPage(list, totalCount, page, (page * limit) < totalCount)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseUsersFromGql(root: JSONObject, page: Int, limit: Int): UserSearchPage {
        val data = root.optJSONObject("data") ?: return UserSearchPage(emptyList(), page, false)
        val usersNode = data.opt("users")
        val usersArray: JSONArray
        var hasMore = false

        when (usersNode) {
            null -> return UserSearchPage(emptyList(), page, false)
            is JSONArray -> {
                usersArray = usersNode
                // If v3 returned full list without server pagination, slice client-side or check size
                if (usersNode.length() > limit) {
                    val from = (page - 1) * limit
                    val to = minOf(from + limit, usersNode.length())
                    val sliced = mutableListOf<SevenTvUser>()
                    for (i in from until to) {
                        usersNode.optJSONObject(i)?.let { sliced.add(parseGqlUser(it)) }
                    }
                    return UserSearchPage(sliced, page, to < usersNode.length())
                }
                hasMore = usersNode.length() >= limit
            }
            is JSONObject -> {
                usersArray = usersNode.optJSONArray("items") ?: JSONArray()
                val total = usersNode.optInt("total", usersNode.optInt("count", 0))
                hasMore = if (total > 0) (page * limit) < total else usersArray.length() >= limit
            }
            else -> return UserSearchPage(emptyList(), page, false)
        }

        val users = buildList {
            for (i in 0 until usersArray.length()) {
                usersArray.optJSONObject(i)?.let { add(parseGqlUser(it)) }
            }
        }
        return UserSearchPage(users, page, hasMore)
    }

    private fun parseGqlUser(obj: JSONObject): SevenTvUser {
        val id = obj.optString("id")
        val username = obj.optString("username")
        val displayName = obj.optString("display_name").ifBlank { username }
        val avatar = normalizeUrl(obj.optString("avatar_url"))
        val connsJson = obj.optJSONArray("connections") ?: JSONArray()
        val conns = buildList {
            for (i in 0 until connsJson.length()) {
                val c = connsJson.optJSONObject(i) ?: continue
                add(
                    SevenTvConnection(
                        id = c.optString("id"),
                        platform = c.optString("platform"),
                        username = c.optString("username"),
                        displayName = c.optString("display_name"),
                        emoteSetId = c.optString("emote_set_id").ifBlank { null }
                    )
                )
            }
        }
        return SevenTvUser(id, username, displayName, avatar, conns, emptyList())
    }

    private fun parseRestUser(obj: JSONObject): SevenTvUser {
        val id = obj.optString("id")
        val username = obj.optString("username")
        val displayName = obj.optString("display_name").ifBlank { username }
        val avatar = normalizeUrl(obj.optString("avatar_url"))

        val connsJson = obj.optJSONArray("connections") ?: JSONArray()
        val conns = buildList {
            for (i in 0 until connsJson.length()) {
                val c = connsJson.optJSONObject(i) ?: continue
                add(
                    SevenTvConnection(
                        id = c.optString("id"),
                        platform = c.optString("platform"),
                        username = c.optString("username"),
                        displayName = c.optString("display_name"),
                        emoteSetId = c.optString("emote_set_id").ifBlank { null }
                    )
                )
            }
        }

        val setsJson = obj.optJSONArray("emote_sets") ?: JSONArray()
        val sets = buildList {
            for (i in 0 until setsJson.length()) {
                val s = setsJson.optJSONObject(i) ?: continue
                add(parseEmoteSet(s))
            }
        }

        return SevenTvUser(id, username, displayName, avatar, conns, sets)
    }

    private fun parseEmoteSet(obj: JSONObject): SevenTvEmoteSet {
        val setId = obj.optString("id")
        val setName = obj.optString("name").ifBlank { "Emotes" }
        val capacity = obj.optInt("capacity", 0)
        val emotesArr = obj.optJSONArray("emotes") ?: JSONArray()
        val emotes = buildList {
            for (i in 0 until emotesArr.length()) {
                val active = emotesArr.optJSONObject(i) ?: continue
                val emoteId = active.optString("id")
                val aliasName = active.optString("name")
                val data = active.optJSONObject("data") ?: active
                val parsed = parseEmoteObject(
                    obj = data,
                    overrideId = emoteId.ifBlank { null },
                    overrideName = aliasName.ifBlank { null },
                    sourceSetId = setId,
                    sourceSetName = setName
                )
                if (parsed != null) add(parsed)
            }
        }
        return SevenTvEmoteSet(setId, setName, capacity, emotes)
    }

    private fun parseEmotesFromGql(root: JSONObject, page: Int, limit: Int): EmoteSearchPage {
        val data = root.optJSONObject("data") ?: return EmoteSearchPage(emptyList(), 0, page, false)
        val emotesObj = data.optJSONObject("emotes") ?: return EmoteSearchPage(emptyList(), 0, page, false)
        val total = emotesObj.optInt("count", emotesObj.optInt("total", 0))
        val itemsArr = emotesObj.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                parseEmoteObject(obj)?.let { add(it) }
            }
        }
        if (items.size > limit) {
            val from = (page - 1) * limit
            val to = minOf(from + limit, items.size)
            val sliced = if (from < items.size) items.subList(from, to) else emptyList()
            val effectiveTotal = maxOf(total, items.size)
            return EmoteSearchPage(sliced, effectiveTotal, page, to < items.size)
        }
        val hasMore = if (total > 0) (page * limit) < total else items.size >= limit
        return EmoteSearchPage(items, total, page, hasMore)
    }

    private fun parseEmoteObject(
        obj: JSONObject,
        overrideId: String? = null,
        overrideName: String? = null,
        sourceSetId: String? = null,
        sourceSetName: String? = null
    ): SevenTvEmote? {
        val id = overrideId ?: obj.optString("id")
        if (id.isBlank()) return null
        val name = overrideName ?: obj.optString("name").ifBlank { id }
        val flags = obj.optInt("flags", 0)
        val animated = obj.optBoolean("animated", false)
        val updatedAt = obj.optLong("updated_at", 0L)
        val ownerObj = obj.optJSONObject("owner")
        val ownerName = ownerObj?.optString("display_name")?.ifBlank { ownerObj.optString("username") }

        val host = obj.optJSONObject("host")
        val baseHostUrl = normalizeUrl(host?.optString("url")) ?: "https://cdn.7tv.app/emote/$id"
        val filesArr = host?.optJSONArray("files") ?: JSONArray()

        val variants = buildList {
            for (i in 0 until filesArr.length()) {
                val f = filesArr.optJSONObject(i) ?: continue
                val fileName = f.optString("name")
                if (fileName.isBlank()) continue
                val staticName = f.optString("static_name").ifBlank { null }
                val format = f.optString("format").ifBlank {
                    fileName.substringAfterLast('.', "").uppercase()
                }
                val cleanBase = baseHostUrl.trimEnd('/')
                add(
                    EmoteVariant(
                        name = fileName,
                        staticName = staticName,
                        format = format,
                        width = f.optInt("width", 0),
                        height = f.optInt("height", 0),
                        sizeBytes = f.optLong("size", 0L),
                        url = "$cleanBase/$fileName"
                    )
                )
            }
        }

        val (preview, highRes, fallbacks) = selectUrlsFromVariants(id, animated, variants)
        val highResFormat = variants.firstOrNull { it.url == highRes }?.format
            ?: highRes.substringAfterLast('.', "webp").uppercase()

        return SevenTvEmote(
            id = id,
            name = name,
            animated = animated,
            flags = flags,
            ownerName = ownerName,
            previewUrl = preview,
            highResUrl = highRes,
            fallbackUrls = fallbacks,
            sourceSetId = sourceSetId,
            sourceSetName = sourceSetName,
            updatedAt = updatedAt,
            originalFormat = highResFormat
        )
    }

    private fun selectUrlsFromVariants(
        emoteId: String,
        animated: Boolean,
        variants: List<EmoteVariant>
    ): Triple<String, String, List<String>> {
        val defaultPreview = "https://cdn.7tv.app/emote/$emoteId/2x.webp"
        val defaultHigh = "https://cdn.7tv.app/emote/$emoteId/4x.webp"

        if (variants.isEmpty()) {
            return Triple(
                defaultPreview,
                defaultHigh,
                listOf(
                    "https://cdn.7tv.app/emote/$emoteId/4x.gif",
                    "https://cdn.7tv.app/emote/$emoteId/3x.webp",
                    "https://cdn.7tv.app/emote/$emoteId/4x.png",
                    "https://cdn.7tv.app/emote/$emoteId/2x.webp"
                )
            )
        }

        // Preview: prefer 2x.webp -> 1x.webp -> 2x.gif -> 2x.png -> any webp/gif
        val preview = variants.firstOrNull { it.name == "2x.webp" }?.url
            ?: variants.firstOrNull { it.name == "1x.webp" }?.url
            ?: variants.firstOrNull { it.name == "2x.gif" }?.url
            ?: variants.firstOrNull { it.format.equals("WEBP", true) }?.url
            ?: variants.firstOrNull { it.format.equals("GIF", true) }?.url
            ?: variants.first().url

        // High-res for conversion: prioritize WEBP (4x->3x->2x->1x), then GIF (for animated), then PNG, then AVIF
        val preferredOrder = if (animated) {
            listOf("4x.webp", "3x.webp", "4x.gif", "3x.gif", "2x.webp", "2x.gif", "1x.webp", "1x.gif", "4x.avif", "4x.png")
        } else {
            listOf("4x.webp", "4x.png", "3x.webp", "3x.png", "2x.webp", "2x.png", "1x.webp", "1x.png", "4x.avif")
        }

        val orderedUrls = mutableListOf<String>()
        for (target in preferredOrder) {
            variants.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let {
                orderedUrls.add(it.url)
            }
        }
        variants.sortedByDescending { it.width * it.height }.forEach { v ->
            if (v.url !in orderedUrls) orderedUrls.add(v.url)
        }

        val highRes = orderedUrls.firstOrNull() ?: defaultHigh
        val fallbacks = orderedUrls.drop(1)
        return Triple(preview, highRes, fallbacks)
    }

    private fun normalizeUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") -> raw.replaceFirst("http://", "https://")
            raw.startsWith("https://") -> raw
            raw.startsWith("https:") -> raw.replaceFirst("https:", "https://")
            else -> "https://$raw"
        }
    }

    private fun hasGraphQlErrors(body: String?, expectedDataField: String? = null): Boolean {
        if (body.isNullOrBlank()) return true
        return try {
            val obj = JSONObject(body)
            val data = obj.optJSONObject("data") ?: return true
            if (expectedDataField != null && data.isNull(expectedDataField)) {
                return true
            }
            val errors = obj.optJSONArray("errors")
            errors != null && errors.length() > 0 && obj.isNull("data")
        } catch (_: Exception) {
            true
        }
    }

    private fun extractGraphQlError(
        response: retrofit2.Response<okhttp3.ResponseBody>?,
        bodyStr: String?
    ): String {
        if (response == null) return "GQL"
        if (!response.isSuccessful) return "HTTP ${response.code()}"
        if (!bodyStr.isNullOrBlank()) {
            try {
                val obj = JSONObject(bodyStr)
                val errors = obj.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    val msg = errors.optJSONObject(0)?.optString("message").orEmpty()
                    if (msg.isNotBlank()) return msg
                }
            } catch (_: Exception) {
                return "Invalid response (HTTP ${response.code()})"
            }
        }
        return "HTTP ${response.code()}"
    }

    private suspend fun <T> retryCall(
        maxAttempts: Int = 3,
        block: suspend () -> retrofit2.Response<T>
    ): retrofit2.Response<T>? {
        var lastException: Exception? = null
        var delayMs = 350L
        repeat(maxAttempts) { attempt ->
            try {
                val res = block()
                if (res.isSuccessful || res.code() in 400..499) {
                    return res
                }
            } catch (e: IOException) {
                lastException = e
            }
            if (attempt < maxAttempts - 1) {
                delay(delayMs)
                delayMs *= 2
            }
        }
        if (lastException != null) throw lastException
        return null
    }
}
