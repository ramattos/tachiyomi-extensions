package eu.kanade.tachiyomi.extension.all.madara

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

abstract class Madara(
        override val name: String,
        override val baseUrl: String,
        override val lang: String,
        private val dateFormat: SimpleDateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
) : ParsedHttpSource() {

    override val supportsLatest = true

    // Popular Manga

    override fun popularMangaSelector() = "div.page-item-detail"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            select("div.post-title a").first()?.let {
                manga.setUrlWithoutDomain(it.attr("href"))
                manga.title = it.ownText()
            }

            select("img").first()?.let {
                manga.thumbnail_url = it.absUrl(if(it.hasAttr("data-src")) "data-src" else "src")
            }
        }

        return manga
    }

    // Latest Updates

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector() = "div.item__wrap"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mp = super.latestUpdatesParse(response)
        val mangas = mp.mangas.distinctBy { it.url }
        return MangasPage(mangas, mp.hasNextPage)
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        // Even if it's different from the popular manga's list, the relevant classes are the same
        return popularMangaFromElement(element)
    }

    // Search Manga

    override fun searchMangaSelector() = "div.c-tabs-item__content"

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        with(element) {
            select("div.post-title a").first()?.let {
                manga.setUrlWithoutDomain(it.attr("href"))
                manga.title = it.ownText()
            }
            select("img").first()?.let {
                manga.thumbnail_url = it.absUrl(if(it.hasAttr("data-src")) "data-src" else "src")
            }
            select("div.mg_author div.summary-content a").first()?.let {
                manga.author = it.text()
            }
            select("div.mg_artists div.summary-content a").first()?.let {
                manga.artist = it.text()
            }
            select("div.mg_genres div.summary-content a").first()?.let {
                manga.genre = it.text()
            }
            select("div.mg_status div.summary-content a").first()?.let {
                manga.status = when(it.text()) {
                    // I don't know what's the corresponding for COMPLETED and LICENSED
                    // There's no support for "Canceled" or "On Hold"
                    "OnGoing" -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
            }
        }

        return manga
    }

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga Details Parse

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        with(document) {
            select("div.post-title h3").first()?.let {
                manga.title = it.ownText()
            }
            select("div.author-content").first()?.let {
                manga.author = it.text()
            }
            select("div.artist-content").first()?.let {
                manga.artist = it.text()
            }
            select("div.description-summary div.summary__content p").let {
                manga.description = it.joinToString(separator = "\n\n") { p ->
                    p.text().replace("<br>", "\n")
                }
            }
            select("div.summary_image img").first()?.let {
                manga.thumbnail_url = it.absUrl(if(it.hasAttr("data-src")) "data-src" else "src")
            }
        }

        return manga
    }

    override fun chapterListSelector() = "div.listing-chapters_wrap li.wp-manga-chapter"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        with(element) {
            select("a").first()?.let { urlElement ->
                chapter.setUrlWithoutDomain(urlElement.attr("href").let {
                    it + if(!it.endsWith("?style=list")) "?style=list" else ""
                })
                chapter.name = urlElement.text()
            }

            select("span.chapter-release-date i").first()?.let {
                chapter.date_upload = parseChapterDate(it.text()) ?: 0
            }
        }

        return chapter
    }

    open fun parseChapterDate(date: String): Long? {
        val lcDate = date.toLowerCase()
        if (lcDate.endsWith(" ago"))
            parseRelativeDate(lcDate)?.let { return it }

        //Handle 'yesterday' and 'today', using midnight
        if (lcDate.startsWith("ayer"))
            return Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -1) //yesterday
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        else if (lcDate.startsWith("today"))
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        return dateFormat.parseOrNull(date)?.time
    }

    // Parses dates in this form:
    // 21 horas ago
    private fun parseRelativeDate(date: String): Long? {
        val trimmedDate = date.split(" ")
        if (trimmedDate[2] != "ago") return null
        val number = trimmedDate[0].toIntOrNull() ?: return null

        // Map English/Spanish unit to Java unit
        val javaUnit = when (trimmedDate[1].removeSuffix("s")) {
            "día", "day" -> Calendar.DAY_OF_MONTH
            "hora", "hour" -> Calendar.HOUR
            "min", "minute" -> Calendar.MINUTE
            "segundo", "second" -> Calendar.SECOND
            else -> return null
        }

        return Calendar.getInstance().apply { add(javaUnit, -number) }.timeInMillis
    }

    private fun SimpleDateFormat.parseOrNull(string: String): Date? {
        return try {
            parse(string)
        } catch (e: ParseException) {
            null
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div.page-break").mapIndexed { index, element ->
            Page(index, "", element.select("img").first()?.let{
                it.absUrl(if(it.hasAttr("data-src")) "data-src" else "src")
            })
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException("Not used")
}