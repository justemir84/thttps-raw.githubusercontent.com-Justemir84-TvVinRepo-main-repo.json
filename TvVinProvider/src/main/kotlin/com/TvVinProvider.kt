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
 *   POST /api/login      - { phone, password }   -> token / cookie
 *   GET  /api/channels   - kanal listesi
 *   GET  /api/channel/{id} - kanal detayı + stream url
 * 
 * Eğer çalışmazsa tarayıcıda F12 -> Network -> XHR sekmesini aç,
 * siteye giriş yap ve kanallardan birine tıkla. Gelen isteklere bakarak
 * aşağıdaki LOGIN_ENDPOINT ve CHANNELS_ENDPOINT'i güncelle.
 */

class TvVinProvider : MainAPI() {

    override var mainUrl    = "https://tv.vin"
    override var name       = "TV.vin"
    override val hasMainPage = true
    override var lang       = "tr"
    override val supportedTypes = setOf(TvType.Live)

    // —— Ayarlar (Kullanıcı bilgileri doğrudan eklendi) ——
    private val phone    = "3125622585"
    private val password = "Macizle123."

    // Oturum token'ı bellekte saklanır (uygulama yeniden başlayana kadar)
    private var authToken: String? = null
    private var sessionId: String? = null

    companion object {
        // ⚠️ Eğer bunlar farklıysa aşağıyı güncelle ————————————————————————
        const val LOGIN_ENDPOINT    = "/api/login"
        const val CHANNELS_ENDPOINT = "/api/channels"
        
        // Alternatif olarak deneyebilirsin:
        // const val LOGIN_ENDPOINT    = "/login"
        // const val CHANNELS_ENDPOINT = "/channels"
        // ——————————————————————————————————————————————————————————————————
    }

    // —— Data Sınıfları ——————————————————————————————————————————————————————

    data class LoginResponse(
        @JsonProperty("token")       val token: String? = null,
        @JsonProperty("auth_token")  val authToken: String? = null,
        @JsonProperty("access_token") val accessToken: String? = null,
        @JsonProperty("session_id")  val sessionId: String? = null,
        @JsonProperty("success")     val success: Boolean? = null,
        @JsonProperty("status")      val status: Int? = null
    )

    data class ChannelItem(
        @JsonProperty("id")          val id:      Any? = null,
        @JsonProperty("name")        val name:    String? = null,
        @JsonProperty("title")       val title:   String? = null,
        @JsonProperty("slug")        val slug:    String? = null,
        @JsonProperty("url")         val url:     String? = null,
        @JsonProperty("logo")        val logo:    String? = null,
        @JsonProperty("image")       val image:   String? = null,
        @JsonProperty("thumbnail")   val thumbnail: String? = null,
        @JsonProperty("stream_url")  val streamUrl: String? = null,
        @JsonProperty("category")    val category: String? = null,
        @JsonProperty("group")       val group:    String? = null
    )

    data class ChannelListResponse(
        @JsonProperty("channels")    val channels: List<ChannelItem>? = null,
        @JsonProperty("data")        val data:     List<ChannelItem>? = null,
        @JsonProperty("items")       val items:    List<ChannelItem>? = null,
        @JsonProperty("results")     val results:  List<ChannelItem>? = null
    )

    data class StreamResponse(
        @JsonProperty("stream_url")  val streamUrl: String? = null,
        @JsonProperty("url")         val url:       String? = null,
        @JsonProperty("hls_url")     val hlsUrl:    String? = null,
        @JsonProperty("m3u8")        val m3u8:      String? = null
    )

    // —— Giriş Yapma Fonksiyonu ——————————————————————————————————————————————

    private suspend fun login(): Boolean {
        if (phone.isEmpty() || password.isEmpty()) {
            throw ErrorLoadingException("Lütfen TV.vin kullanıcı bilgilerini eklenti ayarlarından girin (Ayarlar -> TV.vin).")
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

                Log.d("TvVin", "Giriş sonucu: $success, token=$authToken, session=$sessionId")
                return success
            }
            
            // Yöntem 2: Form data ile dene (bazı siteler form ister)
            val formResponse = app.post(
                url = "$mainUrl$LOGIN_ENDPOINT",
                data = mapOf(
                    "phone"    to phone,
                    "password" to password
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

    // —— Ana Sayfa ——————————————————————————————————————————————————————————

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // İlk yüklemede giriş yap
        if (authToken == null && sessionId == null) {
            val ok = login()
            if (!ok) throw ErrorLoadingException("TV.vin'e giriş yapılamadı.")
        }

        val response = app.get(
            url = "$mainUrl$CHANNELS_ENDPOINT",
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
        
        // Farklı API yapılarına göre kanal listesini bul
        val items = parsed?.channels ?: parsed?.data ?: parsed?.items ?: parsed?.results ?: emptyList()

        val homeItems = items.map { channel ->
            LiveSearchResponse(
                name = channel.name ?: channel.title ?: "Kanal",
                url  = channel.id?.toString() ?: channel.slug ?: "",
                apiName = this.name,
                type = TvType.Live,
                posterUrl = channel.logo ?: channel.image ?: channel.thumbnail
            )
        }

        return HomePageResponse(listOf(HomePageList("Canlı Kanallar", homeItems)))
    }

    // —— Arama (Opsiyonel) ———————————————————————————————————————————————————

    override suspend fun search(query: String): List<SearchResponse> {
        // Ana sayfadaki tüm kanalları çekip filtreleyelim
        val mainPage = getMainPage(1, MainPageRequest("Search", ""))
        return mainPage.list.flatMap { it.list }.filter { 
            it.name.contains(query, ignoreCase = true) 
        }
    }

    // —— Kanal Detayı ve Yayın URL'si ————————————————————————————————————————

    override suspend fun load(url: String): LoadResponse {
        // url burada kanalın ID'si veya slug'ıdır
        val channelUrl = "$mainUrl/api/channel/$url"
        
        val response = app.get(
            url = channelUrl,
            headers = authHeaders(),
            cookies = authCookies()
        )

        val channel = response.parsedSafe<ChannelItem>()
        val title   = channel?.name ?: channel?.title ?: "Canlı Yayın"
        
        // Eğer stream_url doğrudan kanal bilgisinde geliyorsa
        val streamUrl = channel?.streamUrl

        return LiveStreamResponse(
            name = title,
            url  = url,
            apiName = this.name,
            dataUrl = channelUrl,
            posterUrl = channel?.logo ?: channel?.image,
            type = TvType.Live
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data burada kanalın API URL'sidir
        val response = app.get(
            url = data,
            headers = authHeaders(),
            cookies = authCookies()
        )
        
        val channel = response.parsedSafe<ChannelItem>()
        val streamUrl = channel?.streamUrl

        if (streamUrl != null) {
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = streamUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8  = streamUrl.contains("m3u8")
                )
            )
            return true
        }
        return false
    }
}
