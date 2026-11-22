package org.lotro.conductor;

import java.util.*;

/** Conservative suggestions using the instrument labels from SongBook 3 v1.25.4.2.
 * Never silently overwrites a manual part assignment or guesses between equally plausible players.
 */
public final class InstrumentMatcher {
    private InstrumentMatcher() { }
    // Longest / most specific aliases are checked first. Only explicit track-title matches count.
    private static final Map<String,List<String>> LABELS = Map.ofEntries(
        Map.entry("basic harp", List.of("basic harp")),
        Map.entry("misty mountain harp", List.of("misty mountain harp", "misty harp", "mm harp", "mmh")),
        Map.entry("basic lute", List.of("basic lute", "new lute", "blute", "luteb")),
        Map.entry("lute of ages", List.of("lute of ages", "age lute", "ages lute", "loa")),
        Map.entry("basic theorbo", List.of("basic theorbo", "theorbo")),
        Map.entry("traveller's trusty fiddle", List.of("traveller s trusty fiddle", "travellers trusty fiddle", "traveller fiddle", "travellers fiddle", "traveler fiddle", "tt fiddle", "ttf")),
        Map.entry("bardic fiddle", List.of("bardic fiddle")),
        Map.entry("basic fiddle", List.of("basic fiddle", "bfiddle")),
        Map.entry("lonely mountain fiddle", List.of("lonely mountain fiddle", "lonely fiddle", "lm fiddle", "lmf")),
        Map.entry("sprightly fiddle", List.of("sprightly fiddle", "spright fiddle")),
        Map.entry("student's fiddle", List.of("students fiddle", "student fiddle")),
        Map.entry("basic bagpipe", List.of("basic bagpipe", "bagpipe")),
        Map.entry("basic bassoon", List.of("basic bassoon")),
        Map.entry("brusque bassoon", List.of("brusque bassoon", "brusk bassoon")),
        Map.entry("lonely mountain bassoon", List.of("lonely mountain bassoon", "lonely bassoon", "lm bassoon")),
        Map.entry("basic clarinet", List.of("basic clarinet", "clarinet")),
        Map.entry("basic flute", List.of("basic flute", "flute")),
        Map.entry("basic horn", List.of("basic horn", "horn")),
        Map.entry("basic pibgorn", List.of("basic pibgorn", "pibgorn")),
        Map.entry("basic cowbell", List.of("basic cowbell")),
        Map.entry("moor cowbell", List.of("moor cowbell", "more cowbell")),
        Map.entry("basic drum", List.of("basic drum", "drum")),
        Map.entry("jaunty hand-knells", List.of("jaunty hand knells", "hand knells", "glockenspiel"))
    );
    /** SongBook instrument names for the quickslot editor; custom names remain supported. */
    public static List<String> knownInstruments() {
        return LABELS.keySet().stream().map(n -> n.equals("traveller's trusty fiddle") ? "Traveller's Trusty Fiddle" :
                n.equals("lute of ages") ? "Lute of Ages" :
                java.util.Arrays.stream(n.split(" ")).map(w -> Character.toUpperCase(w.charAt(0))+w.substring(1))
                        .collect(java.util.stream.Collectors.joining(" "))).sorted().toList();
    }
    private static String norm(String text) {
        // Apostrophes, hyphens and underscores are treated as word separators.
        return " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ") + " ";
    }
    private static boolean containsPhrase(String normalizedTitle, String phrase) {
        return normalizedTitle.contains(norm(phrase));
    }
    /** True only if the title explicitly names this equipped instrument or a known SongBook alias. */
    public static boolean explicitlyMatches(String title, String equippedInstrument) {
        if (title == null || equippedInstrument == null || title.isBlank() || equippedInstrument.isBlank()) return false;
        String item = norm(equippedInstrument).strip();
        String track = norm(title);
        // Specific aliases come from SongBook's Instruments_Names_inTrack list.
        for (var entry : LABELS.entrySet()) {
            if (!item.equals(norm(entry.getKey()).strip())) continue;
            for (String alias : entry.getValue()) if (containsPhrase(track, alias)) return true;
            return false;
        }
        // Custom named items: only permit an exact whole-phrase match.
        return item.length() >= 6 && track.contains(" " + item + " ");
    }
    /** Return ONLY unambiguous suggestions, leaving existing assignments alone. */
    public static Map<String,Integer> suggest(List<Music.Part> parts, List<CharacterInfo> roster, Map<String,Integer> existing) {
        Map<String,Integer> result = new LinkedHashMap<>();
        Set<Integer> occupied = new HashSet<>(existing.values());
        Set<String> usedCharacters = new HashSet<>(existing.keySet());
        Map<Integer,List<CharacterInfo>> byPart = new LinkedHashMap<>();
        Map<String,Integer> appearances = new HashMap<>();
        for (Music.Part part : parts) {
            if (occupied.contains(part.id())) continue;
            List<CharacterInfo> candidates = roster.stream()
                    .filter(c -> !usedCharacters.contains(c.key()))
                    .filter(c -> explicitlyMatches(part.title(), c.instrument()))
                    .toList();
            byPart.put(part.id(), candidates);
            for (CharacterInfo c : candidates) appearances.merge(c.key(), 1, Integer::sum);
        }
        for (var entry : byPart.entrySet()) {
            List<CharacterInfo> candidates = entry.getValue();
            if (candidates.size() != 1) continue;
            CharacterInfo c = candidates.get(0);
            if (appearances.get(c.key()) == 1) result.put(c.key(), entry.getKey());
        }
        return Map.copyOf(result);
    }
    /** Extract the required SongBook instrument from a track title, if unambiguous.
     * A final " - Instrument" or "(Instrument)" annotation takes precedence.
     */
    public static String requiredInstrument(String title) {
        if (title == null || title.isBlank()) return null;
        List<String> names = knownInstruments();
        int dash = title.lastIndexOf(" - ");
        if (dash >= 0) {
            String suffix = title.substring(dash+3).strip();
            for (String name:names) if (norm(suffix).strip().equals(norm(name).strip())) return name;
        }
        int best = -1; String found = null;
        for (String name:names) {
            if (!explicitlyMatches(title,name)) continue;
            int score = norm(name).length();
            if (score>best) {best=score;found=name;}
            else if(score==best) return null;
        }
        return found;
    }

}
