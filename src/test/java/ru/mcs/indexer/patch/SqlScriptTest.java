package ru.mcs.indexer.patch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptTest {

    @Test
    void splitsOnSemicolonsOutsideLiteralsAndSkipsComments() {
        String script = "-- header: x\nBEGIN;\nINSERT INTO t VALUES ('a;b', 'it''s -- not a comment');\n"
                + "-- stats: 1\nUPDATE t SET v = 'line1\nline2;' WHERE id = 1;\nCOMMIT;\n";
        assertThat(SqlScript.split(script)).containsExactly(
                "BEGIN",
                "INSERT INTO t VALUES ('a;b', 'it''s -- not a comment')",
                "UPDATE t SET v = 'line1\nline2;' WHERE id = 1",
                "COMMIT");
    }

    @Test
    void ignoresTrailingWhitespaceAndEmptyStatements() {
        assertThat(SqlScript.split(" ;; \n SELECT 1 ; \n")).isEqualTo(List.of("SELECT 1"));
    }
}