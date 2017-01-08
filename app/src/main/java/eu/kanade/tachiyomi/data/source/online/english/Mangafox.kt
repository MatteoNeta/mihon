package eu.kanade.tachiyomi.data.source.online.english

import eu.kanade.tachiyomi.data.network.GET
import eu.kanade.tachiyomi.data.source.model.*
import eu.kanade.tachiyomi.data.source.online.ParsedOnlineSource
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class Mangafox : ParsedOnlineSource() {

    override val id: Long = 3

    override val name = "Mangafox"

    override val baseUrl = "http://mangafox.me"

    override val lang = "en"

    override val supportsLatest = true

    override fun popularMangaSelector() = "div#mangalist > ul.list > li"
    
    override fun popularMangaRequest(page: Int): Request {
        val pageStr = if (page != 1) "$page.htm" else ""
        return GET("$baseUrl/directory/$pageStr", headers)
    }

    override fun latestUpdatesSelector() = "div#mangalist > ul.list > li"

    override fun latestUpdatesRequest(page: Int): Request {
        val pageStr = if (page != 1) "$page.htm" else ""
        return GET("$baseUrl/directory/$pageStr?latest")
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a.title").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text()
        }
        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        return popularMangaFromElement(element)
    }

    override fun popularMangaNextPageSelector() = "a:has(span.next)"

    override fun latestUpdatesNextPageSelector() = "a:has(span.next)"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = HttpUrl.parse("$baseUrl/search.php?name_method=cw&author_method=cw&artist_method=cw&advopts=1").newBuilder().addQueryParameter("name", query)
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is Genre -> url.addQueryParameter(filter.id, filter.state.toString())
                is TextField -> url.addQueryParameter(filter.key, filter.state)
                is ListField -> url.addQueryParameter(filter.key, filter.values[filter.state].value)
                is Order -> url.addQueryParameter("order", if (filter.state) "az" else "za")
            }
        }
        url.addQueryParameter("page", page.toString())
        return GET(url.toString(), headers)
    }

    override fun searchMangaSelector() = "div#mangalist > ul.list > li"

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a.title").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text()
        }
        return manga
    }

    override fun searchMangaNextPageSelector() = "a:has(span.next)"

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div#title").first()
        val rowElement = infoElement.select("table > tbody > tr:eq(1)").first()
        val sideInfoElement = document.select("#series_info").first()

        val manga = SManga.create()
        manga.author = rowElement.select("td:eq(1)").first()?.text()
        manga.artist = rowElement.select("td:eq(2)").first()?.text()
        manga.genre = rowElement.select("td:eq(3)").first()?.text()
        manga.description = infoElement.select("p.summary").first()?.text()
        manga.status = sideInfoElement.select(".data").first()?.text().orEmpty().let { parseStatus(it) }
        manga.thumbnail_url = sideInfoElement.select("div.cover > img").first()?.attr("src")
        return manga
    }

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div#chapters li div"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a.tips").first()

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text()
        chapter.date_upload = element.select("span.date").first()?.text()?.let { parseChapterDate(it) } ?: 0
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return if ("Today" in date || " ago" in date) {
            Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else if ("Yesterday" in date) {
            Calendar.getInstance().apply {
                add(Calendar.DATE, -1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        } else {
            try {
                SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).parse(date).time
            } catch (e: ParseException) {
                0L
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val url = document.baseUri().substringBeforeLast('/')

        val pages = mutableListOf<Page>()
        document.select("select.m").first()?.select("option:not([value=0])")?.forEach {
            pages.add(Page(pages.size, "$url/${it.attr("value")}.html"))
        }
        return pages
    }

    override fun imageUrlParse(document: Document): String {
        val url = document.getElementById("image").attr("src")
        return if ("compressed?token=" !in url) {
            url
        } else {
            "http://mangafox.me/media/logo.png"
        }
    }

    private data class ListValue(val name: String, val value: String) {
        override fun toString(): String = name
    }

    private class Genre(name: String, val id: String = "genres[$name]") : Filter.TriState(name)
    private class TextField(name: String, val key: String) : Filter.Text(name)
    private class ListField(name: String, val key: String, values: Array<ListValue>, state: Int = 0) : Filter.List<ListValue>(name, values, state)
    private class Order() : Filter.CheckBox("Ascending order")

    // $('select.genres').map((i,el)=>`Genre("${$(el).next().text().trim()}", "${$(el).attr('name')}")`).get().join(',\n')
    // on http://mangafox.me/search.php
    override fun getFilterList() = FilterList(
            TextField("Author", "author"),
            TextField("Artist", "artist"),
            ListField("Type", "type", arrayOf(ListValue("Any", ""), ListValue("Japanese Manga", "1"), ListValue("Korean Manhwa", "2"), ListValue("Chinese Manhua", "3"))),
            Genre("Completed", "is_completed"),
            ListField("Order by", "sort", arrayOf(ListValue("Series name", "name"), ListValue("Rating", "rating"), ListValue("Views", "views"), ListValue("Total chapters", "total_chapters"), ListValue("Last chapter", "last_chapter_time")), 2),
            Order(),
            Filter.Header("Genres"),
            Genre("Action"),
            Genre("Adult"),
            Genre("Adventure"),
            Genre("Comedy"),
            Genre("Doujinshi"),
            Genre("Drama"),
            Genre("Ecchi"),
            Genre("Fantasy"),
            Genre("Gender Bender"),
            Genre("Harem"),
            Genre("Historical"),
            Genre("Horror"),
            Genre("Josei"),
            Genre("Martial Arts"),
            Genre("Mature"),
            Genre("Mecha"),
            Genre("Mystery"),
            Genre("One Shot"),
            Genre("Psychological"),
            Genre("Romance"),
            Genre("School Life"),
            Genre("Sci-fi"),
            Genre("Seinen"),
            Genre("Shoujo"),
            Genre("Shoujo Ai"),
            Genre("Shounen"),
            Genre("Shounen Ai"),
            Genre("Slice of Life"),
            Genre("Smut"),
            Genre("Sports"),
            Genre("Supernatural"),
            Genre("Tragedy"),
            Genre("Webtoons"),
            Genre("Yaoi"),
            Genre("Yuri")
    )

}