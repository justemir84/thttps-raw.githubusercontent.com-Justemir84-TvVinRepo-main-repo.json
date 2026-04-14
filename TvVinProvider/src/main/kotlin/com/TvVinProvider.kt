package com.tvvin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Log

/*
 * TV.vin CloudStream Eklentisi
 *
 * API endpoint'leri büyük ihtimalle şunlardır:
 *   POST /api/login         → { phone, password }    → token / cookie
 *   GET  /api/channels      → kanal listesi
 *   GET  /api/channel/{id}  → kanal detayı + stream url
 *
 * Eğer çalışmazsa tarayıcıda F12 → Network → XHR sekmesini aç,
 * siteye giriş yap ve kanallardan birine tıkla. Gelen isteklere bakarak
 * aşağıdaki LOGIN_ENDPOINT ve CHANNELS_ENDPOINT'i güncelle.
 */

class TvVinProvider : MainAPI() {

    override var mainUrl    = "https://tv.vin"
    override var name       = "TV.vin"
    override val hasMainPage = true
    override var lang       = "tr"
    override val supportedTypes = setOf(TvType.Live)

    // ─── Ayarlar ──────────────────────────────────────────────────────────────
    private val phone    get() = settingsForProvider.getString("phone",    "")?.trim() ?: ""
    private val password get() = settingsForProvider.getString("password", "")?.trim() ?: ""

    // Oturum token'ı bellekte saklanır (uygulama yeniden başlayana kadar)
    private var authToken: String?  = null
    private var sessionId: String?  = null

    companion object {
        // ⚠️ Eğer bunlar farklıysa aşağıyı güncelle ─────────────────────────
        const val LOGIN_ENDPOINT    = "/api/login"
        const val CHANNELS_ENDPOINT = "/api/channels"

        // Alternatif olarak deneyebilirsin:
        // const val LOGIN_ENDPOINT    = "/login"
        // const val CHANNELS_ENDPOINT = "/channels"
        // ─────────────────────────────────────────────────────────────────────
    }

    // ─── Data Sınıfları ───────────────────────────────────────────────────────

    data class LoginResponse(
        @JsonProperty("token")      val token:   String? = null,
        @JsonProperty("auth_token") val authToken: String? = null,
        @JsonProperty("access_token") val accessToken: String? = null,
        @JsonProperty("session_id") val sessionId: String? = null,
        @JsonProperty("success")    val success: Boolean? = null,
        @JsonProperty("status")     val status:  Int? = null
    )

    data class ChannelItem(
        @JsonProperty("id")       val id:     Any? = null,
        @JsonProperty("name")     val name:   String? = null,
        @JsonProperty("title")    val title:  String? = null,
        @JsonProperty("slug")     val slug:   String? = null,
        @JsonProperty("url")      val url:    String? = null,
        @JsonProperty("logo")     val logo:   String? = null,
        @JsonProperty("image")    val image:  String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("stream_url") val streamUrl: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("group")    val group:  String? = null
    )

    data class ChannelListResponse(
        @JsonProperty("channels")  val channels: List<ChannelItem>? = null,
        @JsonProperty("data")      val data:     List<ChannelItem>? = null,
        @JsonProperty("items")     val items:    List<ChannelItem>? = null,
        @JsonProperty("results")   val results:  List<ChannelItem>? = null
    )

    data class StreamResponse(
        @JsonProperty("stream_url") val streamUrl: String? = null,
        @JsonProperty("url")        val url:       String? = null,
        @JsonProperty("hls_url")    val hlsUrl:    String? = null,
        @JsonProperty("m3u8")       val m3u8:      String? = null,
        @JsonProperty("source")     val source:    String? = null
    )

    // ─── Oturum Açma ──────────────────────────────────────────────────────────

