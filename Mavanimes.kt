package eu.kanade.tachiyomi.animeextension.fr.mavanimes

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Mavanimes : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Mavanimes"

    override val baseUrl = "http://www.mavanimes.co/"

    override val lang = "fr"

    private val json: Json by injectLazy()

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = ".col-sm-3.col-xs-12"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("a").attr("href")
        )
        anime.title = element.select("a p").text().substringBeforeLast(" ").substringBeforeLast(" ")
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    override fun episodeListParse(response: Response): List<SEpisode> {
        val soup = response.asJsoup()
        val sEpisodes = ArrayList<SEpisode>()
        var count = 1
        soup.select("#content > div > article > div > select > option:nth-child(1n+2)").forEach {
            val episode = SEpisode.create()
            episode.url = it.attr("value").replace("http://www.mavanimes.co/", "")
            episode.name = it.text()
            episode.episode_number = count.toFloat()
            count += 1
            sEpisodes.add(episode)
            println(episode.url)
        }
        return sEpisodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        var soup = response.asJsoup()
        val iframeUrl = soup.select("iframe")[0].attr("src")
        if (iframeUrl.startsWith("https://mavavid.com/")) {
            val id = iframeUrl.split("/").last()

            val res = client.newCall(POST("https://mavavid.com/api/source/$id")).execute()
            if (res.body != null) {
                val json = JSONObject(res.body!!.string())
                var array = json.getJSONArray("data")
                for (i in 0 until array.length()) {
                    val el = array.getJSONObject(i)
                    val video = Video(el.getString("label"), el.getString("label"), el.getString("file"))
                    videoList.add(video)
                }
            }
        }
        return videoList.reversed()
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw NotImplementedError()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.search-result")
        val animeList2 = document.select("div.movie-poster")
        val animes = animeList.map {
            searchAnimeFromElement(it)
        } + animeList2.map {
            searchAnimeFromElement(it)
        }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw NotImplementedError()
    }

    private fun searchPopularAnimeFromElement(element: Element): SAnime {
        throw NotImplementedError()
    }

    override fun searchAnimeNextPageSelector(): String = throw NotImplementedError()

    override fun searchAnimeSelector(): String = throw NotImplementedError()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("#content > div > article > header > h1").text().substringBeforeLast(" ").substringBeforeLast(" ")
        anime.description = null
        anime.genre = null
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("not used")

    override fun latestUpdatesSelector(): String = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Vudeo", "Mytv", "Doodstream")
            entryValues = arrayOf("Vudeo", "Mytv", "Doodstream")
            setDefaultValue("Vudeo")
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
