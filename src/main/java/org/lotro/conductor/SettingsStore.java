package org.lotro.conductor;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Simple persisted UI preferences, separate from character profiles. */
public final class SettingsStore {
    private final Path file;
    private boolean confirmActions = true;
    private boolean autoAcceptMatches = false;
    private boolean autoEquipSetlist = false;
    private boolean skipUnchangedEquip = false;
    private boolean showDevLog = false;
    private boolean excludeLeaderMusic = false;
    private boolean refocusLeaderAfterSync = false;
    // Focus preferences apply to the controlling Conductor window, not remote agents.
    private boolean refocusConductorAfterMusicEquipSpace = false;
    private boolean refocusConductorAfterOtherCommands = false;
    private String nextFellowShortcut = "";
    public static final int CUSTOM_BUTTONS = 5;
    private final String[] customCommands = new String[CUSTOM_BUTTONS];
    private final String[] customTargets = new String[CUSTOM_BUTTONS];
    private String pluginDataRoot = "";
    private String songBookLibraryFile = "";
    private SongDisplayFormat songDisplayFormat = SongDisplayFormat.PARTS_TITLE_ARTIST_TIME;
    private int identifyWaitSeconds = 120;

    public SettingsStore(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        java.util.Arrays.fill(customCommands, "");
        java.util.Arrays.fill(customTargets, "All");
        if (Files.isRegularFile(this.file)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(this.file)) { props.load(in); }
            confirmActions = Boolean.parseBoolean(props.getProperty("confirmActions", "true"));
            autoAcceptMatches = Boolean.parseBoolean(props.getProperty("autoAcceptMatches", "false"));
            autoEquipSetlist = Boolean.parseBoolean(props.getProperty("autoEquipSetlist", "false"));
            skipUnchangedEquip = Boolean.parseBoolean(props.getProperty("skipUnchangedEquip", "false"));
            showDevLog = Boolean.parseBoolean(props.getProperty("showDevLog", "false"));
            excludeLeaderMusic = Boolean.parseBoolean(props.getProperty("excludeLeaderMusic", "false"));
            refocusLeaderAfterSync = Boolean.parseBoolean(props.getProperty("refocusLeaderAfterSync", "false"));
            refocusConductorAfterMusicEquipSpace = Boolean.parseBoolean(props.getProperty("refocusConductorAfterMusicEquipSpace", "false"));
            refocusConductorAfterOtherCommands = Boolean.parseBoolean(props.getProperty("refocusConductorAfterOtherCommands", "false"));
            String next = props.getProperty("selectFellow2Shortcut",props.getProperty("nextFellowShortcut", "")).strip();
            try { nextFellowShortcut = next.isEmpty() ? "" : Shortcut.parse(next).toString(); }
            catch (IllegalArgumentException e) { nextFellowShortcut = ""; }
            for(int i=0;i<CUSTOM_BUTTONS;i++) {
                String command=props.getProperty("custom."+i+".command","");
                try {customCommands[i]=CommandValidator.validOrEmpty(command);}
                catch (IllegalArgumentException ignored) {customCommands[i]="";}
                String target=props.getProperty("custom."+i+".target","All");
                customTargets[i]=target.length()>90 || target.isBlank()?"All":target;
            }
            pluginDataRoot = props.getProperty("pluginDataRoot", "");
            songBookLibraryFile = props.getProperty("songBookLibraryFile", "");
            songDisplayFormat = SongDisplayFormat.parse(props.getProperty("songDisplayFormat", ""));
            try { identifyWaitSeconds = Integer.parseInt(props.getProperty("identifyWaitSeconds", "120")); }
            catch (NumberFormatException ignored) { identifyWaitSeconds = 120; }
            if (identifyWaitSeconds < 15 || identifyWaitSeconds > 600) identifyWaitSeconds = 120;
        }
    }
    public Path path() { return file; }
    public boolean confirmActions() { return confirmActions; }
    public boolean autoAcceptMatches() { return autoAcceptMatches; }
    public void setConfirmActions(boolean value) throws IOException { confirmActions = value; save(); }
    public void setAutoAcceptMatches(boolean value) throws IOException { autoAcceptMatches = value; save(); }
    public boolean autoEquipSetlist() { return autoEquipSetlist; }
    public void setAutoEquipSetlist(boolean value) throws IOException { autoEquipSetlist = value; save(); }
    public boolean skipUnchangedEquip() { return skipUnchangedEquip; }
    public void setSkipUnchangedEquip(boolean value) throws IOException { skipUnchangedEquip = value; save(); }
    public boolean showDevLog() { return showDevLog; }
    public void setShowDevLog(boolean value) throws IOException { showDevLog = value; save(); }
    public boolean refocusLeaderAfterSync() { return refocusLeaderAfterSync; }
    public void setRefocusLeaderAfterSync(boolean value) throws IOException {
        refocusLeaderAfterSync = value;
        save();
    }
    public boolean refocusConductorAfterMusicEquipSpace() { return refocusConductorAfterMusicEquipSpace; }
    public void setRefocusConductorAfterMusicEquipSpace(boolean enabled) throws IOException {
        refocusConductorAfterMusicEquipSpace = enabled; save();
    }
    public boolean refocusConductorAfterOtherCommands() { return refocusConductorAfterOtherCommands; }
    public void setRefocusConductorAfterOtherCommands(boolean enabled) throws IOException {
        refocusConductorAfterOtherCommands = enabled; save();
    }
    public boolean excludeLeaderMusic() { return excludeLeaderMusic; }
    public void setExcludeLeaderMusic(boolean value) throws IOException { excludeLeaderMusic = value; save(); }
    public String nextFellowShortcut() { return nextFellowShortcut; } // old API compatibility
    public String fellowTwoShortcut() {return nextFellowShortcut;}
    public void setFellowTwoShortcut(String value) throws IOException {setNextFellowShortcut(value);}
    public String customCommand(int i) {return customCommands[index(i)];}
    public String customTarget(int i) {return customTargets[index(i)];}
    private static int index(int i) {
        if(i<0 || i>=CUSTOM_BUTTONS)throw new IllegalArgumentException("Invalid custom button number");
        return i;
    }
    public void setCustomButton(int i,String command,String target) throws IOException {
        i=index(i);
        String clean=CommandValidator.validOrEmpty(command);
        String cleanTarget=target==null?"All":target.strip();
        if(cleanTarget.isBlank() || cleanTarget.length()>90 || cleanTarget.contains("\n") || cleanTarget.contains("\r"))
            throw new IllegalArgumentException("Invalid custom command recipient");
        String beforeCommand=customCommands[i], beforeTarget=customTargets[i];
        customCommands[i]=clean;customTargets[i]=cleanTarget;
        try {save();} catch(IOException e){customCommands[i]=beforeCommand;customTargets[i]=beforeTarget;throw e;}
    }
    public void setNextFellowShortcut(String value) throws IOException {
        String key = value == null ? "" : value.strip();
        nextFellowShortcut = key.isEmpty() ? "" : Shortcut.parse(key).toString();
        save();
    }
    public SongDisplayFormat songDisplayFormat() {return songDisplayFormat;}
    public void setSongDisplayFormat(SongDisplayFormat format) throws IOException {
        songDisplayFormat = java.util.Objects.requireNonNull(format); save();
    }
    public String songBookLibraryFile() { return songBookLibraryFile; }
    public void setSongBookLibraryFile(String path) throws IOException {
        String clean = path == null ? "" : path.strip();
        if (clean.length() > 4096 || clean.contains("\n") || clean.contains("\r"))
            throw new IllegalArgumentException("Invalid SongBook3 library path");
        songBookLibraryFile = clean; save();
    }
    public String pluginDataRoot() { return pluginDataRoot; }
    public void setPluginDataRoot(String path) throws IOException { pluginDataRoot = path; save(); }
    public int identifyWaitSeconds() { return identifyWaitSeconds; }
    public void setIdentifyWaitSeconds(int seconds) throws IOException {
        if (seconds < 15 || seconds > 600) throw new IllegalArgumentException("Wait must be between 15 and 600 seconds");
        identifyWaitSeconds = seconds; save();
    }
    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("confirmActions", Boolean.toString(confirmActions));
        p.setProperty("autoAcceptMatches", Boolean.toString(autoAcceptMatches));
        p.setProperty("autoEquipSetlist", Boolean.toString(autoEquipSetlist));
        p.setProperty("skipUnchangedEquip", Boolean.toString(skipUnchangedEquip));
        p.setProperty("showDevLog", Boolean.toString(showDevLog));
        p.setProperty("excludeLeaderMusic", Boolean.toString(excludeLeaderMusic));
        p.setProperty("refocusLeaderAfterSync", Boolean.toString(refocusLeaderAfterSync));
        p.setProperty("refocusConductorAfterMusicEquipSpace", Boolean.toString(refocusConductorAfterMusicEquipSpace));
        p.setProperty("refocusConductorAfterOtherCommands", Boolean.toString(refocusConductorAfterOtherCommands));
        p.setProperty("nextFellowShortcut", nextFellowShortcut);
        p.setProperty("selectFellow2Shortcut",nextFellowShortcut);
        for(int i=0;i<CUSTOM_BUTTONS;i++) {
            p.setProperty("custom."+i+".command",customCommands[i]);
            p.setProperty("custom."+i+".target",customTargets[i]);
        }
        p.setProperty("pluginDataRoot", pluginDataRoot);
        p.setProperty("songBookLibraryFile", songBookLibraryFile);
        p.setProperty("songDisplayFormat",songDisplayFormat.name());
        p.setProperty("identifyWaitSeconds", Integer.toString(identifyWaitSeconds));
        Path tmp = Files.createTempFile(file.getParent(), "settings-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) { p.store(out, "LOTRO Conductor preferences"); }
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
}
