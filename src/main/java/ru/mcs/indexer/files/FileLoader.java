package ru.mcs.indexer.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class FileLoader {

    private static final Charset FALLBACK = Charset.forName("windows-1251");

    private FileLoader() {
    }

    public static LoadResult load(SelectedFile file, boolean keepContent) {
        try {
            byte[] bytes = Files.readAllBytes(file.absolutePath());
            String sha256 = sha256(bytes);
            List<String> warnings = new ArrayList<>();
            String text = normalize(decode(bytes, warnings));
            return new LoadResult.Loaded(new LoadedFile(
                    file.path(),
                    FileClassifier.lang(file.path()),
                    FileClassifier.kind(file.path()),
                    bytes.length,
                    countLines(text),
                    sha256,
                    keepContent ? text : null,
                    List.copyOf(warnings)));
        } catch (IOException | RuntimeException e) {
            return new LoadResult.Failed(file.path(), String.valueOf(e));
        }
    }

    static String decode(byte[] bytes, List<String> warnings) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            warnings.add("invalid UTF-8; decoded as windows-1251");
            return new String(bytes, FALLBACK);
        }
    }

    static String normalize(String text) {
        String result = text;
        if (!result.isEmpty() && result.charAt(0) == '\uFEFF') {
            result = result.substring(1);
        }
        if (result.indexOf('\r') < 0) {
            return result;
        }
        return result.replace("\r\n", "\n").replace('\r', '\n');
    }

    static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        if (text.charAt(text.length() - 1) != '\n') {
            count++;
        }
        return count;
    }

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}