package eu.kanade.tachiyomi.animeextension.es.asialiveaction

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.asialiveaction.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.asialiveaction.extractors.OkruExtractor
import eu.kanade.tachiyomi.animeextension.es.asialiveaction.extractors.StreamSBExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.util.Calendar

class AsiaLiveAction : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AsiaLiveAction"

    override val baseUrl = "https://asialiveaction.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.TpRwCont main section ul.MovieList li.TPostMv article.TPost"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/todos/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a h3.Title").text()
        anime.thumbnail_url = element.select("a div.Image figure img").attr("src").trim().replace("//", "https://")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.TpRwCont main div a.next.page-numbers"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("header div.Image figure img").attr("src").trim().replace("//", "https://")
        anime.title = document.selectFirst("header div.asia-post-header div h1.Title").text()
        anime.description = document.selectFirst("header div.asia-post-main div.Description p:nth-child(2)").text().removeSurrounding("\"")
        anime.genre = document.select("div.asia-post-header div:nth-child(2) p.Info span.tags a").joinToString { it.text() }
        val year = document.select("div.asia-post-header div:nth-child(2) p.Info span.Date a").text().toInt()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        anime.status = when {
            (year < currentYear) -> SAnime.COMPLETED
            (year == currentYear) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "#ep-list div.TPTblCn span a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("div.flex-grow-1 p span").text())
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        episode.name = element.select("div.flex-grow-1 p span").text().trim()
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("var videosJap = [")) {
                val content = script.data()
                if (content.contains("sbfull")) {
                    val url = content.substringAfter(",['SB','").substringBefore("',0,0]")
                    val headers = headers.newBuilder()
                        .set("Referer", url)
                        .set(
                            "User-Agent",
                            "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0"
                        )
                        .set("Accept-Language", "en-US,en;q=0.5")
                        .set("watchsb", "sbfull")
                        .build()
                    val videos = StreamSBExtractor(client).videosFromUrl(url, headers)
                    videoList.addAll(videos)
                }
                if (content.contains("fembed")) {
                    val url = content.substringAfter(",['FD','").substringBefore("',0,0]")
                    val videos = FembedExtractor().videosFromUrl(url)
                    videoList.addAll(videos)
                }
                if (content.contains("okru")) {
                    val url = content.substringAfter(",['OK','").substringBefore("',0,0]")
                    val videos = OkruExtractor(client).videosFromUrl(url)
                    videoList.addAll(videos)
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Fembed:1080p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query")
            genreFilter.state != 0 -> GET("$baseUrl/tag/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", "all"),
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Drama", "drama"),
            Pair("Deporte", "deporte"),
            Pair("Erótico", "erotico"),
            Pair("Escolar", "escolar"),
            Pair("Extraterrestres", "extraterrestres"),
            Pair("Fantasía", "fantasia"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Lucha", "lucha"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Yaoi / BL", "yaoi-bl"),
            Pair("Yuri / GL", "yuri-gl")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Fembed:480p", "Fembed:720p", "Stape", "hd", "sd", "low", "lowest", "mobile")
            entryValues = arrayOf("Fembed:480p", "Fembed:720p", "Stape", "hd", "sd", "low", "lowest", "mobile")
            setDefaultValue("Fembed:1080p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
