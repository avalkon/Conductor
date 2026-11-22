package org.lotro.conductor;

import java.io.IOException;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Read ONLY SongBook3 scanner's Lua table *data*; never execute Lua code.
 * The real ABC on disk remains the authority for track IDs and SHA-256.
 */
public final class SongBookCatalog {
    private SongBookCatalog() { }
    public record Entry(Path file, String filename, String title, String artist, Map<Integer,String> instruments, int partCount, String duration) {
        public boolean matchesSong(String query) {
            String q = query.toLowerCase(Locale.ROOT);
            return filename.toLowerCase(Locale.ROOT).contains(q) || title.toLowerCase(Locale.ROOT).contains(q);
        }
        public boolean matchesArtist(String query) {
            return artist.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
        }
    }
    public record Index(List<Path> directories, List<Path> files, Map<Path,Entry> entries) { }

    public static Index read(Path musicDir, Path dataFile) throws IOException {
        Path root = musicDir.toAbsolutePath().normalize();
        if (!Files.isRegularFile(dataFile)) throw new IOException("SongBook3 library not found: " + dataFile);
        if (Files.size(dataFile) > 64 * 1024 * 1024L) throw new IOException("SongBook3 library exceeds 64 MiB safety limit");
        // The supplied real-world file mixes UTF-8 titles and single-byte
        // Windows-1252 artist names. Decode valid UTF-8 runs, falling back
        // only for individual invalid high bytes.
        String content = decodeMixed(Files.readAllBytes(dataFile));
        Table top = new Parser(content).read();
        Table songs = asTable(top.fields.get("Songs"));
        if (songs == null) throw new IOException("SongBook3Data has no Songs table");
        LinkedHashMap<Path,Entry> entries = new LinkedHashMap<>();
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        dirs.add(root);
        Table allDirs = asTable(top.fields.get("Directories"));
        if (allDirs != null) for (Object item : allDirs.items) {
            Path directory = safePath(root, asString(item), false);
            if (directory != null) addAncestors(root, directory, dirs);
        }
        for (Object object : songs.items) {
            Table data = asTable(object);
            if (data == null) continue;
            String filename = asString(data.fields.get("Filename"));
            String filepath = asString(data.fields.get("Filepath"));
            if (filename.isBlank() || filepath.isBlank()) continue;
            Path file = safePath(root, filepath + (filepath.endsWith("/") ? "" : "/") + filename + ".abc", true);
            if (file == null) continue;
            Table tracks = asTable(data.fields.get("Tracks"));
            String title = filename, artist = asString(data.fields.get("Artist"));
            int partCount = tracks == null ? 0 : tracks.items.size();
            String duration = durationFrom(asString(data.fields.get("Duration")));
            if (duration.isEmpty()) duration = durationFrom(asString(data.fields.get("Length")));
            Map<Integer,String> instruments = new LinkedHashMap<>();
            if (tracks != null) for (Object trackValue : tracks.items) {
                Table track = asTable(trackValue);
                if (track == null) continue;
                String partTitle = asString(track.fields.get("Name"));
                if (title.equals(filename) && !partTitle.isBlank()) title = partTitle;
                if (duration.isEmpty()) duration = durationFrom(partTitle);
                if (artist.isBlank()) artist = asString(track.fields.get("Artist"));
                String partName = asString(track.fields.get("PartName"));
                try {
                    int id = Integer.parseInt(asString(track.fields.get("Id")));
                    if (id > 0 && !partName.isBlank()) instruments.put(id,partName);
                } catch (NumberFormatException ignored) { }
            }
            entries.putIfAbsent(file, new Entry(file,filename,title,artist,Map.copyOf(instruments),partCount,duration));
            addAncestors(root,file.getParent(),dirs);
        }
        if (entries.isEmpty()) throw new IOException("SongBook3 library contains no valid song paths");
        Comparator<Path> ordering = Comparator.comparing(p -> p.toString().toLowerCase(Locale.ROOT));
        List<Path> allFolders = new ArrayList<>(dirs); allFolders.sort(ordering);
        List<Path> allFiles = new ArrayList<>(entries.keySet()); allFiles.sort(ordering);
        return new Index(List.copyOf(allFolders),List.copyOf(allFiles),Map.copyOf(entries));
    }
    /** Duration is generally embedded in track Name, e.g. "Song (3:05) - Lute".
     * Never fabricate a time when SongBook does not supply one. */
    private static String durationFrom(String label) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<![0-9])([0-9]{1,3}):([0-9]{2})(?![0-9])").matcher(label);
        while(m.find()) if(Integer.parseInt(m.group(2))<60) return m.group(1)+":"+m.group(2);
        return "";
    }
    private static String decodeMixed(byte[] data) {
        StringBuilder result = new StringBuilder(data.length);
        int i=0;
        while(i<data.length) {
            int start=i;
            while(i<data.length && (data[i]&0x80)==0)i++;
            if(i>start) result.append(new String(data,start,i-start,StandardCharsets.US_ASCII));
            if(i==data.length) break;
            int first=data[i]&0xff;
            int length=first>=0xC2&&first<=0xDF?2:first>=0xE0&&first<=0xEF?3:first>=0xF0&&first<=0xF4?4:1;
            boolean valid=length>1 && i+length<=data.length;
            for(int k=1;k<length&&valid;k++)valid=(data[i+k]&0xC0)==0x80;
            if(valid) {
                try {
                    result.append(StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(data,i,length)));
                    i+=length;continue;
                }catch(CharacterCodingException invalid){/* legacy byte */}
            }
            result.append(new String(data,i,1,Charset.forName("windows-1252")));
            i++;
        }
        return result.toString();
    }
    private static void addAncestors(Path root, Path path, Set<Path> folders) {
        for (Path p = path; p != null && p.startsWith(root); p = p.getParent()) folders.add(p);
    }
    private static Path safePath(Path root, String value, boolean file) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0) return null;
        String relative = value.startsWith("/") ? value.substring(1) : value;
        if (relative.startsWith("/") || relative.isBlank()) return null;
        for (String component : relative.split("/")) if (component.equals("..") || component.equals(".")) return null;
        if (file && !relative.toLowerCase(Locale.ROOT).endsWith(".abc")) return null;
        try {
            Path result = root.resolve(relative).normalize();
            return result.startsWith(root) ? result : null;
        } catch (InvalidPathException notRepresentableOnThisHost) { return null; }
    }
    private static Table asTable(Object object) { return object instanceof Table t ? t : null; }
    private static String asString(Object object) { return object instanceof String s ? s : ""; }
    private static final class Table {
        final Map<String,Object> fields = new LinkedHashMap<>();
        final List<Object> items = new ArrayList<>();
    }
    /** Tiny bounded grammar for scanner output: return {...} with table/string/number literals. */
    private static final class Parser {
        final String source; int pos; int fields;
        Parser(String source) { this.source = source; }
        Table read() throws IOException {
            skip(); if (!word("return")) fail("Expected return of a Lua table");
            Object value = value(0); skip();
            if (pos != source.length()) fail("Trailing executable Lua or unsupported content");
            if (!(value instanceof Table t)) throw new IOException("Expected SongBook3 Lua table");
            return t;
        }
        boolean word(String expected) {skip(); if (source.startsWith(expected,pos) &&
                (pos+expected.length()==source.length() || !Character.isLetterOrDigit(source.charAt(pos+expected.length())))) {
            pos+=expected.length();return true; }return false; }
        void skip() {
            while (pos < source.length()) {
                char ch = source.charAt(pos);
                if (Character.isWhitespace(ch) || ch=='\uFEFF') {pos++;continue;}
                if (ch=='-' && pos+1<source.length() && source.charAt(pos+1)=='-') {
                    pos+=2; while(pos<source.length() && source.charAt(pos)!='\n') pos++; continue;
                }
                break;
            }
        }
        Object value(int depth) throws IOException {
            if (depth > 15) fail("Table nesting too deep");
            skip();if (pos >= source.length()) fail("Unexpected end of file");
            char ch=source.charAt(pos);
            if(ch=='{') return table(depth+1);
            if(ch=='\"' || ch=='\'') return quoted();
            String literal=identifier();
            if(literal.isEmpty())fail("Expected string, number or table");
            return literal;
        }
        Table table(int depth) throws IOException {
            if(source.charAt(pos++)!='{') fail("Expected table");
            Table table=new Table();
            while(true) {
                skip();if(pos>=source.length())fail("Unclosed table");
                if(source.charAt(pos)=='}'){pos++;return table;}
                if(++fields>900000)fail("Too many library entries");
                int mark=pos;
                String field=identifier();
                skip();
                if(!field.isEmpty() && pos<source.length() && source.charAt(pos)=='=') {
                    pos++;table.fields.put(field,value(depth));
                }else {
                    pos=mark;
                    table.items.add(value(depth));
                }
                skip();
                if(pos<source.length() && (source.charAt(pos)==';'||source.charAt(pos)==',')) pos++;
                else if(pos>=source.length() || source.charAt(pos)!='}')fail("Expected separator or }");
            }
        }
        String identifier() {
            skip();int begin=pos;
            while(pos<source.length()){
                char ch=source.charAt(pos);
                if(Character.isLetterOrDigit(ch)||ch=='_'||ch=='-'||ch=='.')pos++;
                else break;
            }
            return source.substring(begin,pos);
        }
        String quoted() throws IOException {
            char quote=source.charAt(pos++);StringBuilder out=new StringBuilder();
            while(pos<source.length()) {
                char ch=source.charAt(pos++);
                if(ch==quote)return out.toString();
                if(ch=='\\'){
                    if(pos>=source.length())fail("Unfinished string escape");
                    char escaped=source.charAt(pos++);
                    switch(escaped){
                        case 'n' -> out.append('\n'); case 'r' -> out.append('\r');
                        case 't' -> out.append('\t'); case '\\' -> out.append('\\');
                        case '\"' -> out.append('"'); case '\'' -> out.append('\'');
                        default -> { if(Character.isDigit(escaped)) fail("Unsupported Lua string escape"); out.append(escaped); }
                    }
                }else out.append(ch);
                if(out.length()>10000)fail("Library string too long");
            }
            fail("Unclosed quoted string");return "";
        }
        void fail(String message) throws IOException {throw new IOException(message+" at byte/character offset "+pos);}
    }
}
