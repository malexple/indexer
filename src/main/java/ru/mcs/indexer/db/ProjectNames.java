package ru.mcs.indexer.db;

import java.util.regex.Pattern;

public final class ProjectNames {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_LENGTH = 64;

    private ProjectNames() {
    }

    public static boolean isValid(String slug) {
        return slug != null && VALID.matcher(slug).matches();
    }

    public static String fromDirectoryName(String name) {
        String slug = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (slug.isEmpty() || !isAsciiAlphanumeric(slug.charAt(0))) {
            slug = "p" + slug;
        }
        return slug.length() > MAX_LENGTH ? slug.substring(0, MAX_LENGTH) : slug;
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}