package ru.mcs.mcpsql.workspace;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Checks an uploaded patch before it is executed. A patch is a SQL script, and the patch applier runs whatever
 * it finds in it, so on a shared server nothing may reach it that the Indexer itself would not have written.
 * The script is unpacked with a hard size limit, split into statements the way SQLite sees them, and every
 * statement must match one of the exact shapes of PatchWriter (DELETE, INSERT and UPDATE by key with literal
 * values, the full-text bookkeeping and the guard table). Anything else (ATTACH, VACUUM INTO, PRAGMA, WITH,
 * CREATE, DROP, sub-queries, comments inside statements, quoted identifiers) rejects the whole patch.
 * The checked script is written to a plain file; the patch is applied from that file, never from the upload.
 */
final class PatchGuard {

    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String VALUE = "(?:N|\\?|NULL|X\\?)";
    private static final String ID_LIST = "N(?:, N)*";
    private static final String META_CHECK = "\\(SELECT value FROM meta WHERE scope = \\? AND key = \\?\\) = \\?";
    private static final int MAX_STATEMENT_SHAPE = 4000;

    private static final Pattern NUMBER =
            Pattern.compile("(?<![A-Za-z0-9_.])-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(?![A-Za-z0-9_.])");

    private static final List<Pattern> FIXED = List.of(
            Pattern.compile("BEGIN"),
            Pattern.compile("COMMIT"),
            Pattern.compile("PRAGMA defer_foreign_keys = ON"),
            Pattern.compile("CREATE TEMP TABLE _patch_guard\\(ok INTEGER NOT NULL CHECK \\(ok = N\\)\\)"),
            Pattern.compile("INSERT INTO _patch_guard SELECT " + META_CHECK + " AND " + META_CHECK + " AND "
                    + META_CHECK),
            Pattern.compile("DROP TABLE _patch_guard"),
            Pattern.compile("INSERT INTO code_fts\\(code_fts, rowid, path, content\\) SELECT \\?, id, path, content "
                    + "FROM files WHERE id IN \\(" + ID_LIST + "\\)"),
            Pattern.compile("INSERT INTO code_fts\\(rowid, path, content\\) SELECT id, path, content "
                    + "FROM files WHERE id IN \\(" + ID_LIST + "\\)"));

    private static final List<Pattern> TABLE_STATEMENTS = List.of(
            Pattern.compile("DELETE FROM (" + IDENT + ") WHERE " + IDENT + " = " + VALUE
                    + "(?: AND " + IDENT + " = " + VALUE + ")*"),
            Pattern.compile("INSERT INTO (" + IDENT + ")\\(" + IDENT + "(?:, " + IDENT + ")*\\) VALUES \\("
                    + VALUE + "(?:, " + VALUE + ")*\\)"),
            Pattern.compile("UPDATE (" + IDENT + ") SET " + IDENT + " = " + VALUE + "(?:, " + IDENT + " = " + VALUE
                    + ")* WHERE " + IDENT + " = " + VALUE + "(?: AND " + IDENT + " = " + VALUE + ")*"));

    private PatchGuard() {
    }

    /**
     * Unpacks and checks the patch; writes the plain script to target. Returns the number of statements.
     * Throws IllegalArgumentException for anything that is not a genuine patch or is larger than maxBytes unpacked.
     */
    static int sanitize(Path source, Path target, long maxBytes) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(source))) {
            raw.mark(2);
            int first = raw.read();
            int second = raw.read();
            raw.reset();
            boolean gzip = first == 0x1f && second == 0x8b;
            InputStream plain = gzip ? new GZIPInputStream(raw) : raw;
            try (PushbackReader in = new PushbackReader(
                         new InputStreamReader(new Limited(plain, maxBytes), StandardCharsets.UTF_8), 1);
                 Writer out = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(target),
                         StandardCharsets.UTF_8))) {
                return scan(in, out);
            }
        } catch (ZipException | EOFException e) {
            throw reject("the patch is not a valid gzip file");
        }
    }

    private static int scan(PushbackReader in, Writer out) throws IOException {
        StringBuilder shape = new StringBuilder();
        int count = 0;
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\'') {
                out.write(c);
                shape.append('?');
                skipLiteral(in, out);
            } else if (c == '-') {
                int next = in.read();
                if (next == '-') {
                    out.write("--");
                    int d;
                    while ((d = in.read()) >= 0) {
                        out.write(d);
                        if (d == '\n') {
                            break;
                        }
                    }
                    appendSpace(shape);
                } else {
                    if (next >= 0) {
                        in.unread(next);
                    }
                    out.write(c);
                    shape.append('-');
                }
            } else if (c == ';') {
                out.write(c);
                count += check(shape.toString(), count + 1);
                shape.setLength(0);
            } else {
                out.write(c);
                if (Character.isWhitespace(c)) {
                    appendSpace(shape);
                } else {
                    shape.append((char) c);
                }
            }
            if (shape.length() > MAX_STATEMENT_SHAPE) {
                throw reject("a statement is too long to be part of a patch");
            }
        }
        count += check(shape.toString(), count + 1);
        return count;
    }

    /** Runs of blanks and comment lines count once, so padding cannot trip the statement length check. */
    private static void appendSpace(StringBuilder shape) {
        if (shape.length() > 0 && shape.charAt(shape.length() - 1) != ' ') {
            shape.append(' ');
        }
    }

    /** The opening quote is already written; copies the literal including the closing quote. */
    private static void skipLiteral(PushbackReader in, Writer out) throws IOException {
        while (true) {
            int d = in.read();
            if (d < 0) {
                throw reject("a string literal is not terminated");
            }
            out.write(d);
            if (d == '\'') {
                int e = in.read();
                if (e == '\'') {
                    out.write(e);
                    continue;
                }
                if (e >= 0) {
                    in.unread(e);
                }
                return;
            }
        }
    }

    /** Returns 1 for a statement that is allowed, 0 for an empty one; throws for everything else. */
    private static int check(String shape, int number) {
        String s = shape.replaceAll("\\s+", " ").strip();
        if (s.isEmpty()) {
            return 0;
        }
        s = NUMBER.matcher(s).replaceAll("N");
        try {
            for (Pattern pattern : FIXED) {
                if (pattern.matcher(s).matches()) {
                    return 1;
                }
            }
            for (Pattern pattern : TABLE_STATEMENTS) {
                Matcher m = pattern.matcher(s);
                if (m.matches()) {
                    String table = m.group(1).toLowerCase(java.util.Locale.ROOT);
                    if (table.startsWith("sqlite_") || table.equals("_patch_guard")) {
                        throw reject("statement " + number + " touches a table that patches may not change");
                    }
                    return 1;
                }
            }
        } catch (StackOverflowError e) {
            throw reject("statement " + number + " is too complex");
        }
        String shown = s.length() > 60 ? s.substring(0, 60) + "..." : s;
        throw reject("statement " + number + " is not allowed in a patch: " + shown.replaceAll("[^\\x20-\\x7E]", "?"));
    }

    private static IllegalArgumentException reject(String message) {
        return new IllegalArgumentException("patch rejected: " + message);
    }

    /** Stops reading after maxBytes; this is what defeats compression bombs. */
    private static final class Limited extends FilterInputStream {

        private final long max;
        private long total;

        Limited(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++total > max) {
                throw tooLarge();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0) {
                total += n;
                if (total > max) {
                    throw tooLarge();
                }
            }
            return n;
        }

        private IllegalArgumentException tooLarge() {
            return reject("the patch is larger than " + max / (1024 * 1024) + " MB when unpacked");
        }
    }
}
