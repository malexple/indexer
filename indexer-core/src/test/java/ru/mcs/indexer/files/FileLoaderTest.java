package ru.mcs.indexer.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileLoaderTest {

    @TempDir
    Path tmp;

    private LoadedFile load(String name, byte[] bytes, boolean keepContent) throws IOException {
        Path file = tmp.resolve(name);
        Files.write(file, bytes);
        LoadResult result = FileLoader.load(new SelectedFile(name, file, bytes.length), keepContent);
        assertThat(result).isInstanceOf(LoadResult.Loaded.class);
        return ((LoadResult.Loaded) result).file();
    }

    @Test
    void stripsBomAndNormalizesLineEndings() throws IOException {
        byte[] raw = "\uFEFFa\r\nb\rc\n".getBytes(StandardCharsets.UTF_8);
        LoadedFile file = load("a.txt", raw, true);
        assertThat(file.content()).isEqualTo("a\nb\nc\n");
        assertThat(file.lineCount()).isEqualTo(3);
        assertThat(file.sizeBytes()).isEqualTo(raw.length);
        assertThat(file.warnings()).isEmpty();
    }

    @Test
    void countsLinesWithoutTrailingNewlineAndEmptyFile() throws IOException {
        assertThat(load("b.txt", "a\nb".getBytes(StandardCharsets.UTF_8), true).lineCount()).isEqualTo(2);
        assertThat(load("c.txt", new byte[0], true).lineCount()).isZero();
    }

    @Test
    void sha256IsComputedOverRawBytes() throws IOException {
        LoadedFile file = load("d.txt", "abc".getBytes(StandardCharsets.UTF_8), true);
        assertThat(file.sha256())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void invalidUtf8FallsBackToWindows1251WithWarning() throws IOException {
        byte[] raw = {(byte) 0xCF, (byte) 0xF0, (byte) 0xE8, (byte) 0xE2, (byte) 0xE5, (byte) 0xF2};
        LoadedFile file = load("e.txt", raw, true);
        assertThat(file.content()).isEqualTo("Привет");
        assertThat(file.warnings()).hasSize(1);
    }

    @Test
    void noSourceKeepsMetricsButDropsContent() throws IOException {
        LoadedFile file = load("f.txt", "x\ny\n".getBytes(StandardCharsets.UTF_8), false);
        assertThat(file.content()).isNull();
        assertThat(file.lineCount()).isEqualTo(2);
        assertThat(file.sha256()).isNotBlank();
    }

    @Test
    void missingFileYieldsFailure() {
        Path missing = tmp.resolve("missing.txt");
        LoadResult result = FileLoader.load(new SelectedFile("missing.txt", missing, 0), true);
        assertThat(result).isInstanceOf(LoadResult.Failed.class);
    }
}