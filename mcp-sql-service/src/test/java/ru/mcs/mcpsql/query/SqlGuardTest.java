package ru.mcs.mcpsql.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

    @Test
    void acceptsOneSelectOrWithStatement() {
        assertThat(SqlGuard.check("SELECT 1;")).isEqualTo("SELECT 1");
        assertThat(SqlGuard.check("-- note\nwith x as (select 1) select * from x")).startsWith("-- note");
    }

    @Test
    void literalsMayContainSemicolonsAndForbiddenWords() {
        assertThat(SqlGuard.check("select * from files where path like '%;update%'")).contains("like");
    }

    @Test
    void rejectsEverythingElse() {
        for (String bad : new String[]{"", "   ", "select 1; select 2", "delete from files",
                "select 1; drop table files", "attach database 'x' as y", "pragma writable_schema = 1",
                "select load_extension('x')", "insert into meta values ('a','b','c')"}) {
            assertThatThrownBy(() -> SqlGuard.check(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}