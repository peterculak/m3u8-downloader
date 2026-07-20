@file:DependsOn("org.jsoup:jsoup:1.17.2")
import org.jsoup.Jsoup

val html = """
  <div class="teaser teaser--list">
    <a href="https://example.com" class="teaser__link--main">
      <div class="teaser--list__text-container">
        <h1 class="teaser__title teaser__title--size-big">
          My Title
        </h1>
        <a href="/author" class="author-highlight">.author</a>
        <a href="/category" class="theme-highlight">.category</a>
        <span class="theme-highlight">17.07.2026</span>
      </div>
    </a>
  </div>
"""

val doc = Jsoup.parse(html)
for (el in doc.select("div.teaser--list")) {
    val title = el.selectFirst("h1.teaser__title")?.text()
    val date = el.select("span.theme-highlight").lastOrNull()?.text()
    val link = el.selectFirst("a.teaser__link--main")?.attr("href")
    println("Title: \$title")
    println("Date: \$date")
    println("Link: \$link")
}
