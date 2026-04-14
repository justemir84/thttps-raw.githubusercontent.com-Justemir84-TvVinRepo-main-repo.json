// TvVinProvider/build.gradle.kts
version = 1

cloudstream {
    // Kurucunun adı (değiştirebilirsin)
    authors     = listOf("TvVinDev")
    language    = "tr"
    description = "TV.vin canlı kanal eklentisi. Giriş bilgilerini eklenti ayarlarından girin."

    /**
     * Status:
     *  0: Down
     *  1: Ok
     *  2: Slow
     *  3: Beta (test aşamasında)
     */
    status = 3

    tvTypes = listOf("Live")

    iconUrl = "https://tv.vin/favicon.ico"
}
