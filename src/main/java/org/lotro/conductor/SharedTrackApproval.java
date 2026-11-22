package org.lotro.conductor;

import java.util.*;

/**
 * Approval for intentionally sharing an ABC X: part among multiple musicians.
 * Approval is specific to the current song checksum and exact member-to-track
 * mapping. It is local to this UI session and never changes any network rules.
 */
public final class SharedTrackApproval {
    public record Conflict(int trackId, String title, List<String> musicians) {
        public Conflict { musicians = List.copyOf(musicians); }
    }

    private String approvedFingerprint;

    public static List<Conflict> conflicts(Music.Song song, Map<String,Integer> assignments,
                                           List<CharacterInfo> roster) {
        if (song == null) return List.of();
        Map<Integer,List<String>> byPart = new TreeMap<>();
        for (CharacterInfo member : roster) {
            Integer id = assignments.get(member.key());
            if (id != null) byPart.computeIfAbsent(id, ignored -> new ArrayList<>())
                    .add(member.name() + " (" + member.computer() + ")");
        }
        Map<Integer,String> titles = new HashMap<>();
        for (Music.Part part : song.parts()) titles.put(part.id(), part.title());
        List<Conflict> result = new ArrayList<>();
        for (var entry : byPart.entrySet()) {
            if (entry.getValue().size() > 1)
                result.add(new Conflict(entry.getKey(),
                        titles.getOrDefault(entry.getKey(), "(unknown part)"),entry.getValue()));
        }
        return List.copyOf(result);
    }

    private static String fingerprint(Music.Song song, Map<String,Integer> assignments,
                                      List<CharacterInfo> roster) {
        if (song == null) return "";
        List<String> entries = new ArrayList<>();
        for (CharacterInfo c : roster) {
            Integer track = assignments.get(c.key());
            if (track != null) entries.add(c.key() + "\u0000" + track);
        }
        Collections.sort(entries);
        return song.gamePath() + "\u0000" + song.sha256() + "\u0000" + String.join("\u0001",entries);
    }

    public boolean approved(Music.Song song, Map<String,Integer> assignments,
                            List<CharacterInfo> roster) {
        return approvedFingerprint != null && approvedFingerprint.equals(fingerprint(song,assignments,roster));
    }

    public void approve(Music.Song song, Map<String,Integer> assignments,
                        List<CharacterInfo> roster) {
        if (conflicts(song,assignments,roster).isEmpty())
            throw new IllegalArgumentException("No shared parts to approve");
        approvedFingerprint=fingerprint(song,assignments,roster);
    }

    /** Called on song/roster/assignment changes, including intermediate edits. */
    public void invalidateIfChanged(Music.Song song, Map<String,Integer> assignments,
                                    List<CharacterInfo> roster) {
        if (!approved(song,assignments,roster)) approvedFingerprint=null;
    }

    public static String warning(List<Conflict> overlaps) {
        StringBuilder result = new StringBuilder("These ABC parts are assigned to more than one musician:\n\n");
        for (Conflict overlap : overlaps) {
            result.append("X:").append(overlap.trackId()).append(" — ").append(overlap.title()).append("\n");
            for (String musician : overlap.musicians()) result.append("    • ").append(musician).append("\n");
            result.append("\n");
        }
        return result + "If sharing these parts is intentional, allow this assignment.\n"
                + "Conductor will remember your approval until the song or assignments change.\n"
                + "Otherwise, cancel and correct the assignments.";
    }
}
