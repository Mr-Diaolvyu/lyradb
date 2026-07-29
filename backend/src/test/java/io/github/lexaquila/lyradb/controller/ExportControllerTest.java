package io.github.lexaquila.lyradb.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExportControllerTest {

    @Test
    void spreadsheetFormulaPrefixesAreNeutralized() {
        assertEquals("'=HYPERLINK(\"https://example.com\")",
                ExportController.safeSpreadsheetText("=HYPERLINK(\"https://example.com\")"));
        assertEquals("'  +cmd", ExportController.safeSpreadsheetText("  +cmd"));
        assertEquals("normal", ExportController.safeSpreadsheetText("normal"));
    }
}
