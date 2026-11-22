package org.lotro.conductor;

import java.util.*;

/** SongBook-style browse captions. Metadata is read from SongBook3Data, not ABC timing analysis. */
public enum SongDisplayFormat {
    PARTS_TITLE_ARTIST_TIME("(n) Title - Artist (mm:ss)", true, false, true, false),
    ARTIST_TITLE_TIME_PARTS("Artist - Title (mm:ss) (n)", false, false, true, true),
    PARTS_FILENAME_ARTIST_TIME("(n) Filename - Artist (mm:ss)", true, true, false, false),
    PARTS_FILENAME_TITLE_ARTIST_TIME("(n) Filename / Title - Artist (mm:ss)", true, true, true, false),
    TITLE_ARTIST_TIME_PARTS("Title - Artist (mm:ss) (n)", false, false, true, true),
    FILENAME_TITLE_TIME_PARTS("Filename / Title - Artist (mm:ss) (n)", false, true, true, true),
    PARTS_ARTIST_TITLE_TIME("(n) Artist - Title (mm:ss)", true, false, true, true),
    FILENAME_ONLY("Filename", false, true, false, false);

    private final String label;
    private final boolean partsFirst, filename, title, artistFirst;
    SongDisplayFormat(String label, boolean partsFirst, boolean filename, boolean title, boolean artistFirst) {
        this.label=label;this.partsFirst=partsFirst;this.filename=filename;this.title=title;this.artistFirst=artistFirst;
    }
    @Override public String toString() {return label;}
    public static SongDisplayFormat parse(String name) {
        try{return valueOf(name);}catch(IllegalArgumentException | NullPointerException ignored){return PARTS_TITLE_ARTIST_TIME;}
    }
    public String render(SongBookCatalog.Entry entry) {
        if(entry==null) return "";
        String file=entry.filename();
        String cleanTitle=entry.title().replaceAll("\\s*\\([0-9]{1,3}:[0-9]{2}\\)", " ")
                .replaceAll("\\s+", " ").strip();
        if(cleanTitle.isEmpty())cleanTitle=file;
        String artist=entry.artist().strip();
        String partLabel=entry.partCount()>0?"("+entry.partCount()+")":"";
        String time=entry.duration().isBlank()?"":"("+entry.duration()+")";
        List<String> elements=new ArrayList<>();
        if(filename)elements.add(file);
        if(title && (!filename || !cleanTitle.equalsIgnoreCase(file)))elements.add(cleanTitle);
        if(elements.isEmpty())elements.add(cleanTitle);
        String subject=String.join(" / ",elements);
        String desc=artist.isEmpty()?subject:(artistFirst?artist+" - "+subject:subject+" - "+artist);
        if(partsFirst && !partLabel.isEmpty())desc=partLabel+" "+desc;
        if(!time.isEmpty() && this!=FILENAME_ONLY)desc+=" "+time;
        if(!partsFirst && !partLabel.isEmpty() && this!=FILENAME_ONLY)desc+=" "+partLabel;
        return desc;
    }
}
