package comp3011;

public class SubtitleLayoutTest {
    @Test
    void shortTextStaysOnOneLine() {
        assertEquals(List.of("Hello there."), SubtitleLayout.layout("Hello there."));
    }

    @Test
    void longTextBreaksIntoTwoLines() {
        List<String> lines = SubtitleLayout.layout(
                "This is a long piece of dialogue that will not fit on a single subtitle line.");
        assertEquals(2, lines.size());
        lines.forEach(line -> assertTrue(line.length() <= SubtitleLayout.MAX_CHARACTERS_PER_LINE));
    }

    @Test
    void breaksAfterPunctuationWhenPossible() {
        List<String> lines = SubtitleLayout.layout(
                "I went to the shop yesterday, and then I walked all the way back home again.");
        assertTrue(lines.get(0).endsWith(","));
    }
}
