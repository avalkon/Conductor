package org.lotro.conductor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Ordered, versioned Planner setlist. Import never issues game input. */
public record ConductorSetlist(String name, List<Entry> songs) {
    public record Assignment(int trackId, String character, String instrument) { }
    public record Entry(String gamePath, List<Assignment> parts) {
        @Override public String toString(){return gamePath + "  (" + parts.size() + " assignments)";}
    }
    public ConductorSetlist { songs=List.copyOf(songs); }
    private static Map<?,?> object(Object o,String label) throws IOException {
        if(!(o instanceof Map<?,?> m)) throw new IOException(label+" must be an object");
        return m;
    }
    private static List<?> list(Object o,String label) throws IOException {
        if(!(o instanceof List<?> l)) throw new IOException(label+" must be a list");
        return l;
    }
    private static String str(Object v,String label) throws IOException {
        if(!(v instanceof String s)) throw new IOException(label+" must be a string");
        if(s.length()>300) throw new IOException(label+" is too long");
        return s;
    }
    private static int number(Object value,String label) throws IOException {
        if(!(value instanceof Long n) || n<1 || n>9999) throw new IOException(label+" must be a positive track ID");
        return n.intValue();
    }
    public static ConductorSetlist read(Path path) throws IOException {
        if(Files.size(path)>4_000_000) throw new IOException("Setlist exceeds 4 MB");
        Map<?,?> root=object(SimpleJson.parse(Files.readString(path,StandardCharsets.UTF_8)),"Setlist");
        if(!"lotro-conductor-setlist".equals(root.get("format")) || !Long.valueOf(1).equals(root.get("version")))
            throw new IOException("Not a Conductor setlist v1 file");
        String name=str(root.get("name"),"Setlist name");
        List<?> entries=list(root.get("songs"),"songs");
        if(entries.size()>2000) throw new IOException("Setlist contains too many songs");
        List<Entry> songs=new ArrayList<>();
        for(Object raw: entries){
            Map<?,?> item=object(raw,"Song");
            String gamePath=str(item.get("gamePath"),"gamePath");
            Music.validateGamePath(gamePath);
            List<?> parts=list(item.get("parts"),"parts");
            if(parts.size()>500) throw new IOException("Too many parts in one song");
            List<Assignment> assignments=new ArrayList<>();
            // Multiple different characters may intentionally play the same ABC part.
            // A character must still have at most one assignment per setlist song.
            Set<String> usedMembers=new HashSet<>();
            for(Object rawPart:parts){
                Map<?,?> part=object(rawPart,"Part");
                int id=number(part.get("trackId"),"trackId");
                String charName=str(part.get("character"),"character").strip();
                String instr=str(part.get("instrument"),"instrument").strip();
                if(!charName.matches("[\\p{L}0-9_-]{1,60}") ||
                        !usedMembers.add(charName.toLowerCase(Locale.ROOT)))
                    throw new IOException("Duplicate/invalid character in " + gamePath);
                assignments.add(new Assignment(id,charName,instr));
            }
            songs.add(new Entry(gamePath,List.copyOf(assignments)));
        }
        return new ConductorSetlist(name,songs);
    }
    public Path fileFor(Path musicDir,Entry entry) throws IOException {
        Path root=musicDir.toAbsolutePath().normalize();
        Path file=root.resolve(entry.gamePath().substring(1)+".abc").normalize();
        if(!file.startsWith(root)) throw new IOException("Unsafe setlist path");
        if(Files.isRegularFile(file)) return file;
        // Conductor 0.6.0's Planner exporter accidentally removed the top-level
        // Songs directory. Recover only that precise legacy layout, never do a
        // recursive filename search that might silently choose the wrong ABC.
        String relative=entry.gamePath().substring(1);
        if(!relative.split("/",2)[0].equalsIgnoreCase("songs")) {
            List<Path> matches=new ArrayList<>();
            for(String dirname:List.of("songs","Songs")) {
                Path candidate=root.resolve(dirname).resolve(relative+".abc").normalize();
                if(!candidate.startsWith(root) || !Files.isRegularFile(candidate)) continue;
                boolean duplicate=false;
                for(Path existing:matches) if(Files.isSameFile(existing,candidate)) {duplicate=true;break;}
                if(!duplicate) matches.add(candidate);
            }
            if(matches.size()==1) return matches.get(0);
            if(matches.size()>1)
                throw new IOException("Ambiguous legacy setlist path (both songs/ and Songs/ match): " + entry.gamePath());
        }
        throw new IOException("Song file not found: " + file
                + " (check that the Planner export includes the Songs/ directory)");
    }
    public Map<String,Integer> assignmentsFor(Entry entry,List<CharacterInfo> roster,Music.Song song)
            throws IOException {
        Set<Integer> knownTracks=new HashSet<>();
        for(Music.Part part:song.parts())knownTracks.add(part.id());
        Map<String,Integer> mapped=new HashMap<>();
        for(Assignment part:entry.parts()){
            if(!knownTracks.contains(part.trackId()))
                throw new IOException("Setlist track X:"+part.trackId()+" is missing from " + entry.gamePath());
            CharacterInfo matching=null;
            for(CharacterInfo member:roster) if(member.name().equalsIgnoreCase(part.character())) {
                if(matching!=null) throw new IOException("Ambiguous character name across computers: "+part.character());
                matching=member;
            }
            if(matching==null) throw new IOException("Setlist musician not registered: " + part.character());
            mapped.put(matching.key(),part.trackId());
        }
        return mapped;
    }
}
