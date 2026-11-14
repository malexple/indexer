package ru.mcs.indexer.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitIgnoreMatcherTest {

    private static GitIgnoreMatcher matcher(String... lines) {
        GitIgnoreMatcher m = new GitIgnoreMatcher();
        m.addRules("", List.of(lines));
        return m;
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        GitIgnoreMatcher m = matcher("# comment", "", "   ", "*.log");
        assertThat(m.isIgnored("a.log", false)).isTrue();
        assertThat(m.isIgnored("# comment", false)).isFalse();
    }

    @Test
    void unanchoredNameMatchesAtAnyDepth() {
        GitIgnoreMatcher m = matcher("*.log");
        assertThat(m.isIgnored("a.log", false)).isTrue();
        assertThat(m.isIgnored("x/y/a.log", false)).isTrue();
        assertThat(m.isIgnored("a.txt", false)).isFalse();
    }

    @Test
    void directoryOnlyRule() {
        GitIgnoreMatcher m = matcher("build/");
        assertThat(m.isIgnored("build", true)).isTrue();
        assertThat(m.isIgnored("build", false)).isFalse();
        assertThat(m.isIgnored("x/build/Foo.class", false)).isTrue();
    }

    @Test
    void leadingSlashAnchorsToBase() {
        GitIgnoreMatcher m = matcher("/todo.txt");
        assertThat(m.isIgnored("todo.txt", false)).isTrue();
        assertThat(m.isIgnored("sub/todo.txt", false)).isFalse();
    }

    @Test
    void middleSlashAnchorsPattern() {
        GitIgnoreMatcher m = matcher("doc/*.txt");
        assertThat(m.isIgnored("doc/a.txt", false)).isTrue();
        assertThat(m.isIgnored("doc/x/a.txt", false)).isFalse();
        assertThat(m.isIgnored("a/doc/a.txt", false)).isFalse();
    }

    @Test
    void leadingDoubleStar() {
        GitIgnoreMatcher m = matcher("**/logs");
        assertThat(m.isIgnored("logs", true)).isTrue();
        assertThat(m.isIgnored("a/b/logs", true)).isTrue();
        assertThat(m.isIgnored("a/b/logs2", true)).isFalse();
    }

    @Test
    void trailingDoubleStarMatchesContentsOnly() {
        GitIgnoreMatcher m = matcher("logs/**");
        assertThat(m.isIgnored("logs/a.txt", false)).isTrue();
        assertThat(m.isIgnored("logs/a/b", false)).isTrue();
        assertThat(m.isIgnored("logs", true)).isFalse();
    }

    @Test
    void middleDoubleStar() {
        GitIgnoreMatcher m = matcher("a/**/b");
        assertThat(m.isIgnored("a/b", false)).isTrue();
        assertThat(m.isIgnored("a/x/y/b", false)).isTrue();
        assertThat(m.isIgnored("ab", false)).isFalse();
    }

    @Test
    void negationLastMatchWins() {
        GitIgnoreMatcher m = matcher("*.log", "!keep.log");
        assertThat(m.isIgnored("keep.log", false)).isFalse();
        assertThat(m.isIgnored("other.log", false)).isTrue();

        GitIgnoreMatcher reversed = matcher("!keep.log", "*.log");
        assertThat(reversed.isIgnored("keep.log", false)).isTrue();
    }

    @Test
    void cannotReincludeInsideIgnoredDirectory() {
        GitIgnoreMatcher m = matcher("build/", "!build/keep.txt");
        assertThat(m.isIgnored("build/keep.txt", false)).isTrue();
    }

    @Test
    void questionMarkMatchesSingleNonSlashChar() {
        GitIgnoreMatcher m = matcher("file?.txt");
        assertThat(m.isIgnored("file1.txt", false)).isTrue();
        assertThat(m.isIgnored("file10.txt", false)).isFalse();
        assertThat(m.isIgnored("dir/file2.txt", false)).isTrue();
    }

    @Test
    void characterClass() {
        GitIgnoreMatcher m = matcher("*.[oa]");
        assertThat(m.isIgnored("x.o", false)).isTrue();
        assertThat(m.isIgnored("x.a", false)).isTrue();
        assertThat(m.isIgnored("x.c", false)).isFalse();
    }

    @Test
    void negatedCharacterClass() {
        GitIgnoreMatcher m = matcher("f[!0-9].txt");
        assertThat(m.isIgnored("fa.txt", false)).isTrue();
        assertThat(m.isIgnored("f1.txt", false)).isFalse();
    }

    @Test
    void dotsAreLiteral() {
        GitIgnoreMatcher m = matcher("a.b");
        assertThat(m.isIgnored("a.b", false)).isTrue();
        assertThat(m.isIgnored("aXb", false)).isFalse();
    }

    @Test
    void nestedGitignoreOverridesParent() {
        GitIgnoreMatcher m = new GitIgnoreMatcher();
        m.addRules("", List.of("*.tmp"));
        m.addRules("sub", List.of("!important.tmp"));
        assertThat(m.isIgnored("sub/important.tmp", false)).isFalse();
        assertThat(m.isIgnored("important.tmp", false)).isTrue();
        assertThat(m.isIgnored("other/important.tmp", false)).isTrue();
        assertThat(m.isIgnored("sub/x.tmp", false)).isTrue();
    }

    @Test
    void nestedAnchoredRuleIsRelativeToItsBase() {
        GitIgnoreMatcher m = new GitIgnoreMatcher();
        m.addRules("sub", List.of("/gen"));
        assertThat(m.isIgnored("sub/gen", true)).isTrue();
        assertThat(m.isIgnored("gen", true)).isFalse();
        assertThat(m.isIgnored("sub/x/gen", true)).isFalse();
    }

    @Test
    void trailingSpacesTrimmedUnlessEscaped() {
        GitIgnoreMatcher m = matcher("foo   ", "bar\\ ");
        assertThat(m.isIgnored("foo", false)).isTrue();
        assertThat(m.isIgnored("bar ", false)).isTrue();
        assertThat(m.isIgnored("bar", false)).isFalse();
    }

    @Test
    void escapedHashAndBang() {
        GitIgnoreMatcher m = matcher("\\#hash", "\\!bang");
        assertThat(m.isIgnored("#hash", false)).isTrue();
        assertThat(m.isIgnored("!bang", false)).isTrue();
    }

    @Test
    void windowsSeparatorsAreNormalized() {
        GitIgnoreMatcher m = matcher("*.log");
        assertThat(m.isIgnored("a\\b\\c.log", false)).isTrue();
    }

    @Test
    void crlfLinesAreAccepted() {
        GitIgnoreMatcher m = matcher("*.log\r", "tmp/\r");
        assertThat(m.isIgnored("a.log", false)).isTrue();
        assertThat(m.isIgnored("tmp", true)).isTrue();
    }

    @Test
    void doubleStarAloneMatchesEverything() {
        GitIgnoreMatcher m = matcher("**");
        assertThat(m.isIgnored("a/b/c.txt", false)).isTrue();
    }
}