package ru.mcs.mcpsql.site;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SiteContentTest {

    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");
    private static final Pattern ID = Pattern.compile("id=\"([^\"]+)\"");

    private static String resource(String path) throws IOException {
        try (InputStream in = SiteContentTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> ids(String html) {
        Set<String> ids = new TreeSet<>();
        Matcher m = ID.matcher(html);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    @Test
    void englishIsTheDefaultPageAndRussianLivesUnderRu() throws IOException {
        String en = resource("/static/index.html");
        String ru = resource("/static/ru/index.html");

        assertThat(en).contains("<html lang=\"en\">");
        assertThat(ru).contains("<html lang=\"ru\">");
        assertThat(CYRILLIC.matcher(en).find()).as("the English page has no Cyrillic text").isFalse();
        assertThat(CYRILLIC.matcher(ru).find()).isTrue();
    }

    @Test
    void bothPagesHaveTheSameElementsForTheScript() throws IOException {
        String en = resource("/static/index.html");
        String ru = resource("/static/ru/index.html");

        assertThat(ids(ru)).isEqualTo(ids(en));
        assertThat(ids(en)).contains("users-active", "users-capacity", "users-registered", "storage-used", "storage-free",
                "pairs-5h", "pairs-1d", "pairs-7d", "bar", "limits", "example", "generated", "stats-error", "stats-stale");
    }

    @Test
    void bothPagesSwitchLanguageAndShareTheAssets() throws IOException {
        for (String page : List.of("/static/index.html", "/static/ru/index.html")) {
            String html = resource(page);
            assertThat(html).as(page).contains("href=\"/\" data-lang=\"en\"", "href=\"/ru/\" data-lang=\"ru\"")
                    .contains("href=\"/style.css\"", "src=\"/app.js\"", "https://t.me/mcp_sql_bot");
        }
    }

    @Test
    void thePagesFitTheContentSecurityPolicy() throws IOException {
        for (String page : List.of("/static/index.html", "/static/ru/index.html")) {
            String html = resource(page);
            assertThat(html).as(page).doesNotContain("<style").doesNotContain(" style=").doesNotContain(" onclick=")
                    .doesNotContainPattern("<script(?![^>]*\\bsrc=)");
        }
    }

    @Test
    void theScriptKnowsBothLanguagesAndEveryLimit() throws IOException {
        String js = resource("/static/app.js");

        assertThat(js).contains("en: {", "ru: {");
        for (String key : List.of("maxWorkspaces", "maxDbBytes", "uploadsPerDay", "writeBytesPerDay",
                "patchIntervalSeconds", "requestsPerDay", "keyIssuesPerDay")) {
            assertThat(js.split(key, -1).length - 1).as(key).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void theRussianAddressForwardsToTheRussianPage() {
        assertThat(new SiteController().russian()).isEqualTo("forward:/ru/index.html");
    }
}