    private suspend fun login(): Boolean {
        if (phone.isEmpty() || password.isEmpty()) {
            throw ErrorLoadingException(
                "Lütfen TV.vin kullanıcı bilgilerini eklenti ayarlarından girin (Ayarlar → TV.vin)."
            )
        }

        Log.d("TvVin", "Giriş yapılıyor: $phone")

        try {
            // Yöntem 1: JSON body ile POST
            val response = app.post(
                url = "$mainUrl$LOGIN_ENDPOINT",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept"       to "application/json",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                requestBody = mapOf(
                    "phone"    to phone,
                    "password" to password
                ).toJson().toRequestBody()
            )

            if (response.isSuccessful) {
                val parsed = response.parsedSafe<LoginResponse>()

                // Token'ı bul (farklı isimlerde gelebilir)
                authToken = parsed?.token
                    ?: parsed?.authToken
                    ?: parsed?.accessToken

                // Cookie tabanlı oturum varsa onu al
                sessionId = response.cookies["session_id"]
                    ?: response.cookies["PHPSESSID"]
                    ?: response.cookies["laravel_session"]
                    ?: response.cookies["ci_session"]

                val success = authToken != null || sessionId != null ||
                              (parsed?.success == true) || (parsed?.status == 1)

                Log.d("TvVin", "Giriş sonucu: success=$success, token=$authToken, session=$sessionId")
                return success
            }

            // Yöntem 2: Form data ile dene (bazı siteler form ister)
            val formResponse = app.post(
                url = "$mainUrl$LOGIN_ENDPOINT",
                data = mapOf(
                    "phone"    to phone,
                    "password" to password
                ),
                headers = mapOf(
                    "Accept" to "application/json, text/html, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )

            sessionId = formResponse.cookies.values.firstOrNull()
            return formResponse.isSuccessful

        } catch (e: Exception) {
            Log.e("TvVin", "Giriş hatası: ${e.message}")
            return false
        }
    }

    /** Her istekte kullanılacak header ve cookie'leri döner */
    private fun authHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "Accept"           to "application/json, text/html, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer"          to mainUrl
        )
        authToken?.let { headers["Authorization"] = "Bearer $it" }
        return headers
    }

    private fun authCookies(): Map<String, String> {
        return sessionId?.let { mapOf("session_id" to it) } ?: emptyMap()
    }

    // ─── Ana Sayfa ────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // İlk yüklemede giriş yap
        if (authToken == null && sessionId == null) {
            val ok = login()
            if (!ok) throw ErrorLoadingException("TV.vin'e giriş yapılamadı.")
        }

        val response = app.get(
            url     = "$mainUrl$CHANNELS_ENDPOINT",
            headers = authHeaders(),
            cookies = authCookies()
        )

        // Oturum süresi dolmuşsa yeniden giriş yap
        if (response.code == 401 || response.code == 403) {
            authToken = null; sessionId = null
            login()
            return getMainPage(page, request)
        }

        val parsed = response.parsedSafe<ChannelListResponse>()
        val rawList = parsed?.channels ?: parsed?.data ?: parsed?.items ?: parsed?.results

        if (rawList == null) {
            Log.e("TvVin", "Kanal listesi parse edilemedi. Ham cevap: ${response.text.take(500)}")
            throw ErrorLoadingException("Kanal listesi alınamadı.")
        }

        // Kategorilere göre grupla
        val grouped = rawList.groupBy { it.category ?: it.group ?: "Kanallar" }

        val homePages = grouped.map { (category, channels) ->
            val items = channels.mapNotNull { channel ->
                val channelName = channel.name ?: channel.title ?: return@mapNotNull null
                val channelUrl  = buildChannelUrl(channel)

                newLiveSearchResponse(
                    name      = channelName,
                    url       = channelUrl,
                    type      = TvType.Live
                ) {
                    this.posterUrl = channel.logo ?: channel.image ?: channel.thumbnail
                }
            }
            HomePageList(category, items, isHorizontalImages = true)
        }

        return HomePageResponse(homePages)
    }

    private fun buildChannelUrl(channel: ChannelItem): String {
        return when {
            channel.streamUrl != null -> channel.streamUrl
            channel.url       != null -> channel.url
            channel.slug      != null -> "$mainUrl/${channel.slug}"
            channel.id        != null -> "$mainUrl/channel/${channel.id}"
            else -> mainUrl
        }
    }

    // ─── Arama ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        if (authToken == null && sessionId == null) login()

        // Kanal listesi üzerinden lokal arama
        val response = app.get(
            url     = "$mainUrl$CHANNELS_ENDPOINT",
            headers = authHeaders(),
            cookies = authCookies()
        )

        val parsed  = response.parsedSafe<ChannelListResponse>()
        val rawList = parsed?.channels ?: parsed?.data ?: parsed?.items ?: parsed?.results
            ?: return emptyList()

        return rawList
            .filter { (it.name ?: it.title ?: "").contains(query, ignoreCase = true) }
            .mapNotNull { channel ->
                val channelName = channel.name ?: channel.title ?: return@mapNotNull null
                newLiveSearchResponse(
                    name = channelName,
                    url  = buildChannelUrl(channel),
                    type = TvType.Live
                ) {
                    this.posterUrl = channel.logo ?: channel.image
                }
            }
    }

    // ─── Kanal Detayı ─────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val channelName = url
            .removeSuffix("/")
            .substringAfterLast("/")
            .replace("-", " ")
            .replaceFirstChar { it.uppercase() }

        return newLiveStreamLoadResponse(
            name    = channelName,
            url     = url,
            dataUrl = url
        )
    }

    // ─── Stream Linki ─────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (authToken == null && sessionId == null) login()

        // Eğer data direkt bir .m3u8 URL'yse, o zaman onu kullan
        if (data.contains(".m3u8") || data.contains("/hls/") || data.contains("/live/")) {
            callback(
                ExtractorLink(
                    source   = name,
                    name     = name,
                    url      = data,
                    referer  = mainUrl,
                    quality  = Qualities.Unknown.value,
                    isM3u8   = true,
                    headers  = authHeaders()
                )
            )
            return true
        }

        // Kanal sayfasından stream URL'yi çek
        val response = app.get(
            url     = data,
            headers = authHeaders(),
            cookies = authCookies()
        )

        if (!response.isSuccessful) {
            Log.e("TvVin", "Stream isteği başarısız: ${response.code} - $data")
            return false
        }

        // 1) JSON API ise parse et
        val parsed = response.parsedSafe<StreamResponse>()
        val streamUrl = parsed?.streamUrl ?: parsed?.url ?: parsed?.hlsUrl ?: parsed?.m3u8 ?: parsed?.source

        if (streamUrl != null) {
            callback(
                ExtractorLink(
                    source   = name,
                    name     = name,
                    url      = if (streamUrl.startsWith("http")) streamUrl else "$mainUrl$streamUrl",
                    referer  = mainUrl,
                    quality  = Qualities.Unknown.value,
                    isM3u8   = streamUrl.contains(".m3u8"),
                    headers  = authHeaders()
                )
            )
            return true
        }

        // 2) HTML sayfasıysa m3u8 / stream URL ara
        val html = response.text
        val patterns = listOf(
            Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""source\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""file\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""stream_url\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""hls_url\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""["']url["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
        )

        for (pattern in patterns) {
            val match = pattern.find(html)?.groupValues?.getOrNull(1)
            if (!match.isNullOrBlank()) {
                Log.d("TvVin", "Regex ile stream bulundu: $match")
                callback(
                    ExtractorLink(
                        source   = name,
                        name     = name,
                        url      = if (match.startsWith("http")) match else "$mainUrl$match",
                        referer  = mainUrl,
                        quality  = Qualities.Unknown.value,
                        isM3u8   = match.contains(".m3u8"),
                        headers  = authHeaders()
                    )
                )
                return true
            }
        }

        Log.e("TvVin", "Stream URL bulunamadı. Sayfa: ${html.take(1000)}")
        return false
    }

    // ─── Eklenti Ayarları (UI) ────────────────────────────────────────────────

    override val settingsItems = listOf(
        SettingsItem(
            id           = "phone",
            name         = "Telefon Numarası",
            description  = "TV.vin hesabınızın telefon numarası (örn: 5551234567)",
            type         = SettingsType.Text,
            placeholder  = "5551234567"
        ),
        SettingsItem(
            id          = "password",
            name        = "Şifre",
            description = "TV.vin hesabınızın şifresi",
            type        = SettingsType.Password
        )
    )
}
