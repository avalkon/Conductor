package org.lotro.conductor;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable ABC browser; can use SongBook3's scanner index without executing its Lua. */
public record MusicLibraryIndex(Path root, List<Path> directories, List<Path> songs,
                                Map<Path,SongBookCatalog.Entry> metadata) {
    public MusicLibraryIndex(Path root, List<Path> directories, List<Path> songs) {
        this(root, directories, songs, Map.of());
    }
    public MusicLibraryIndex {
        root = root.toAbsolutePath().normalize();
        directories = List.copyOf(directories);
        songs = List.copyOf(songs);
        metadata = Map.copyOf(metadata);
    }
    public SongBookCatalog.Entry details(Path path) { return metadata.get(path); }
    public static MusicLibraryIndex fromSongBook(Path root, Path dataFile) throws IOException {
        SongBookCatalog.Index catalog = SongBookCatalog.read(root, dataFile);
        return new MusicLibraryIndex(root,catalog.directories(),catalog.files(),catalog.entries());
    }
    public static MusicLibraryIndex scan(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) return new MusicLibraryIndex(base, List.of(base), List.of());
        try (var paths = Files.walk(base, 12)) {
            List<Path> entries = paths.filter(path -> !path.equals(base))
                    .filter(path -> Files.isDirectory(path) || (Files.isRegularFile(path) &&
                            path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".abc")))
                    .limit(10000).toList();
            List<Path> dirs = new ArrayList<>(List.of(base));
            List<Path> songs = new ArrayList<>();
            for (Path path : entries) {
                if (Files.isDirectory(path)) dirs.add(path);
                else if (Files.isRegularFile(path)) songs.add(path);
            }
            Comparator<Path> byName = Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT));
            dirs.sort(byName); songs.sort(byName);
            return new MusicLibraryIndex(base, dirs, songs);
        }
    }
    public Music.Song addTrackInstruments(Music.Song song) {
        SongBookCatalog.Entry entry=details(song.file());
        if(entry==null || entry.instruments().isEmpty())return song;
        List<Music.Part> updated = new ArrayList<>();
        for(Music.Part part:song.parts()) {
            String instrument=entry.instruments().get(part.id());
            String title=part.title();
            if(instrument!=null && InstrumentMatcher.requiredInstrument(instrument)!=null
                    && !InstrumentMatcher.explicitlyMatches(title,instrument))
                title=title+" - "+instrument;
            updated.add(new Music.Part(part.id(),title));
        }
        return new Music.Song(song.file(),song.gamePath(),song.sha256(),List.copyOf(updated));
    }
    public List<Path> folders(Path current, String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) return directories.stream().filter(p -> !p.equals(root))
                .filter(p -> root.relativize(p).toString().toLowerCase(Locale.ROOT).contains(q)).toList();
        List<Path> result = new ArrayList<>();
        if (!current.equals(root) && current.startsWith(root)) result.add(current.getParent());
        directories.stream().filter(p -> !p.equals(current) && Objects.equals(p.getParent(), current))
                .forEach(result::add);
        return List.copyOf(result);
    }
    public List<Path> songs(Path current, String query) {return songs(current,query,"");}
    public List<Path> songs(Path current, String query, String artistQuery) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        String artist = artistQuery == null ? "" : artistQuery.strip().toLowerCase(Locale.ROOT);
        return songs.stream().filter(p -> {
            SongBookCatalog.Entry info=details(p);
            boolean songHit = q.isEmpty() || (info==null ? root.relativize(p).toString().toLowerCase(Locale.ROOT).contains(q)
                    : info.matchesSong(q) || root.relativize(p).toString().toLowerCase(Locale.ROOT).contains(q));
            boolean artistHit = artist.isEmpty() || (info!=null && info.matchesArtist(artist));
            return songHit && artistHit && ((q.isEmpty() && artist.isEmpty()) ? Objects.equals(p.getParent(),current) : true);
        }).toList();
    }
}
