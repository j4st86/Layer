package com.layer.core.config

import com.layer.core.model.AppRoutingMode
import com.layer.core.model.AppRoutingRule

data class RecommendedApp(
    val displayName: String,
    val mode: AppRoutingMode,
    val packageNames: List<String>,
)

/**
 * Suggested apps to add after the first server. Keep this list away from UI.
 * Extra package names can be added later without changing call sites.
 */
object RecommendedApps {
    val catalog: List<RecommendedApp> = listOf(
        rec("Telegram", "org.telegram.messenger", "org.telegram.messenger.web"),
        rec("YouTube", "com.google.android.youtube"),
        rec("WhatsApp", "com.whatsapp", "com.whatsapp.w4b"),
        rec("Instagram", "com.instagram.android"),
        rec("Spotify", "com.spotify.music"),
        rec("YouTube Music", "com.google.android.apps.youtube.music"),
        rec("Netflix", "com.netflix.mediaclient"),
        rec("Discord", "com.discord"),
        rec("X (Twitter)", "com.twitter.android"),
        rec("Google Gemini", "com.google.android.apps.bard"),
        rec("Threads", "com.instagram.barcelona"),
        rec("TikTok", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        rec("YouTube Morphe", "app.morphe.android.youtube"),
        rec("YouTube Music Morphe", "app.morphe.android.apps.youtube.music"),
        rec("YTDLnis", "com.deniscerri.ytdl"),
        rec("ChatGPT", "com.openai.chatgpt"),
        rec("Claude", "com.anthropic.claude"),
        rec("Microsoft Copilot", "com.microsoft.copilot"),
        rec("Perplexity", "ai.perplexity.app.android"),
        rec("ElevenLabs", "io.elevenlabs.readerapp"),
        rec("Suno", "com.suno.android"),
        rec("Twitch", "tv.twitch.android.app"),
        rec("Disney+", "com.disney.disneyplus"),
        rec("HBO Max", "com.wbd.stream", "com.hbo.hbonow"),
        rec("Hulu", "com.hulu.plus"),
        rec("Paramount+", "com.cbs.app", "com.cbs.ott"),
        rec("Peacock", "com.peacocktv.peacockandroid"),
        rec("Crunchyroll", "com.crunchyroll.crunchyroid"),
        rec("DAZN", "com.dazn"),
        rec("Facebook", "com.facebook.katana", "com.facebook.lite"),
        rec("Snapchat", "com.snapchat.android"),
        rec("Signal", "org.thoughtcrime.securesms"),
        rec("Viber", "com.viber.voip"),
        rec("LinkedIn", "com.linkedin.android"),
        rec("Pinterest", "com.pinterest"),
        rec("Google TV", "com.google.android.videos", "com.google.android.apps.tv.launcherx"),
        rec("Google Meet", "com.google.android.apps.tachyon", "com.google.android.apps.meetings"),
        rec("Google Chat", "com.google.android.apps.dynamite"),
        rec("Google Voice", "com.google.android.apps.googlevoice"),
        rec("Google Home", "com.google.android.apps.chromecast.app"),
        rec("Gemini Notebook", "com.google.android.apps.labs.language.tailwind"),
        recDirect("BandChat", "ru.wb.wband"),
        recDirect("Aliexpress", "ru.aliexpress.buyer"),
        recDirect("Auto.ru", "ru.auto.ara"),
        recDirect("BelkaCar", "ru.belkacar.belkacar"),
        recDirect("CityDrive", "youdrive.today"),
        recDirect("Citymobil", "com.citymobil"),
        recDirect("Delimobil", "com.carshering"),
        recDirect("Delivery", "com.deliveryclub"),
        recDirect("Dodo Pizza", "ru.dodopizza.app"),
        recDirect("DDX Fitness", "fitness.ddx.ddx_fitness"),
        recDirect("Fasten RU", "ru.yandex.uber"),
        recDirect("Nash dom", "spotnik.axmor.com"),
        recDirect("OZON", "ru.ozon.app.android"),
        recDirect("VK", "com.vkontakte.android"),
        recDirect("Wildberries", "com.wildberries.ru"),
        recDirect("Yandex Eats", "ru.foodfox.client"),
        recDirect("Yandex Go", "ru.yandex.taxi"),
        recDirect("Yandex Mail", "ru.yandex.mail"),
        recDirect("Yandex Metro", "ru.yandex.metro"),
        recDirect("Yandex Maps", "ru.yandex.yandexmaps"),
        recDirect("Авито", "com.avito.android"),
        recDirect("Авто", "ru.gosuslugi.auto"),
        recDirect("АЗС Роснефть", "ru.pichesky.rosneft"),
        recDirect("Вкусно — и точка", "com.apegroup.mcdonaldsrussia"),
        recDirect("ВТБ", "ru.vtb24.mobilebanking.android"),
        recDirect("Глобус", "ru.globus.app"),
        recDirect("Госуслуги", "ru.rostel"),
        recDirect("Драйв", "com.yandex.mobile.drive"),
        recDirect("ЛЕНТА", "com.icemobile.lenta.prod"),
        recDirect("ЛУКОЙЛ", "ru.serebryakovas.lukoilmobileapp"),
        recDirect("Мой МТС", "ru.mts.mymts"),
        recDirect("МосОблЕИРЦ", "ru.domopult.mosobleirc.android"),
        recDirect("Парковки", "ru.mosparking.appnew"),
        recDirect("СберБанк", "ru.sberbankmobile"),
        recDirect("Штрафы", "ru.gibdd_pay.app"),
        recDirect("Якитория", "com.voltmobi.yakitoriya"),
        recDirect("myteam", "ru.wildberries.team2"),
        recDirect("T-Bank", "com.idamob.tinkoff.android"),
        recDirect("Яндекс Электрички", "ru.yandex.rasp"),
        recDirect("Мегафон", "ru.megafon.mlk"),
    )

    fun rulesToAdd(
        installedLabelsByPackage: Map<String, String>,
        existingPackageNames: Set<String>,
    ): List<AppRoutingRule> {
        return catalog.flatMap { entry ->
            entry.packageNames.mapNotNull { pkg ->
                val label = installedLabelsByPackage[pkg] ?: return@mapNotNull null
                if (pkg in existingPackageNames) return@mapNotNull null
                AppRoutingRule(
                    packageName = pkg,
                    appName = label.ifBlank { entry.displayName },
                    mode = entry.mode,
                )
            }
        }.distinctBy { it.packageName }
    }

    private fun rec(
        displayName: String,
        vararg packageNames: String,
        mode: AppRoutingMode = AppRoutingMode.VPN,
    ) = RecommendedApp(displayName, mode, packageNames.toList())

    private fun recDirect(displayName: String, vararg packageNames: String) =
        RecommendedApp(displayName, AppRoutingMode.DIRECT, packageNames.toList())
}
