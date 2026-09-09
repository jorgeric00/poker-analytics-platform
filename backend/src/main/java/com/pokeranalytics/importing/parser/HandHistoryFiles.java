package com.pokeranalytics.importing.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 */
public final class HandHistoryFiles {

    private HandHistoryFiles() {
    }

    /** Reads a hand-history file as UTF-8, stripping a leading byte-order mark if present. */
    public static String readNormalized(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == 0xFEFF) {
            content = content.substring(1);
        }
        return content;
    }
}
