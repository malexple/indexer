package ru.mcs.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexerMainTest {

    @TempDir
    Path tmp;

    private void createProject() throws IOException {
        Files.writeString(tmp.resolve("Main.java"), "class Main {}\n");
    }

    @Test
    void spaceSeparatedOptionsWork() throws IOException {
        createProject();
        assertThat(IndexerMain.run(new String[]{"-r", tmp.toString(), "-l", "java"})).isZero();
    }

    @Test
    void equalsSeparatedOptionsWork() throws IOException {
        createProject();
        assertThat(IndexerMain.run(new String[]{"-r=" + tmp, "-l=java"})).isZero();
    }

    @Test
    void longOptionsAndFlagsWork() throws IOException {
        createProject();
        int code = IndexerMain.run(new String[]{"--root", tmp.toString(), "--lang=java",
                "--max-file-kb=100", "--no-source", "--no-resolve", "--stats", "-v",
                "--include-ext=cfg,.ini", "--exclude=*.tmp"});
        assertThat(code).isZero();
    }

    @Test
    void helpReturnsZero() {
        assertThat(IndexerMain.run(new String[]{"--help"})).isZero();
    }

    @Test
    void unknownOptionReturnsOne() {
        assertThat(IndexerMain.run(new String[]{"--bogus"})).isEqualTo(1);
    }

    @Test
    void nonPositiveMaxFileKbReturnsOne() throws IOException {
        createProject();
        assertThat(IndexerMain.run(new String[]{"-r", tmp.toString(), "--max-file-kb=0"})).isEqualTo(1);
    }

    @Test
    void unknownLanguageReturnsOne() throws IOException {
        createProject();
        assertThat(IndexerMain.run(new String[]{"-r", tmp.toString(), "-l", "cobol"})).isEqualTo(1);
    }

    @Test
    void notYetImplementedLanguageOnlyReturnsOne() throws IOException {
        createProject();
        assertThat(IndexerMain.run(new String[]{"-r", tmp.toString(), "-l", "kotlin"})).isEqualTo(1);
    }

    @Test
    void missingRootReturnsOne() {
        Path missing = tmp.resolve("does-not-exist");
        assertThat(IndexerMain.run(new String[]{"-r", missing.toString()})).isEqualTo(1);
    }
}