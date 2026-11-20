package ru.mcs.indexer.lang.java;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavadocSummaryTest {

    @Test
    void takesFirstSentence() {
        assertThat(JavadocSummary.firstSentence("\n * Opens a file. Second sentence.\n "))
                .isEqualTo("Opens a file.");
    }

    @Test
    void stopsAtBlockTagAndParagraph() {
        assertThat(JavadocSummary.firstSentence("\n * Does things\n * over two lines\n *\n * Other.\n * @param x y\n"))
                .isEqualTo("Does things over two lines");
        assertThat(JavadocSummary.firstSentence("\n * @return nothing\n")).isNull();
    }

    @Test
    void unwrapsInlineTagsAndHtml() {
        assertThat(JavadocSummary.firstSentence(" Uses {@link Foo} and <b>{@code bar}</b>. Next."))
                .isEqualTo("Uses Foo and bar.");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(JavadocSummary.firstSentence(null)).isNull();
        assertThat(JavadocSummary.firstSentence("  \n * \n")).isNull();
    }
}