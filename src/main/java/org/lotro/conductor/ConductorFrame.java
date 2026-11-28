package org.lotro.conductor;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.nio.file.Files;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.time.Duration;

/** Compact Swing desktop UI; never embeds a web browser or game view. */
public final class ConductorFrame extends JFrame {
    private final Options options;
    private final GameBackend backend;
    private final LocalAgent agent;
    private final ProfileStore profiles;
    private final SettingsStore settings;
    private final LoginPanel loginPanel;
    private final DefaultListModel<Path> musicFoldersModel = new DefaultListModel<>();
    private final DefaultListModel<Path> musicSongsModel = new DefaultListModel<>();
    private final JList<Path> musicFolders = new JList<>(musicFoldersModel);
    private final JList<Path> musicSongs = new JList<>(musicSongsModel);
    private final JTextField folderSearch = new JTextField(13);
    private final JTextField songSearch = new JTextField(13);
    private final JTextField artistSearch = new JTextField(13);
    /** Instrument choices from the last song sent to Sync All, not from slow bridge mode reports. */
    private final Map<String,String> lastSyncedInstruments = new HashMap<>();
    private final JPanel searchSidebar = new JPanel();
    private JSplitPane searchSplit;
    private int lastSearchWidth = 185;
    private final JLabel folderLocation = new JLabel("Folders");
    private final JLabel songLocation = new JLabel("Songs");
    private MusicLibraryIndex libraryIndex;
    private Path currentMusicFolder;
    private boolean updatingMusicLists;
    private final java.util.concurrent.atomic.AtomicLong songSelectionRevision = new java.util.concurrent.atomic.AtomicLong();
    private Music.Song lastPreparedSong;
    private Map<String,Integer> lastPreparedAssignments = Map.of();
    private final JButton lastPreparedLink = new JButton("↶ Last synced song");
    private final DefaultListModel<Music.Part> songPartsModel = new DefaultListModel<>();
    private final JList<Music.Part> songPartsList = new JList<>(songPartsModel);
    private final JComboBox<CharacterInfo> musicCharacterChoice = new JComboBox<>();
    private final JComboBox<CharacterInfo> leaderChoice = new JComboBox<>();
    private final JButton[] customCommandButtons = new JButton[SettingsStore.CUSTOM_BUTTONS];
    @SuppressWarnings("unchecked")
    private final JComboBox<String>[] customCommandTargets = createCommandTargets();
    @SuppressWarnings("unchecked")
    private static JComboBox<String>[] createCommandTargets() {
        JComboBox<String>[] result = (JComboBox<String>[]) new JComboBox<?>[SettingsStore.CUSTOM_BUTTONS];
        for(int i=0;i<result.length;i++) result[i]=new JComboBox<>();
        return result;
    }
    private final Map<String,JButton> slotAssignmentButtons = new HashMap<>();
    private boolean updatingProfiles;
    private boolean updatingCommandTargets;
    private final JComboBox<String> savedProfiles = new JComboBox<>();
    private List<CharacterInfo> pendingRoster;
    private Map<String,Set<String>> pendingCapabilities;
    private Map<String,Set<String>> bandCapabilities=Map.of();
    private int expectedRosterSize;
    private long pendingRosterRevision;
    private long acceptedRosterRevision = -1;
    private final BandClient client;
    private final BandServer server;
    private final DefaultTableModel rosterModel = new DefaultTableModel(
            new Object[]{"Character", "Instrument", "Computer", "Game window", "Assigned X:", "Command status"}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable rosterTable = new JTable(rosterModel);
    private final Map<String,Integer> assignments = new HashMap<>();
    private final SharedTrackApproval sharedTrackApproval = new SharedTrackApproval();
    /** Focus return only when the group's network action results have been received. */
    private final CommandFocusTracker focusTracker = new CommandFocusTracker();
    private volatile long latestFocusRequest;
    private final Map<JButton,String[]> compactButtons = new LinkedHashMap<>();
    private final DefaultListModel<SetlistRow> setlistSongsModel = new DefaultListModel<>();
    private final JList<SetlistRow> setlistSongs = new JList<>(setlistSongsModel);
    private final CardLayout songsCardLayout = new CardLayout();
    private final JPanel songsCards = new JPanel(songsCardLayout);
    private boolean setlistView;
    private final java.util.concurrent.atomic.AtomicLong setlistNavigationRevision = new java.util.concurrent.atomic.AtomicLong();
    private record SetlistRow(int index,ConductorSetlist.Entry entry) {
        @Override public String toString() {
            String path=entry.gamePath(); int slash=path.lastIndexOf('/');
            return (index+1)+". "+(slash<0?path:path.substring(slash+1))+"   ("+entry.parts().size()+" parts)";
        }
    }
    private ConductorSetlist importedSetlist;
    private boolean updatingSetlist;

    private List<CharacterInfo> roster = List.of();
    private String selectedRosterKey;
    private boolean rebuildingTable;
    private final JComboBox<GameWindow> windows = new JComboBox<>();
    private final JTextField characterField = new JTextField(13);
    private final JTextField instrumentField = new JTextField(16);
    private final JTextField bridgeFileField = new JTextField(25);
    private final JTextField pluginDataRootField = new JTextField(25);
    private final JSpinner identifyWaitSpinner = new JSpinner();
    private final AtomicBoolean cancelIdentification = new AtomicBoolean(false);
    private JButton identifyButton;
    private JButton cancelIdentifyButton;
    private final JLabel songLabel = new JLabel("No ABC selected");
    private final JLabel countLabel = new JLabel("0 musicians");
    private final JLabel activityLabel = new JLabel("Ready");
    private JScrollPane devLogPanel;
    private final JTextArea console = new JTextArea(8, 90);
    private volatile boolean connected = true;
    private Music.Song song;
    private final ExecutorService workers = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r,"conductor-ui-worker"); t.setDaemon(true); return t;
    });
    public ConductorFrame(Options options, GameBackend backend, LocalAgent agent, BandClient client, BandServer server, ProfileStore profiles) {
        super("LOTRO Conductor 0.12.15 — " + options.nodeName() + (options.local() ? " [LOCAL]" : options.host() ? " [HOST]" : " [AGENT]"));
        this.options = options; this.backend = backend; this.agent = agent; this.client = client; this.server = server; this.profiles = profiles;
        try { this.settings = new SettingsStore(profiles.path().resolveSibling("settings.properties")); }
        catch (IOException e) { throw new IllegalStateException("Cannot load Conductor settings: " + e.getMessage(), e); }
        backend.setChatTiming(settings.chatTiming());
        backend.setChatPastePolicy(settings.pastePolicy());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });
        setSize(Integer.getInteger("conductor.window.width",1100),
                Integer.getInteger("conductor.window.height",730));
        setMinimumSize(new Dimension(590, 500));
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { updateCompactButtons(); }
        });
        setLocationByPlatform(true);
        setLayout(new BorderLayout(8,8));
        JPanel north = new JPanel(new BorderLayout(4,4));
        north.setBorder(BorderFactory.createEmptyBorder(8,8,0,8));
        JLabel status = new JLabel((options.local() ? "One-machine conductor (loopback only)" : options.host() ? "Master conductor" : "Remote computer: " + options.connectHost()) +
                "   •   " + (options.demo() ? "DEMO" : options.live() ? "LIVE INPUT" : "DRY RUN") +
                "   •   " + backend.description());
        north.add(status, BorderLayout.NORTH);
        north.add(countLabel, BorderLayout.EAST);
        activityLabel.setForeground(Color.GRAY);
        activityLabel.setFont(activityLabel.getFont().deriveFont(Font.PLAIN,11f));
        north.add(activityLabel, BorderLayout.SOUTH);
        add(north,BorderLayout.NORTH);
        // Single top navigation strip, with one leader selector shared by every page.
        CardLayout pageLayout = new CardLayout();
        JPanel pages = new JPanel(pageLayout);
        pages.add(createMusicPanel(),"music");
        pages.add(createRosterPanel(),"roster");
        pages.add(createLocalPanel(),"computer");
        pages.add(createSettingsPanel(),"settings");
        loginPanel=new LoginPanel(agent,options.live()&&!options.demo(),profiles.path().resolveSibling("settings.properties").getParent(),
                this::log,()->++latestFocusRequest,
                message->confirmAction(message,"Launcher login",JOptionPane.WARNING_MESSAGE),this::launcherSelected);
        pages.add(loginPanel,"login");
        JPanel navigation = new JPanel(new BorderLayout(5,0));
        JPanel tabButtons = new JPanel(new FlowLayout(FlowLayout.LEFT,2,0));
        ButtonGroup group = new ButtonGroup();
        String[][] tabNames = {{"Music", "music"},{"Band", "roster"},{"Computer", "computer"},{"Login", "login"},{"Settings", "settings"}};
        for (int i=0;i<tabNames.length;i++) {
            String[] item=tabNames[i];
            JToggleButton tab=new JToggleButton(item[0]);
            tab.setMargin(new Insets(3,5,3,5));
            tab.setFocusPainted(false);
            group.add(tab); tabButtons.add(tab);
            tab.addActionListener(e -> pageLayout.show(pages,item[1]));
            if(i==0)tab.setSelected(true);
        }
        navigation.add(tabButtons,BorderLayout.WEST);
        JPanel globalLeader = new JPanel(new FlowLayout(FlowLayout.RIGHT,3,0));
        globalLeader.add(new JLabel("Leader:"));
        leaderChoice.setPrototypeDisplayValue(new CharacterInfo("placeholder","LinuxPC","Longestname","","",""));
        leaderChoice.setPreferredSize(new Dimension(143,26));
        leaderChoice.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list,value,index,selected,focus);
                if(value instanceof CharacterInfo c)setText(c.name()+" ("+c.computer()+")");
                return this;
            }
        });
        globalLeader.add(leaderChoice);
        navigation.add(globalLeader,BorderLayout.EAST);
        JPanel center = new JPanel(new BorderLayout(2,2));
        center.add(navigation,BorderLayout.NORTH);
        center.add(pages,BorderLayout.CENTER);
        add(center,BorderLayout.CENTER);
        updateCompactButtons();
        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        console.setLineWrap(true); console.setWrapStyleWord(true);
        devLogPanel = new JScrollPane(console);
        devLogPanel.setBorder(BorderFactory.createTitledBorder("Development log"));
        devLogPanel.setPreferredSize(new Dimension(700, 145));
        devLogPanel.setVisible(settings.showDevLog());
        add(devLogPanel, BorderLayout.SOUTH);
        rosterTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rosterTable.setAutoCreateRowSorter(true);
        rosterTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || rebuildingTable) return;
            int row = rosterTable.getSelectedRow();
            if (row >= 0 && rosterTable.getRowCount() > 0) {
                int modelRow = rosterTable.convertRowIndexToModel(row);
                if (modelRow >= 0 && modelRow < roster.size()) selectedRosterKey = roster.get(modelRow).key();
            } else selectedRosterKey = null;
        });
        log("Local agent: " + backend.description());
        log("Music folder: " + options.musicDir());
        log("Saved character profiles: " + profiles.path());
        refreshProfiles();
        refreshQuickslots();
        pluginDataRootField.setText(settings.pluginDataRoot());
        log(options.demo() ? "Demo mode: keyboard input is always disabled" :
                options.live() ? "LIVE MODE enabled. Make sure LOTRO chat is closed before sending commands." :
                        "DRY-RUN mode enabled. Add --live when prepared for actual game-window input.");
        refreshWindows();
        refreshMusicLibrary();
        try { client.send("LIST"); } catch (Exception e) { log("Initial roster request failed: " + e.getMessage()); }
    }
    private JButton compactButton(String full, String compact) {
        JButton button = new JButton(full);
        button.setMargin(new Insets(3,5,3,5));
        button.setToolTipText(full);
        compactButtons.put(button,new String[]{full,compact});
        return button;
    }
    private void updateCompactButtons() {
        boolean small = getWidth() < 950;
        for (var entry:compactButtons.entrySet()) {
            String text = entry.getValue()[small?1:0];
            if (!entry.getKey().getText().equals(text)) entry.getKey().setText(text);
        }
    }
    private JPanel createRosterPanel() {
        JPanel root = new JPanel(new BorderLayout(4,4));
        root.setBorder(BorderFactory.createEmptyBorder(5,8,5,8));
        root.add(new JScrollPane(rosterTable),BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1,3,4,0));
        JButton report = compactButton("Request bridge report", "Report");
        report.addActionListener(e -> actSelected("REPORT")); actions.add(report);
        JButton music = compactButton("Toggle /music (selected)", "/music");
        music.addActionListener(e -> actSelected("MUSIC")); actions.add(music);
        JButton follow = compactButton("Follow (except leader)", "Follow");
        follow.setToolTipText("Send Select fellow 2, then /follow to every registered musician except the leader");
        follow.addActionListener(e -> followAllExceptLeader()); actions.add(follow);
        root.add(actions,BorderLayout.SOUTH);
        if(!options.host()) {report.setEnabled(false);music.setEnabled(false);follow.setEnabled(false);}
        return root;
    }
    /** The music tab follows SongBook: controls, directory/song browser, then part list. */
    private JPanel createMusicPanel() {
        JPanel root = new JPanel(new BorderLayout(5,5));
        root.setBorder(BorderFactory.createEmptyBorder(5,7,5,7));
        JPanel actions = new JPanel(new BorderLayout(4,0));
        JPanel actionButtons = new JPanel(new GridLayout(1,5,3,0));
        JButton musicAll = compactButton("Toggle /music (All)", "/music");
        musicAll.addActionListener(e -> musicAll()); actionButtons.add(musicAll);
        JButton assignAll = compactButton("Assign All", "Assign");
        assignAll.setToolTipText("Match every unassigned ABC part to saved instrument quickslots; prefer keeping the instrument from the previous synced song");
        assignAll.addActionListener(e -> suggestParts()); actionButtons.add(assignAll);
        JButton equip = compactButton("Equip instruments", "Equip");
        equip.setToolTipText("Equip assigned instruments; optional skip for unchanged shortcuts, no music toggles or bridge verification");
        equip.addActionListener(e -> equipCurrentSong()); actionButtons.add(equip);
        JButton prepare = compactButton("Sync All", "Sync");
        prepare.addActionListener(e -> dispatchParts("PREPARE")); actionButtons.add(prepare);
        JButton start = compactButton("/playstart", "Play");
        start.addActionListener(e -> startLeader(chosenLeader())); actionButtons.add(start);
        actions.add(actionButtons,BorderLayout.CENTER);
        DefaultListCellRenderer memberRenderer = new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected,boolean focus) {
                super.getListCellRendererComponent(list,value,index,selected,focus);
                if(value instanceof CharacterInfo c)setText(c.name()+" ("+c.computer()+")");
                return this;
            }
        };
        musicCharacterChoice.setRenderer(memberRenderer);
        JPanel top = new JPanel(new BorderLayout());
        top.add(actions,BorderLayout.NORTH);
        JPanel current = new JPanel(new BorderLayout(8,0));
        current.add(songLabel,BorderLayout.CENTER);
        JLabel safety = new JLabel("Commands sent; readiness appears when SongBook confirms.");
        safety.setForeground(Color.GRAY);
        current.add(safety,BorderLayout.EAST);
        top.add(current,BorderLayout.SOUTH);
        root.add(top,BorderLayout.NORTH);

        // Browse directories independently from songs, like SongBook3's library panel.
        JPanel browser = new JPanel(new BorderLayout(5,5));
        JButton refresh = compactButton("Refresh", "Refresh");
        refresh.addActionListener(e -> refreshMusicLibrary());
        JButton toggleSearch = compactButton("Search ▸", "Search");
        toggleSearch.addActionListener(e -> {
            searchSidebar.setVisible(!searchSidebar.isVisible());
            toggleSearch.setText(searchSidebar.isVisible() ? "Search ◂" : "Search ▸");
            if(searchSplit!=null) {
                if(searchSidebar.isVisible()) {
                    searchSplit.setDividerSize(6);
                    searchSplit.setDividerLocation(lastSearchWidth);
                } else {
                    int oldWidth=searchSplit.getDividerLocation();
                    if(oldWidth>=125)lastSearchWidth=oldWidth;
                    searchSplit.setDividerSize(0);
                    searchSplit.setDividerLocation(0);
                }
                searchSplit.revalidate();
            }
            browser.revalidate(); browser.repaint();
        });
        JPanel browserHeader = new JPanel(new BorderLayout(5,0));
        browserHeader.add(new JLabel("Music library"),BorderLayout.WEST);
        JPanel browserTools = new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));
        JButton importList = compactButton("Import setlist…", "Import");
        importList.addActionListener(e -> importPlannerSetlist());
        JButton toggleList = compactButton("Setlist / Library", "List");
        toggleList.setToolTipText("Switch between the ABC folder browser and the loaded setlist");
        toggleList.addActionListener(e -> switchSetlistView(!setlistView));
        JButton show = compactButton("Show in SongBook", "Show");
        show.setToolTipText("Select the assigned song and parts in SongBook");
        show.addActionListener(e -> dispatchParts("SELECT"));
        browserTools.add(importList); browserTools.add(toggleList); browserTools.add(show);
        browserTools.add(toggleSearch); browserTools.add(refresh);
        browserHeader.add(browserTools,BorderLayout.EAST);
        browser.add(browserHeader,BorderLayout.NORTH);

        JPanel folderPanel = new JPanel(new BorderLayout(0,2));
        folderPanel.add(folderLocation,BorderLayout.NORTH);
        folderPanel.add(new JScrollPane(musicFolders),BorderLayout.CENTER);
        JPanel songsPanel = new JPanel(new BorderLayout(0,2));
        songsPanel.add(songLocation,BorderLayout.NORTH);
        songsCards.add(new JScrollPane(musicSongs),"library");
        setlistSongs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setlistSongs.addListSelectionListener(e -> {
            if(e.getValueIsAdjusting() || updatingSetlist) return;
            SetlistRow row=setlistSongs.getSelectedValue();
            if(row!=null)openSetlistSong(row.index());
        });
        songsCards.add(new JScrollPane(setlistSongs),"setlist");
        songsPanel.add(songsCards,BorderLayout.CENTER);
        JSplitPane browserSections = new JSplitPane(JSplitPane.VERTICAL_SPLIT,folderPanel,songsPanel);
        browserSections.setResizeWeight(0.42);
        browserSections.setContinuousLayout(true);
        browserSections.setDividerSize(6);


        musicFolders.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        musicSongs.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        musicFolders.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,
                    boolean selected,boolean focus) {
                super.getListCellRendererComponent(list,value,index,selected,focus);
                if (value instanceof Path path) {
                    if (currentMusicFolder != null && !currentMusicFolder.equals(options.musicDir()) &&
                            path.equals(currentMusicFolder.getParent())) setText("..   (parent folder)");
                    else setText(path.getFileName() == null ? path.toString() : path.getFileName().toString());
                }
                return this;
            }
        });
        musicSongs.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,
                    boolean selected,boolean focus) {
                super.getListCellRendererComponent(list,value,index,selected,focus);
                if (value instanceof Path path) {
                    String name = path.getFileName().toString().replaceFirst("(?i)\\.abc$", "");
                    if (libraryIndex != null) {
                        SongBookCatalog.Entry info = libraryIndex.details(path);
                        if (info != null) name = settings.songDisplayFormat().render(info);
                    }
                    if ((!songSearch.getText().isBlank() || !artistSearch.getText().isBlank()) && libraryIndex != null)
                        name += "   [" + libraryIndex.root().relativize(path.getParent()) + "]";
                    setText(name);
                }
                return this;
            }
        });
        musicFolders.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingMusicLists) return;
            Path selected = musicFolders.getSelectedValue();
            if (selected != null && libraryIndex != null && libraryIndex.directories().contains(selected)) {
                currentMusicFolder = selected;
                updateMusicLists();
            }
        });
        musicSongs.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingMusicLists) return;
            Path selected = musicSongs.getSelectedValue();
            if (selected != null && Files.isRegularFile(selected)) loadSong(selected);
        });
        searchSidebar.setBorder(BorderFactory.createTitledBorder("Search"));
        searchSidebar.setLayout(new BoxLayout(searchSidebar,BoxLayout.Y_AXIS));
        searchSidebar.setPreferredSize(new Dimension(185, 200));
        searchSidebar.setMinimumSize(new Dimension(120,120));
        for (JTextField field : List.of(folderSearch,songSearch,artistSearch)) {
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE,27));
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        JLabel folderFindLabel = new JLabel("Folder name:");
        JLabel songFindLabel = new JLabel("Song name:");
        folderFindLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        songFindLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchSidebar.add(folderFindLabel); searchSidebar.add(folderSearch);
        searchSidebar.add(Box.createVerticalStrut(10));
        searchSidebar.add(songFindLabel); searchSidebar.add(songSearch);
        searchSidebar.add(Box.createVerticalStrut(10));
        JLabel artistLabel = new JLabel("Artist:");
        artistLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        artistSearch.setToolTipText("Search song/track Artist metadata from SongBook3Data.plugindata");
        searchSidebar.add(artistLabel); searchSidebar.add(artistSearch);
        searchSidebar.add(Box.createVerticalGlue());
        folderSearch.getDocument().addDocumentListener(changeListener(this::updateMusicLists));
        songSearch.getDocument().addDocumentListener(changeListener(this::updateMusicLists));
        artistSearch.getDocument().addDocumentListener(changeListener(this::updateMusicLists));
        searchSidebar.setVisible(false);
        searchSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,searchSidebar,browserSections);
        searchSplit.setResizeWeight(0.0);
        searchSplit.setContinuousLayout(true);
        searchSplit.setDividerSize(0);
        searchSplit.setDividerLocation(0);
        browser.add(searchSplit,BorderLayout.CENTER);

        JPanel partsPanel = new JPanel(new BorderLayout(3,3));
        JPanel partHeader = new JPanel(new BorderLayout());
        partHeader.add(new JLabel("Tracks and assignments"),BorderLayout.WEST);
        lastPreparedLink.setBorderPainted(false);
        lastPreparedLink.setContentAreaFilled(false);
        lastPreparedLink.setForeground(new Color(30,85,150));
        lastPreparedLink.setToolTipText("Return to the last song sent with Sync All (not an in-game playback confirmation)");
        lastPreparedLink.setEnabled(false);
        lastPreparedLink.addActionListener(e -> restoreLastPreparedSong());
        partHeader.add(lastPreparedLink,BorderLayout.EAST);
        partsPanel.add(partHeader,BorderLayout.NORTH);
        songPartsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        songPartsList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,
                    boolean selected,boolean focus) {
                super.getListCellRendererComponent(list,value,index,selected,focus);
                if (value instanceof Music.Part part) setText(partDisplay(part));
                setBorder(BorderFactory.createEmptyBorder(4,6,4,6));
                return this;
            }
        });
        partsPanel.add(new JScrollPane(songPartsList),BorderLayout.CENTER);
        JPanel chooser = new JPanel(new FlowLayout(FlowLayout.LEFT,3,2));
        chooser.add(new JLabel("Assign to:"));
        musicCharacterChoice.setPreferredSize(new Dimension(115,25));
        musicCharacterChoice.setMinimumSize(new Dimension(80,25));
        chooser.add(musicCharacterChoice);
        JButton assign = compactButton("Assign selected part", "Assign");
        assign.addActionListener(e -> assignSongPart());
        chooser.add(assign);
        assign.setToolTipText("Assign the selected track to this character; replaces their prior part");
        JPanel assignAndCustom = new JPanel(new BorderLayout(4,0));
        assignAndCustom.add(chooser,BorderLayout.WEST);
        JPanel customButtonsPanel = new JPanel(new GridLayout(1,SettingsStore.CUSTOM_BUTTONS,3,0));
        for(int i=0;i<customCommandButtons.length;i++) {
            final int index=i;
            JButton button=new JButton();
            button.setMargin(new Insets(2,4,2,4));
            button.addActionListener(e -> executeCustomCommand(index));
            customCommandButtons[i]=button;
            customButtonsPanel.add(button);
        }
        assignAndCustom.add(customButtonsPanel,BorderLayout.CENTER);
        JButton spaceAll=new JButton("␣");
        spaceAll.setMargin(new Insets(1,2,1,2));
        spaceAll.getAccessibleContext().setAccessibleName("Space (All)");
        spaceAll.setToolTipText("Send ONE Space keypress to each registered LOTRO window. No music-mode state is inferred.");
        spaceAll.addActionListener(e -> spaceAll());
        spaceAll.setPreferredSize(new Dimension(42,25));
        spaceAll.setEnabled(options.host());
        assignAndCustom.add(spaceAll,BorderLayout.EAST);
        partsPanel.add(assignAndCustom,BorderLayout.SOUTH);
        refreshCustomCommandButtons();

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT,browser,partsPanel);
        vertical.setResizeWeight(0.54);
        vertical.setContinuousLayout(true);
        vertical.setDividerSize(6);
        root.add(vertical,BorderLayout.CENTER);
        if (!options.host()) {
            musicAll.setEnabled(false); assignAll.setEnabled(false); prepare.setEnabled(false);
            start.setEnabled(false); show.setEnabled(false); equip.setEnabled(false); assign.setEnabled(false);
        }
        return root;
    }

    private static javax.swing.event.DocumentListener changeListener(Runnable onChange) {
        return new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onChange.run(); }
        };
    }

    private static String escapeHtml(String value) {
        return value.replace("&","&amp;").replace("<","&lt;")
                .replace(">","&gt;").replace("\"","&quot;");
    }
    private String partDisplay(Music.Part part) {
        List<String> names = roster.stream()
                .filter(c -> Objects.equals(assignments.get(c.key()),part.id()))
                .map(c -> c.name() + (c.instrument().isBlank() ? "" : " (" + c.instrument() + ")"))
                .toList();
        String players = names.isEmpty() ? "Unassigned" : String.join(", ", names);
        String shared = names.size() <= 1 ? "" : sharedTrackApproval.approved(song,assignments,roster)
                ? "  <span style='color:#39764c'>[shared: approved]</span>"
                : "  <span style='color:#966500'>[shared: approval needed]</span>";
        return "<html><b>X:" + part.id() + "</b>  " + escapeHtml(part.title()) + shared +
                "<br><span style='color:#777777'>→ " + escapeHtml(players) + "</span></html>";
    }

    private void restoreLastPreparedSong() {
        if (lastPreparedSong == null) return;
        loadSong(lastPreparedSong.file(),lastPreparedAssignments);
    }

    /** Scrollable setting sections track the viewport width instead of creating horizontal scrollbars. */
    private static final class SettingsColumn extends JPanel implements Scrollable {
        SettingsColumn() {setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));}
        @Override public Dimension getPreferredScrollableViewportSize() {return getPreferredSize();}
        @Override public int getScrollableUnitIncrement(Rectangle rect,int orientation,int direction){return 18;}
        @Override public int getScrollableBlockIncrement(Rectangle rect,int orientation,int direction){return Math.max(60,rect.height-35);}
        @Override public boolean getScrollableTracksViewportWidth(){return true;}
        @Override public boolean getScrollableTracksViewportHeight(){return false;}
    }
    private static void addSettingLine(SettingsColumn parent,JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
        parent.add(component);
    }
    private static void setWrappedText(JLabel label,String text,int width) {
        label.setText("<html><div style='width:"+Math.max(140,width)+"px;'>"+escapeHtml(text)+"</div></html>");
    }
    private JPanel createSettingsPanel() {
        JPanel root=new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(7,9,7,9));
        SettingsColumn controls=new SettingsColumn();
        controls.setBorder(BorderFactory.createEmptyBorder(2,3,2,3));
        // Track original labels: Swing's default checkbox and label texts do not wrap.
        Map<JCheckBox,String> checkLabels=new LinkedHashMap<>();
        Map<JLabel,String> noteLabels=new LinkedHashMap<>();

        JCheckBox confirm = new JCheckBox("Ask for confirmation before commands, assignments, login and deletion",settings.confirmActions());
        confirm.addActionListener(e -> {
            try {settings.setConfirmActions(confirm.isSelected()); log("Confirmations " +(confirm.isSelected()?"enabled":"disabled"));}
            catch(IOException ex){error("Cannot save settings: "+ex.getMessage());}
        });
        JCheckBox auto = new JCheckBox("Automatically assign parts using saved quickslots (prefer previous instrument)",settings.autoAcceptMatches());
        auto.addActionListener(e -> {
            try {settings.setAutoAcceptMatches(auto.isSelected());if(auto.isSelected())applyAutoSuggestions();}
            catch(IOException ex){error("Cannot save settings: "+ex.getMessage());}
        });
        JCheckBox autoEquip = new JCheckBox("Send mapped quickslot keys when selecting a setlist song (all connected computers)",settings.autoEquipSetlist());
        autoEquip.addActionListener(e -> {
            try {settings.setAutoEquipSetlist(autoEquip.isSelected());}
            catch(IOException ex){error("Cannot save auto-equip setting: "+ex.getMessage());}
        });
        JCheckBox skipRepeatEquip = new JCheckBox("Equip: skip characters whose last successful quickslot was the same instrument",settings.skipUnchangedEquip());
        skipRepeatEquip.setToolTipText(options.host()
                ? "Host preference: each agent tracks last successfully sent instrument and saved shortcut, without querying game state."
                : "Set this option on the master Conductor; the host chooses which Equip action to send.");
        skipRepeatEquip.setEnabled(options.host());
        skipRepeatEquip.addActionListener(e -> {
            try {settings.setSkipUnchangedEquip(skipRepeatEquip.isSelected());
                log("Skip unchanged Equip " + (skipRepeatEquip.isSelected()?"enabled":"disabled"));}
            catch(IOException ex){error("Cannot save Equip preference: "+ex.getMessage());}
        });
        JCheckBox excludeLeader = new JCheckBox("Exclude selected leader from Toggle /music (All)",settings.excludeLeaderMusic());
        excludeLeader.addActionListener(e -> {
            try {settings.setExcludeLeaderMusic(excludeLeader.isSelected());}
            catch(IOException ex){error("Cannot save music leader setting: "+ex.getMessage());}
        });
        JCheckBox refocusLeader = new JCheckBox("Refocus selected leader window after Sync All", settings.refocusLeaderAfterSync());
        refocusLeader.setToolTipText("Focus-only: queued after Sync All on the leader's computer; never sends keys or changes assignments.");
        refocusLeader.setEnabled(options.host());
        refocusLeader.addActionListener(e -> {
            try { settings.setRefocusLeaderAfterSync(refocusLeader.isSelected()); }
            catch (IOException ex) { error("Cannot save leader refocus setting: " + ex.getMessage()); }
        });
        JCheckBox refocusAfterPrimary = new JCheckBox(
                "Refocus Conductor after /music, Equip, and Space",
                settings.refocusConductorAfterMusicEquipSpace());
        refocusAfterPrimary.setToolTipText("After the commands complete on all involved computers, bring this Conductor window forward.");
        refocusAfterPrimary.setEnabled(options.host());
        refocusAfterPrimary.addActionListener(e -> {
            try { settings.setRefocusConductorAfterMusicEquipSpace(refocusAfterPrimary.isSelected()); }
            catch(IOException ex) { error("Cannot save Conductor refocus setting: " + ex.getMessage()); }
        });
        JCheckBox refocusAfterOther = new JCheckBox(
                "Refocus Conductor after other commands (except Sync All)",
                settings.refocusConductorAfterOtherCommands());
        refocusAfterOther.setToolTipText("Includes /playstart, Follow, Show in SongBook, custom commands, and Report. Sync All is excluded.");
        refocusAfterOther.setEnabled(options.host());
        refocusAfterOther.addActionListener(e -> {
            try { settings.setRefocusConductorAfterOtherCommands(refocusAfterOther.isSelected()); }
            catch(IOException ex) { error("Cannot save other-command refocus setting: " + ex.getMessage()); }
        });
        JCheckBox devLog = new JCheckBox("Show development log",settings.showDevLog());
        devLog.addActionListener(e -> {
            try {
                settings.setShowDevLog(devLog.isSelected());devLogPanel.setVisible(devLog.isSelected());
                getContentPane().revalidate();getContentPane().repaint();
            }catch(IOException ex){error("Cannot save log preference: "+ex.getMessage());}
        });
        for(JCheckBox check:List.of(confirm,auto,autoEquip,skipRepeatEquip,excludeLeader,refocusLeader,
                refocusAfterPrimary,refocusAfterOther,devLog)) {
            checkLabels.put(check,check.getText());
            addSettingLine(controls,check);
        }
        // Per-machine pacing, applied immediately to the local input backend.
        // Keep compact so the Settings tab stays usable at narrow widths.
        JPanel timingPanel = new JPanel(new GridLayout(5, 2, 5, 2));
        timingPanel.setBorder(BorderFactory.createTitledBorder("Chat typing timing (this computer, milliseconds)"));
        ChatInputTiming timing = settings.chatTiming();
        JSpinner openWait = new JSpinner(new SpinnerNumberModel(timing.chatOpenMs(), 0, 5000, 50));
        JSpinner keyWait = new JSpinner(new SpinnerNumberModel(timing.keyDelayMs(), 0, 200, 5));
        JSpinner beforeWait = new JSpinner(new SpinnerNumberModel(timing.beforeEnterMs(), 0, 5000, 50));
        JSpinner afterWait = new JSpinner(new SpinnerNumberModel(timing.afterEnterMs(), 0, 5000, 50));
        JSpinner perCharacterWait = new JSpinner(new SpinnerNumberModel(timing.afterEnterPerCharacterMs(), 0, 100, 5));
        String[] timingNames = {"After opening chat", "Between typed keys", "Before sending Enter",
                "Min. before next window", "Extra ms per typed char"};
        JSpinner[] timingInputs = {openWait, keyWait, beforeWait, afterWait, perCharacterWait};
        for (int i=0;i<timingInputs.length;i++) {
            timingPanel.add(new JLabel(timingNames[i]));
            timingPanel.add(timingInputs[i]);
        }
        javax.swing.event.ChangeListener pacingChanged = event -> {
            try {
                ChatInputTiming next = new ChatInputTiming((Integer)openWait.getValue(),(Integer)keyWait.getValue(),
                        (Integer)beforeWait.getValue(),(Integer)afterWait.getValue(),
                        (Integer)perCharacterWait.getValue());
                settings.setChatTiming(next);
                backend.setChatTiming(next);
                log("Local chat typing delays saved: " + next);
            } catch(Exception ex) { error("Cannot save chat timing: " + ex.getMessage()); }
        };
        for(JSpinner input:timingInputs) input.addChangeListener(pacingChanged);
        addSettingLine(controls,timingPanel);
        JLabel timingNote=new JLabel();
        timingNote.setFont(timingNote.getFont().deriveFont(Font.PLAIN,11f));
        timingNote.setForeground(Color.GRAY);
        noteLabels.put(timingNote,"After Enter, Conductor waits minimum + (typed characters × extra ms). Example: 100 ms + 20 characters × 15 ms = 400 ms. Maximum 60 seconds. Local per PC; sent is not game-confirmed.");
        addSettingLine(controls,timingNote);
        ChatPastePolicy currentPaste = settings.pastePolicy();
        JPanel pasteControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JCheckBox pasteCheck = new JCheckBox("Paste long commands (Ctrl+V)", currentPaste.enabled());
        pasteCheck.setToolTipText("This computer only. Long chat lines use Ctrl+V when clipboard access succeeds.");
        JCheckBox preserveClipboard = new JCheckBox("Preserve clipboard contents", currentPaste.preserveClipboard());
        preserveClipboard.setToolTipText("Keep checked to protect non-text/rich content. Unchecking permits replacing any existing clipboard item with the last pasted game command.");
        preserveClipboard.setEnabled(pasteCheck.isSelected());
        JSpinner pasteThreshold = new JSpinner(new SpinnerNumberModel(currentPaste.minimumCharacters(), 12, 512, 4));
        pasteThreshold.setEnabled(pasteCheck.isSelected());
        pasteControls.add(pasteCheck);
        pasteControls.add(new JLabel("At least:"));
        pasteControls.add(pasteThreshold);
        pasteControls.add(new JLabel("characters"));
        Runnable savePaste = () -> {
            try {
                ChatPastePolicy next = new ChatPastePolicy(pasteCheck.isSelected(), (Integer) pasteThreshold.getValue(), preserveClipboard.isSelected());
                settings.setPastePolicy(next);
                backend.setChatPastePolicy(next);
                pasteThreshold.setEnabled(next.enabled());
                preserveClipboard.setEnabled(next.enabled());
                log("Local long-command paste mode saved: " + next);
            } catch (Exception ex) { error("Cannot save clipboard paste setting: " + ex.getMessage()); }
        };
        pasteCheck.addActionListener(e -> savePaste.run());
        pasteThreshold.addChangeListener(e -> savePaste.run());
        addSettingLine(controls, pasteControls);
        preserveClipboard.addActionListener(e -> {
            if (!preserveClipboard.isSelected() && JOptionPane.showConfirmDialog(this,
                    "Allow Conductor to replace the clipboard, including images, files and formatted text?\nThe last pasted chat command will remain on the clipboard.",
                    "Allow clipboard replacement", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                preserveClipboard.setSelected(true);
                return;
            }
            savePaste.run();
        });
        addSettingLine(controls, preserveClipboard);
        JLabel pasteNote = new JLabel();
        pasteNote.setFont(pasteNote.getFont().deriveFont(Font.PLAIN,11f));
        pasteNote.setForeground(Color.GRAY);
        noteLabels.put(pasteNote, "Paste retries a busy clipboard and logs the reason for typing fallback. Preserve protects rich clipboard content and restores text where possible. If unchecked, the last pasted command replaces the clipboard. No game acknowledgment.");
        addSettingLine(controls, pasteNote);
        JButton resetEquipHistory = new JButton("Reset remembered Equip shortcuts (all computers)");
        resetEquipHistory.setToolTipText("Clear each agent's last successful Equip command. Next Equip can send the shortcut again.");
        resetEquipHistory.setEnabled(options.host());
        resetEquipHistory.addActionListener(e -> {
            if(!confirmAction("Clear remembered instrument shortcuts for all registered characters on connected computers?\n"
                    +"No in-game command will be sent.","Reset Equip history",JOptionPane.QUESTION_MESSAGE))return;
            for(CharacterInfo member:List.copyOf(roster)) sendAction(member,"EQUIP_RESET","","","");
            log("Requested Equip-history reset for "+roster.size()+" registered musician(s)");
        });
        addSettingLine(controls,resetEquipHistory);
        JPanel fellowRow=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
        fellowRow.add(new JLabel("Select fellow 2 keybind:"));
        JButton fellowButton=new JButton(settings.fellowTwoShortcut().isBlank()?"Assign":settings.fellowTwoShortcut());
        fellowButton.setToolTipText("Press/enter shortcut, Escape to cancel, Space to unassign");
        fellowButton.addActionListener(e -> ShortcutEditor.open(this,"Select fellow 2",
                settings.fellowTwoShortcut(),shortcut -> {
            try {
                settings.setFellowTwoShortcut(shortcut==null?"":shortcut.toString());
                fellowButton.setText(shortcut==null?"Assign":shortcut.toString());
            }catch(IOException ex){error("Cannot save fellow shortcut: "+ex.getMessage());}
        }));
        fellowRow.add(fellowButton);
        addSettingLine(controls,fellowRow);
        String[] notes={
            "Identification wait is 15–600 seconds (default 120); change it on This computer.",
            "Equip can skip unchanged instrument shortcuts when enabled. History is based on successful input, not bridge reports; manual in-game changes are unknown.",
            "Auto-equip sends mapped quickslots without /music toggles. Follow sends Select fellow 2 then /follow.",
            "Assign All prefers the last synced instrument; confirmation settings never disable window checks or command validation."
        };
        for(String text:notes) {
            JLabel note=new JLabel();
            note.setFont(note.getFont().deriveFont(Font.PLAIN,11f));
            note.setForeground(Color.GRAY);
            noteLabels.put(note,text);
            addSettingLine(controls,note);
        }
        JLabel settingsFile=new JLabel("Settings file: "+settings.path().getFileName());
        settingsFile.setToolTipText(settings.path().toString());
        settingsFile.setFont(settingsFile.getFont().deriveFont(Font.PLAIN,11f));
        settingsFile.setForeground(Color.GRAY);
        addSettingLine(controls,settingsFile);
        controls.add(Box.createVerticalStrut(5));

        JPanel libraryPanel=new JPanel(new BorderLayout(4,2));
        libraryPanel.add(new JLabel("SongBook3Data.plugindata (optional):"),BorderLayout.NORTH);
        JTextField libraryPathField=new JTextField(settings.songBookLibraryFile());
        libraryPathField.setMinimumSize(new Dimension(85,24));
        libraryPathField.setToolTipText("If empty, browse ABC folders directly; actual ABC files are still needed.");
        JPanel libraryPathRow=new JPanel(new BorderLayout(4,0));
        libraryPathRow.add(libraryPathField,BorderLayout.CENTER);
        JPanel libraryButtons=new JPanel(new FlowLayout(FlowLayout.RIGHT,3,0));
        JButton libraryBrowse=new JButton("Browse");
        libraryBrowse.addActionListener(e -> {
            JFileChooser picker=new JFileChooser();
            picker.setDialogTitle("Choose SongBook3Data.plugindata");
            if(!libraryPathField.getText().isBlank()) {
                try {picker.setSelectedFile(Path.of(libraryPathField.getText()).toFile());}
                catch(Exception invalid){log("Saved SongBook path could not be opened: "+invalid.getMessage());}
            }
            if(picker.showOpenDialog(this)==JFileChooser.APPROVE_OPTION) {
                libraryPathField.setText(picker.getSelectedFile().getAbsolutePath());
                try {settings.setSongBookLibraryFile(libraryPathField.getText());refreshMusicLibrary();}
                catch(Exception ex){error("Cannot save library path: "+ex.getMessage());}
            }
        });
        JButton libraryApply=new JButton("Apply");
        libraryApply.addActionListener(e -> {
            try {settings.setSongBookLibraryFile(libraryPathField.getText());refreshMusicLibrary();}
            catch(Exception ex){error("Cannot save library path: "+ex.getMessage());}
        });
        libraryButtons.add(libraryBrowse);libraryButtons.add(libraryApply);
        libraryPathRow.add(libraryButtons,BorderLayout.EAST);
        libraryPanel.add(libraryPathRow,BorderLayout.CENTER);
        addSettingLine(controls,libraryPanel);
        controls.add(Box.createVerticalStrut(4));

        JPanel formatPanel=new JPanel(new BorderLayout(5,0));
        formatPanel.add(new JLabel("Song labels:"),BorderLayout.WEST);
        JComboBox<SongDisplayFormat> labelFormats=new JComboBox<>(SongDisplayFormat.values());
        labelFormats.setMinimumSize(new Dimension(90,24));
        labelFormats.setSelectedItem(settings.songDisplayFormat());
        labelFormats.setToolTipText("Part count, title, filename, artist and duration from the SongBook index");
        labelFormats.addActionListener(e -> {
            try {settings.setSongDisplayFormat((SongDisplayFormat)labelFormats.getSelectedItem());musicSongs.repaint();}
            catch(IOException ex){error("Cannot save song display format: "+ex.getMessage());}
        });
        formatPanel.add(labelFormats,BorderLayout.CENTER);
        addSettingLine(controls,formatPanel);
        controls.add(Box.createVerticalStrut(5));

        JPanel commands=new JPanel();
        commands.setLayout(new BoxLayout(commands,BoxLayout.Y_AXIS));
        commands.setBorder(BorderFactory.createTitledBorder("Music-tab commands"));
        for(int i=0;i<SettingsStore.CUSTOM_BUTTONS;i++) {
            final int index=i;
            JPanel row=new JPanel(new GridBagLayout());
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            GridBagConstraints g=new GridBagConstraints();
            g.gridy=0;g.insets=new Insets(0,2,1,2);g.anchor=GridBagConstraints.CENTER;
            g.gridx=0;g.weightx=0;g.fill=GridBagConstraints.NONE;
            row.add(new JLabel("Button "+(i+1)+":"),g);
            JTextField command=new JTextField(settings.customCommand(i));
            command.setToolTipText("Enter a single slash command, for example /bow");
            command.setMinimumSize(new Dimension(60,24));
            g.gridx=1;g.weightx=1;g.fill=GridBagConstraints.HORIZONTAL;
            row.add(command,g);
            g.gridx=2;g.weightx=0;g.fill=GridBagConstraints.NONE;
            row.add(new JLabel("To:"),g);
            JComboBox<String> target=customCommandTargets[i];
            target.setPreferredSize(new Dimension(103,24));
            target.setMinimumSize(new Dimension(72,24));
            g.gridx=3;g.fill=GridBagConstraints.HORIZONTAL;
            row.add(target,g);
            JButton save=new JButton("Save");
            save.setMargin(new Insets(2,5,2,5));
            save.addActionListener(e -> {
                try {
                    settings.setCustomButton(index,command.getText(),(String)target.getSelectedItem());
                    refreshCustomCommandButtons();
                    log("Saved music command "+(index+1)+": "+
                            (settings.customCommand(index).isBlank()?"none":settings.customCommand(index))+
                            " → "+settings.customTarget(index));
                }catch(Exception ex){error("Invalid custom command: "+ex.getMessage());}
            });
            g.gridx=4;g.fill=GridBagConstraints.NONE;
            row.add(save,g);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE,28));
            commands.add(row);
        }
        refreshCustomCommandTargets();
        addSettingLine(controls,commands);

        JScrollPane scroller=new JScrollPane(controls,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(null);
        scroller.getVerticalScrollBar().setUnitIncrement(16);
        // Refresh the HTML wrap widths whenever the viewport changes. No fixed horizontal min width.
        Runnable updateWrap=() -> {
            int width=Math.max(180,scroller.getViewport().getWidth()-42);
            for(var pair:checkLabels.entrySet()) {
                JCheckBox checkbox=pair.getKey();
                checkbox.setText("<html><div style='width:"+width+"px'>"+escapeHtml(pair.getValue())+"</div></html>");
                checkbox.setMaximumSize(new Dimension(Integer.MAX_VALUE,checkbox.getPreferredSize().height));
            }
            for(var pair:noteLabels.entrySet()) {
                JLabel note=pair.getKey();setWrappedText(note,pair.getValue(),width);
                note.setMaximumSize(new Dimension(Integer.MAX_VALUE,note.getPreferredSize().height));
            }
            controls.revalidate();
        };
        scroller.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {updateWrap.run();}
        });
        SwingUtilities.invokeLater(updateWrap);
        root.add(scroller,BorderLayout.CENTER);
        return root;
    }
    /** Keep every custom button visible even at the application's minimum width. */
    static String compactCommandLabel(String command) {
        if (command == null || command.isBlank()) return "none";
        return command.length() <= 12 ? command : command.substring(0,11) + "…";
    }
    private void refreshCustomCommandButtons() {
        for(int i=0;i<customCommandButtons.length;i++) {
            JButton button=customCommandButtons[i];
            if(button==null)continue;
            String command=settings.customCommand(i);
            button.setText(command.isBlank()?"none":compactCommandLabel(command));
            button.setToolTipText(command.isBlank()?"Not configured":command+" → "+settings.customTarget(i));
        }
    }
    private void refreshCustomCommandTargets() {
        if(updatingCommandTargets)return;
        updatingCommandTargets=true;
        try {
            for(int i=0;i<customCommandTargets.length;i++) {
                JComboBox<String> choice=customCommandTargets[i];
                String target=settings.customTarget(i);
                choice.removeAllItems();
                choice.addItem("All");
                for(CharacterInfo member:roster)choice.addItem(member.name());
                boolean found=false;
                for(int j=0;j<choice.getItemCount();j++)
                    if(choice.getItemAt(j).equalsIgnoreCase(target)){found=true;choice.setSelectedIndex(j);break;}
                if(!found) {choice.addItem(target);choice.setSelectedItem(target);}
            }
        } finally {updatingCommandTargets=false;}
    }
    private void executeCustomCommand(int index) {
        String command=settings.customCommand(index);
        if(command.isBlank()){log("Custom button "+(index+1)+" is not configured");return;}
        String target=settings.customTarget(index);
        List<CharacterInfo> recipients="All".equalsIgnoreCase(target)?List.copyOf(roster):
                roster.stream().filter(c->c.name().equalsIgnoreCase(target)).toList();
        if(recipients.isEmpty()){log("No registered recipients for "+command+" → "+target);return;}
        if(!confirmAction("Send "+command+" to "+(target.equalsIgnoreCase("All")?
                "all "+recipients.size()+" characters":target)+"?",
                "Custom command",JOptionPane.QUESTION_MESSAGE))return;
        sendActions(recipients.stream().map(c -> new PendingAction(c,"CUSTOM",command,"","")).toList());
    }

    private void refreshMusicLibrary() {
        workers.submit(() -> {
            try {
                MusicLibraryIndex found;
                String libraryFile = settings.songBookLibraryFile();
                if (libraryFile.isBlank()) found = MusicLibraryIndex.scan(options.musicDir());
                else {
                    try { found = MusicLibraryIndex.fromSongBook(options.musicDir(),Path.of(libraryFile)); }
                    catch(Exception ex) {
                        String detail=ex.getMessage();
                        log("SongBook3 library could not load (using ABC folders): "+detail);
                        found=MusicLibraryIndex.scan(options.musicDir());
                    }
                }
                MusicLibraryIndex loadedIndex = found;
                SwingUtilities.invokeLater(() -> {
                    libraryIndex = loadedIndex;
                    if (currentMusicFolder == null || !loadedIndex.directories().contains(currentMusicFolder))
                        currentMusicFolder = loadedIndex.root();
                    updateMusicLists();
                    log("Library refreshed: " + loadedIndex.songs().size() +
                            (loadedIndex.metadata().isEmpty()?" ABC files":" SongBook3 entries with artist/track metadata"));
                });
            } catch (Exception ex) { log("Cannot browse ABC music library: " + ex.getMessage()); }
        });
    }
    private void updateMusicLists() {
        if (libraryIndex == null || currentMusicFolder == null) return;
        updatingMusicLists = true;
        try {
            Path selectedSong = musicSongs.getSelectedValue();
            musicFoldersModel.clear();
            for (Path folder : libraryIndex.folders(currentMusicFolder,folderSearch.getText()))
                musicFoldersModel.addElement(folder);
            musicSongsModel.clear();
            for (Path file : libraryIndex.songs(currentMusicFolder,songSearch.getText(),artistSearch.getText()))
                musicSongsModel.addElement(file);
            if (selectedSong != null && musicSongsModel.contains(selectedSong))
                musicSongs.setSelectedValue(selectedSong,true);
            folderLocation.setText("Folders  —  " + libraryIndex.root().relativize(currentMusicFolder));
            songLocation.setText("Songs (" + musicSongsModel.size() + ") — click to load");
        } finally { updatingMusicLists = false; }
        refreshSetlistRows();
    }
    private void switchSetlistView(boolean showSetlist) {
        if(showSetlist && importedSetlist==null) { log("Import a Planner setlist first"); return; }
        setlistView=showSetlist;
        songsCardLayout.show(songsCards,showSetlist?"setlist":"library");
        folderLocation.setText(showSetlist?"Folders — library (switch back to browse)":"Folders");
        songLocation.setText(showSetlist?"Setlist: "+importedSetlist.name()+" ("+setlistSongsModel.size()+")":"Songs — click to load");
        if(!showSetlist) updateMusicLists();
    }
    private void refreshSetlistRows() {
        if(importedSetlist==null)return;
        int selected=setlistSongs.getSelectedValue()==null?-1:setlistSongs.getSelectedValue().index();
        updatingSetlist=true;
        try {
            setlistSongsModel.clear();
            String filter=songSearch.getText().toLowerCase(Locale.ROOT).strip();
            for(int i=0;i<importedSetlist.songs().size();i++) {
                ConductorSetlist.Entry entry=importedSetlist.songs().get(i);
                if(filter.isEmpty() || entry.gamePath().toLowerCase(Locale.ROOT).contains(filter))
                    setlistSongsModel.addElement(new SetlistRow(i,entry));
            }
            for(int i=0;i<setlistSongsModel.size();i++)
                if(setlistSongsModel.get(i).index()==selected) {setlistSongs.setSelectedIndex(i); break;}
        } finally {updatingSetlist=false;}
        if(setlistView) songLocation.setText("Setlist: "+importedSetlist.name()+" ("+setlistSongsModel.size()+")");
    }

    private void assignSongPart() {
        CharacterInfo c = (CharacterInfo)musicCharacterChoice.getSelectedItem();
        Music.Part part = songPartsList.getSelectedValue();
        if (song == null || c == null || part == null) { log("Select a song, part and character to assign"); return; }
        int currentIndex=musicCharacterChoice.getSelectedIndex();
        assignments.put(c.key(),part.id()); refreshTable();
        int next=nextUnassignedCharacterIndex(currentIndex);
        if(next >= 0)musicCharacterChoice.setSelectedIndex(next);
        log("Assigned " + c.name() + " => X:" + part.id());
    }
    /** Advance to the next musician without an assigned track; wrap once, otherwise retain selection. */
    private int nextUnassignedCharacterIndex(int afterIndex) {
        List<CharacterInfo> displayed = new ArrayList<>();
        for(int i=0;i<musicCharacterChoice.getItemCount();i++)
            displayed.add(musicCharacterChoice.getItemAt(i));
        return nextUnassignedIndex(displayed,assignments,afterIndex);
    }
    /** Pure helper so manual-assignment keyboard workflow can be regression-tested. */
    static int nextUnassignedIndex(List<CharacterInfo> displayed, Map<String,Integer> assigned, int afterIndex) {
        int count=displayed.size();
        if(count==0)return -1;
        for(int offset=1;offset<=count;offset++) {
            int index=(Math.max(0,afterIndex)+offset)%count;
            CharacterInfo item=displayed.get(index);
            if(item!=null && !assigned.containsKey(item.key()))return index;
        }
        return -1;
    }
    private void refreshMusicChoices() {
        CharacterInfo selected=(CharacterInfo)musicCharacterChoice.getSelectedItem();
        CharacterInfo leader=(CharacterInfo)leaderChoice.getSelectedItem();
        musicCharacterChoice.removeAllItems();
        leaderChoice.removeAllItems();
        for(CharacterInfo c:roster) {musicCharacterChoice.addItem(c);leaderChoice.addItem(c);}
        chooseMatching(musicCharacterChoice,selected);
        chooseMatching(leaderChoice,leader);
        refreshCustomCommandTargets();
    }
    private void chooseMatching(JComboBox<CharacterInfo> box, CharacterInfo value) {
        if (value == null) return;
        for (int i=0;i<box.getItemCount();i++) if (box.getItemAt(i).key().equals(value.key())) {
            box.setSelectedIndex(i); break;
        }
    }
    private void updateSongSummary() {
        songPartsList.repaint();
        if (song == null) return;
        int matched = 0;
        for (Music.Part part : song.parts())
            if (roster.stream().anyMatch(c -> Objects.equals(assignments.get(c.key()),part.id()))) matched++;
        songLabel.setText("Song: " + song.gamePath() + "   |   Assigned " + matched + "/" + song.parts().size() + " parts");
        songLabel.setToolTipText(song.file().toString());
    }
    /** Shared optional-action confirmation gate. Read the current preference
     * at click time; mandatory validation remains outside this gate. */
    static boolean confirmIfEnabled(SettingsStore settings,BooleanSupplier prompt,Runnable suppressed) {
        if (!settings.confirmActions()) { suppressed.run(); return true; }
        return prompt.getAsBoolean();
    }
    private boolean confirmAction(String message,String title,int messageType) {
        return confirmIfEnabled(settings,
                ()->JOptionPane.showConfirmDialog(this,message,title,JOptionPane.YES_NO_OPTION,messageType)==JOptionPane.YES_OPTION,
                ()->log("Confirmation suppressed: " + title));
    }
    private void musicAll() {
        if (roster.isEmpty()) { log("No registered characters to toggle"); return; }
        CharacterInfo leader = chosenLeader();
        if (settings.excludeLeaderMusic() && leader == null) {
            log("Choose a leader before toggling /music with leader exclusion enabled"); return;
        }
        List<CharacterInfo> recipients;
        try {
            recipients = settings.excludeLeaderMusic()
                    ? BandTargets.exceptLeader(roster,leader) : List.copyOf(roster);
        } catch (IllegalArgumentException invalidLeader) { log(invalidLeader.getMessage()); return; }
        if (recipients.isEmpty()) { log("No non-leader characters to toggle"); return; }
        if (!confirmAction("Toggle /music for " + recipients.size() + " character(s)?\n"
                + "This toggles each client's CURRENT state."
                + (settings.excludeLeaderMusic()?"\nExcluding leader: " + leader.name():""),
                "Toggle /music on clients",JOptionPane.WARNING_MESSAGE)) return;
        sendActions(recipients.stream().map(c -> new PendingAction(c,"MUSIC","","","")).toList());
    }
    private void followAllExceptLeader() {
        CharacterInfo leader = chosenLeader();
        if (leader == null) { log("Choose a leader before using Follow"); return; }
        String key = settings.fellowTwoShortcut();
        if (key.isBlank()) { error("Assign a Select fellow 2 keybind in Settings first"); return; }
        // Do not query the bridge: it cannot confirm either current target or follow state.
        List<CharacterInfo> followers;
        try { followers = BandTargets.exceptLeader(roster,leader); }
        catch (IllegalArgumentException invalidLeader) { log(invalidLeader.getMessage()); return; }
        if (followers.isEmpty()) { log("No non-leader characters to follow"); return; }
        sendActions(followers.stream().map(c -> new PendingAction(c,"FOLLOW",key,"","")).toList());
        log("Follow sent to " + followers.size() + " musician(s); leader " + leader.name() + " excluded.");
    }
    private CharacterInfo chosenLeader() {
        return (CharacterInfo) leaderChoice.getSelectedItem();
    }
    private void applyAutoSuggestions() {
        if (!settings.autoAcceptMatches() || song == null) return;
        List<CharacterInfo> withLocal = mergeLocalInstruments(roster,Map.of());
        if(withLocal.stream().anyMatch(c->!availableQuickslots().containsKey(c.key())))return;
        Map<String,Integer> proposal = QuickslotAssigner.assignWithCapabilities(song.parts(),withLocal,availableQuickslots(),assignments,lastSyncedInstruments);
        if (!proposal.isEmpty()) {
            assignments.putAll(proposal); refreshTable();
            log("Auto-assigned " + proposal.size() + " mapped parts");
        }
    }
    private List<CharacterInfo> mergeLocalInstruments(List<CharacterInfo> current,Map<String,String> fresh) {
        List<CharacterInfo> result = new ArrayList<>();
        for (CharacterInfo c : current) {
            String instrument = c.instrument();
            if (c.nodeId().equals(client.id())) {
                instrument = fresh.get(c.name().toLowerCase(Locale.ROOT));
                if (instrument == null) instrument = agent.instrumentFor(c.name());
                if (instrument == null) instrument = c.instrument();
            }
            result.add(new CharacterInfo(c.nodeId(),c.computer(),c.name(),instrument,c.window(),c.state()));
        }
        return result;
    }

    private JPanel createLocalPanel() {
        JPanel root = new JPanel(new BorderLayout(6,6));
        root.setBorder(BorderFactory.createEmptyBorder(10,14,10,14));
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4,3,4,3); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0;
        labelAndField(fields, c, "Saved character", savedProfiles);
        JPanel profileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));
        savedProfiles.addActionListener(e -> { if(!updatingProfiles)loadProfile(); });
        JButton forgetProfile = compactButton("Forget saved", "Forget");
        forgetProfile.addActionListener(e -> forgetProfile());
        profileButtons.add(forgetProfile);
        c.gridx=2; c.weightx=0; fields.add(profileButtons,c);
        c.gridy++;
        labelAndField(fields, c, "LOTRO window", windows);
        JButton refresh = compactButton("Refresh windows", "Refresh");
        JButton identify = compactButton("Identify (focus only)", "Focus");
        JPanel windowActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        windowActions.add(refresh);
        windowActions.add(identify);
        c.gridx=2; c.weightx=0; fields.add(windowActions,c);
        refresh.addActionListener(e -> refreshWindows());
        identify.addActionListener(e -> {
            GameWindow chosen = (GameWindow) windows.getSelectedItem();
            if (chosen == null) { error("Select a LOTRO window first"); return; }
            ++latestFocusRequest;
            workers.submit(() -> {
                try { agent.focusCandidateWindow(chosen).get();
                    log("Focused candidate window " + chosen + " (no keys sent)"); }
                catch (Exception ex) { log("Cannot identify window: " + ex.getMessage()); }
            });
        });
        c.gridy++;
        JButton resetKeyboard = new JButton("Reset keyboard state");
        resetKeyboard.setToolTipText("Release Ctrl, Shift and Alt in the selected local LOTRO window; no chat or other key presses.");
        resetKeyboard.setEnabled(options.live() && !options.demo());
        resetKeyboard.addActionListener(e -> {
            GameWindow chosen = (GameWindow) windows.getSelectedItem();
            if (chosen == null) { error("Select a LOTRO window first"); return; }
            if (JOptionPane.showConfirmDialog(this,
                    "Focus the selected LOTRO window and release Ctrl, Shift and Alt?\nKeep your hands off the keyboard during the reset.",
                    "Reset keyboard state", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            ++latestFocusRequest;
            var reset = agent.resetCandidateKeyboard(chosen);
            resetKeyboard.setEnabled(false);
            reset.whenComplete((unused, ex) -> SwingUtilities.invokeLater(() -> {
                resetKeyboard.setEnabled(options.live() && !options.demo());
                if (ex == null) log("KEYBOARD RESET SENT: " + chosen + " (modifier releases only; not game-confirmed)");
                else log("KEYBOARD RESET FAILED: " + ex.getMessage());
            }));
        });
        c.gridx=0; c.gridwidth=3; c.weightx=1; fields.add(resetKeyboard,c); c.gridwidth=1;
        c.gridy++; labelAndField(fields,c,"Character name",characterField);
        c.gridy++; labelAndField(fields,c,"Instrument (optional)",instrumentField);
        c.gridy++; labelAndField(fields,c,"Bridge status .plugindata (optional)",bridgeFileField);
        JButton browse = compactButton("Browse...", "Browse");
        c.gridx=2; c.weightx=0; fields.add(browse,c);
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                bridgeFileField.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        c.gridy++;
        labelAndField(fields,c,"PluginData folder for auto-identify",pluginDataRootField);
        JButton browseRoot = compactButton("Browse folder...", "Browse");
        c.gridx=2; c.weightx=0; fields.add(browseRoot,c);
        browseRoot.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                pluginDataRootField.setText(chooser.getSelectedFile().getAbsolutePath());
                savePluginRoot();
            }
        });
        c.gridy++;
        JPanel actions = new JPanel(new GridLayout(2,1,2,2));
        JPanel rowOne = new JPanel(new GridLayout(1,3,3,0));
        JPanel rowTwo = new JPanel(new GridLayout(1,3,3,0));
        JButton add = compactButton("Add / update local character", "Add/Update");
        add.addActionListener(e -> register()); rowOne.add(add);
        JButton remove = compactButton("Remove local character", "Remove");
        remove.addActionListener(e -> {
            try { agent.remove(characterField.getText().strip()); log("Removed local character (if registered)"); }
            catch (Exception x) { error(x.getMessage()); }
        }); rowOne.add(remove);
        JButton read = compactButton("Read existing bridge status", "Brdg stat");
        read.addActionListener(e -> agent.requestReadBridge(characterField.getText().strip()));
        rowOne.add(read);
        identifyButton = compactButton("Identify unregistered windows", "Identify");
        identifyButton.addActionListener(e -> autoIdentifyWindows());
        identifyButton.setEnabled(options.live() && !options.demo());
        rowTwo.add(identifyButton);
        cancelIdentifyButton = compactButton("Cancel identification", "Cancel");
        cancelIdentifyButton.setEnabled(false);
        cancelIdentifyButton.addActionListener(e -> { cancelIdentification.set(true); log("Cancelling identification; no new windows will be registered"); });
        rowTwo.add(cancelIdentifyButton);
        identifyWaitSpinner.setModel(new SpinnerNumberModel(settings.identifyWaitSeconds(),15,600,15));
        identifyWaitSpinner.addChangeListener(e -> {
            try { settings.setIdentifyWaitSeconds((Integer) identifyWaitSpinner.getValue()); }
            catch (IOException ex) { log("Cannot save identity wait: " + ex.getMessage()); }
        });
        JPanel waitArea = new JPanel(new FlowLayout(FlowLayout.CENTER,3,0));
        waitArea.add(new JLabel("Wait (s):"));
        waitArea.add(identifyWaitSpinner);
        rowTwo.add(waitArea);
        actions.add(rowOne); actions.add(rowTwo);
        c.gridx=0; c.gridwidth=3; c.weightx=0;
        fields.add(actions,c);
        root.add(fields,BorderLayout.NORTH);
        JPanel lower = new JPanel(new BorderLayout(3,3));
        lower.setBorder(BorderFactory.createTitledBorder("Instrument quickslots — click Assign (Esc cancels, Space removes)"));
        JPanel columns = new JPanel(new GridLayout(1,2,8,0));
        List<String> instruments=InstrumentMatcher.knownInstruments();
        int middle=(instruments.size()+1)/2;
        for(int column=0;column<2;column++) {
            JPanel list = new JPanel(new GridLayout(middle,1,0,3));
            for(int row=0;row<middle;row++) {
                int index=column*middle+row;
                if(index>=instruments.size()) {list.add(new JLabel(""));continue;}
                String instrument=instruments.get(index);
                JPanel item=new JPanel(new BorderLayout(2,0));
                JLabel label=new JLabel(instrument);
                label.setFont(label.getFont().deriveFont(11f));
                label.setToolTipText(instrument);
                item.add(label,BorderLayout.CENTER);
                JButton assign=new JButton("Assign");
                assign.setMargin(new Insets(2,3,2,3));
                assign.setPreferredSize(new Dimension(72,24));
                assign.addActionListener(e -> editInstrumentShortcut(instrument));
                slotAssignmentButtons.put(instrument,assign);
                JButton test=new JButton("▶");
                test.setMargin(new Insets(1,1,1,1));
                test.setPreferredSize(new Dimension(27,24));
                test.setToolTipText("Send this instrument's saved shortcut to the current character");
                test.addActionListener(e -> testInstrumentShortcut(instrument));
                JPanel right=new JPanel(new FlowLayout(FlowLayout.RIGHT,2,0));
                right.add(assign);right.add(test);
                item.add(right,BorderLayout.EAST);
                list.add(item);
            }
            columns.add(list);
        }
        JScrollPane scroll=new JScrollPane(columns);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(15);
        lower.add(scroll,BorderLayout.CENTER);
        root.add(lower,BorderLayout.CENTER);
        return root;
    }
    private void savePluginRoot() {
        try { settings.setPluginDataRoot(pluginDataRootField.getText().strip()); }
        catch (IOException e) { error("Cannot save PluginData folder: " + e.getMessage()); }
    }
    private void autoIdentifyWindows() {
        String raw = pluginDataRootField.getText().strip();
        Path directory = null;
        boolean savedPaths = profiles.list().stream().anyMatch(p -> p.bridgeStatus() != null);
        if (!raw.isBlank()) {
            try { directory = Path.of(raw).toAbsolutePath().normalize(); }
            catch (Exception ex) { error("Invalid folder: " + ex.getMessage()); return; }
            if (!Files.isDirectory(directory)) {
                if (savedPaths) {
                    log("PluginData folder unavailable; using saved character bridge file paths only");
                    directory = null;
                } else { error("Folder does not exist: " + directory); return; }
            }
        }
        if (directory == null && !savedPaths) {
            error("Add a saved character with a bridge status file, or choose your PluginData directory"); return;
        }
        if (!confirmAction("Conductor will focus each unregistered LOTRO window and send an identity command.\n" +
                "Existing bridge files are used first; responses may take many seconds.\n" +
                "Close any open game chat/editor first. Continue?", "Identify game windows", JOptionPane.WARNING_MESSAGE)) return;
        int waitSeconds = (Integer) identifyWaitSpinner.getValue();
        savePluginRoot();
        ++latestFocusRequest;
        cancelIdentification.set(false);
        identifyButton.setEnabled(false);
        cancelIdentifyButton.setEnabled(true);
        Path root = directory;
        workers.submit(() -> {
            try {
                for (String outcome : agent.identifyWindows(root, Duration.ofSeconds(waitSeconds),
                        message -> log("Auto identify: " + message), cancelIdentification::get)) {
                    log("Auto identify: " + outcome);
                }
                SwingUtilities.invokeLater(this::refreshProfiles);
                refreshWindows();
            } catch (Exception ex) { error("Auto identification failed: " + ex.getMessage()); }
            finally { SwingUtilities.invokeLater(() -> {
                identifyButton.setEnabled(options.live() && !options.demo());
                cancelIdentifyButton.setEnabled(false);
            }); }
        });
    }
    private void labelAndField(JPanel panel, GridBagConstraints c, String label, Component input) {
        c.gridx=0; c.weightx=0; c.gridwidth=1; panel.add(new JLabel(label + ":"),c);
        c.gridx=1; c.weightx=1; panel.add(input,c);
    }
    /** Login designation is local-only and updates the Computer dropdown at once.
     * The backend also guards stale selections and already queued commands. */
    private void launcherSelected(LauncherWindow launcher) {
        agent.setLauncherWindow(launcher);
        for(int i=windows.getItemCount()-1;i>=0;i--)
            if(backend.isSelectedLauncher(windows.getItemAt(i)))windows.removeItemAt(i);
        log(launcher==null?"Launcher selection cleared":"Launcher excluded from game windows: "+launcher.id());
        refreshWindows();
    }
    private void refreshWindows() {
        GameWindow selected = (GameWindow)windows.getSelectedItem();
        workers.submit(() -> {
            try {
                List<GameWindow> found = backend.listWindows();
                SwingUtilities.invokeLater(() -> {
                    windows.removeAllItems();
                    for (GameWindow w : found) if(!backend.isSelectedLauncher(w))windows.addItem(w);
                    if (selected != null) for (int i=0; i<windows.getItemCount(); i++)
                        if (windows.getItemAt(i).id().equals(selected.id())) { windows.setSelectedIndex(i); break; }
                    log("Discovered " + windows.getItemCount() + " candidate LOTRO window(s)");
                });
            } catch (Exception e) { log("Window discovery failed: " + e.getMessage()); }
        });
    }
    private void register() {
        GameWindow window = (GameWindow) windows.getSelectedItem();
        if (window == null) { error("Select a LOTRO window first"); return; }
        String name = characterField.getText().strip();
        Path statusPath = bridgeFileField.getText().isBlank() ? null : Path.of(bridgeFileField.getText().strip());
        try {
            agent.register(name, instrumentField.getText().strip(), window, statusPath);
            log("Registered " + name + " on " + window);
            refreshProfiles();
            savedProfiles.setSelectedItem(name);
            refreshQuickslots();
        } catch (Exception e) { error(e.getMessage()); }
    }
    private void refreshQuickslots() {
        ProfileStore.Profile p=profiles.find(characterField.getText().strip());
        for(var e:slotAssignmentButtons.entrySet()) {
            String value=p==null?null:p.shortcutFor(e.getKey());
            e.getValue().setText(value==null?"Assign":value);
            e.getValue().setToolTipText(e.getKey()+": "+(value==null?"unassigned":value));
        }
    }
    private void editInstrumentShortcut(String instrument) {
        String character=characterField.getText().strip();
        ProfileStore.Profile profile=profiles.find(character);
        if(profile==null) {error("Select a saved character first");return;}
        ShortcutEditor.open(this,instrument,profile.shortcutFor(instrument),chosen -> {
            try {
                ProfileStore.Profile current=profiles.find(character);
                if(current==null)throw new IOException("Character profile no longer exists");
                Map<String,String> mappings=new LinkedHashMap<>(current.quickslots());
                mappings.keySet().removeIf(k->k.equalsIgnoreCase(instrument));
                if(chosen!=null)mappings.put(instrument,chosen.toString());
                profiles.upsert(new ProfileStore.Profile(current.name(),current.instrument(),current.bridgeStatus(),mappings));
                if(agent.bindings().stream().anyMatch(b -> b.character().equalsIgnoreCase(character)))
                    agent.publishCapabilities(character);
                refreshQuickslots();
                log(character+" / "+instrument+": "+(chosen==null?"unassigned":chosen));
            }catch(Exception x){error("Cannot save quickslot: "+x.getMessage());}
        });
    }
    private void testInstrumentShortcut(String instrument) {
        String name=characterField.getText().strip();
        ProfileStore.Profile profile=profiles.find(name);
        String key=profile==null?null:profile.shortcutFor(instrument);
        if(key==null) {log("No shortcut assigned for "+instrument+" / "+name);return;}
        if(!confirmAction("Send "+key+" to "+name+" for "+instrument+"?",
                "Test instrument shortcut",JOptionPane.QUESTION_MESSAGE))return;
        ++latestFocusRequest;
        agent.testQuickslot(name,instrument,Shortcut.parse(key));
    }

    private void importPlannerSetlist() {
        JFileChooser chooser=new JFileChooser();
        chooser.setDialogTitle("Import exported Conductor setlist JSON from SongBook Planner");
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        Path file=chooser.getSelectedFile().toPath();
        workers.submit(() -> {
            try {
                ConductorSetlist loaded=ConductorSetlist.read(file);
                SwingUtilities.invokeLater(() -> {
                    importedSetlist=loaded;
                    refreshSetlistRows();
                    switchSetlistView(true);
                    log("Imported "+loaded.name()+": "+loaded.songs().size()+" songs (navigation only)");
                    if(!loaded.songs().isEmpty())setlistSongs.setSelectedIndex(0);
                });
            }catch(Exception e){error("Setlist import: "+e.getMessage());}
        });
    }
    private void openSetlistSong(int index) {
        ConductorSetlist setlist=importedSetlist;
        if(setlist==null || index<0 || index>=setlist.songs().size())return;
        long navigation=setlistNavigationRevision.incrementAndGet();
        ConductorSetlist.Entry entry=setlist.songs().get(index);
        workers.submit(() -> {
            try {
                Path abc=setlist.fileFor(options.musicDir(),entry);
                Music.Song selected=Music.read(options.musicDir(),abc);
                Map<String,Integer> plan=setlist.assignmentsFor(entry,roster,selected);
                List<String> instrumentWarnings=new ArrayList<>();
                for(ConductorSetlist.Assignment part:entry.parts()) {
                    CharacterInfo member=roster.stream().filter(c->c.name().equalsIgnoreCase(part.character())).findFirst().orElse(null);
                    if(member!=null && !part.instrument().isBlank() &&
                            !member.instrument().equalsIgnoreCase(part.instrument()))
                        instrumentWarnings.add(member.name()+" needs "+part.instrument()+" (reports "+member.instrument()+")");
                }
                SwingUtilities.invokeLater(() -> {
                    if(navigation!=setlistNavigationRevision.get())return;
                    Map<Integer,String> explicitInstruments=new HashMap<>();
                    for(ConductorSetlist.Assignment part:entry.parts())
                        if(!part.instrument().isBlank())explicitInstruments.put(part.trackId(),part.instrument());
                    // Auto-equip must occur AFTER the asynchronous ABC load applied
                    // this song's assignments; otherwise it could equip the prior song.
                    loadSong(abc,plan,settings.autoEquipSetlist()
                            ? () -> sendEquipFor(song,explicitInstruments,true) : null);
                    log("Setlist song "+(index+1)+"/"+setlist.songs().size()+": "+selected.gamePath());
                    if(!selected.gamePath().equals(entry.gamePath()))
                        log("Legacy Planner path corrected: "+entry.gamePath()+" → "+selected.gamePath());
                    for(String warning:instrumentWarnings)log("INSTRUMENT CHANGE: "+warning);

                });
            }catch(Exception e){error("Setlist song not loaded: "+e.getMessage());}
        });
    }
    /** Unified equip for an ordinary ABC or Planner song, regardless of computer.
     * Each target resolves the instrument on its own node and validates its window.
     * The command is fire-and-forget; RESULT indicates input sent, not equipment state. */
    private void equipCurrentSong() {
        Music.Song current=song;
        if(current==null){log("Choose a song before equipping instruments");return;}
        Map<Integer,String> plannedInstruments=new HashMap<>();
        SetlistRow row=setlistSongs.getSelectedValue();
        if(importedSetlist!=null && row!=null) {
            try {
                Path planned=importedSetlist.fileFor(options.musicDir(),row.entry());
                if(planned.equals(current.file()))
                    for(ConductorSetlist.Assignment part:row.entry().parts())
                        if(!part.instrument().isBlank())plannedInstruments.put(part.trackId(),part.instrument());
            }catch(IOException ignored) { /* ordinary ABC browsing must still work */ }
        }
        sendEquipFor(current,plannedInstruments,false);
    }
    private void sendEquipFor(Music.Song current, Map<Integer,String> plannedInstruments, boolean automatic) {
        SongEquipPlanner.NetworkPlan plan=SongEquipPlanner.planNetwork(current,
                Map.copyOf(assignments),List.copyOf(roster),plannedInstruments);
        for(String warning:plan.warnings())log("Equip: "+warning);
        if(plan.targets().isEmpty()){log("No assigned parts have recognizable instruments");return;}
        if(!automatic && !confirmAction("Send saved instrument quickslots to "+plan.targets().size()+
                " assigned musician(s), across all connected computers?\n"
                +"No /music toggle or equipment verification will occur.",
                "Equip current song",JOptionPane.QUESTION_MESSAGE))return;
        String equipAction = settings.skipUnchangedEquip() ? "EQUIP_CHANGED" : "EQUIP";
        sendActions(plan.targets().stream()
                .map(target -> new PendingAction(target.musician(),equipAction,target.instrument(),"", ""))
                .toList());
        log("Equip requested for "+plan.targets().size()+" musician(s); check per-window results");
    }
    private void equipSelectedSetlist(boolean automatic) {
        // Selected setlist song already populates the Music tab assignments and song.
        // Do not rely on any local-only code path or stale status-file snapshot.
        if(song==null)return;
        Map<Integer,String> explicit=new HashMap<>();
        SetlistRow row=setlistSongs.getSelectedValue();
        if(row!=null)for(ConductorSetlist.Assignment part:row.entry().parts())
            if(!part.instrument().isBlank())explicit.put(part.trackId(),part.instrument());
        sendEquipFor(song,explicit,automatic);
    }
    private void refreshProfiles() {
        Object selected=savedProfiles.getSelectedItem();
        updatingProfiles=true;
        try {
            savedProfiles.removeAllItems();
            for(ProfileStore.Profile p:profiles.list())savedProfiles.addItem(p.name());
            if(selected!=null)savedProfiles.setSelectedItem(selected);
        } finally {updatingProfiles=false;}
        if(savedProfiles.getSelectedItem()!=null)loadProfile();
    }
    private void loadProfile() {
        String name = (String)savedProfiles.getSelectedItem();
        if (name == null) { error("No saved character selected"); return; }
        ProfileStore.Profile p = profiles.find(name);
        if (p == null) return;
        characterField.setText(p.name());
        instrumentField.setText(p.instrument());
        bridgeFileField.setText(p.bridgeStatus() == null ? "" : p.bridgeStatus().toString());
        refreshQuickslots();
        log("Loaded saved details for " + name + "; select the current game window before registering");
    }
    private void forgetProfile() {
        String name = (String)savedProfiles.getSelectedItem();
        if (name == null) return;
        if (!confirmAction("Forget saved details for " + name + "? This does not remove an active character.",
                "Forget saved character", JOptionPane.QUESTION_MESSAGE)) return;
        try { profiles.forget(name); refreshProfiles(); log("Forgot saved details for " + name); }
        catch (IOException e) { error("Cannot forget profile: " + e.getMessage()); }
    }
    private void loadSong(Path path) { loadSong(path, null); }
    private void loadSong(Path path, Map<String,Integer> restoredAssignments) {
        loadSong(path,restoredAssignments,null);
    }
    private void loadSong(Path path, Map<String,Integer> restoredAssignments, Runnable afterLoaded) {
        long revision = songSelectionRevision.incrementAndGet();
        workers.submit(() -> {
            try {
                Music.Song loaded = Music.read(options.musicDir(), path);
                Music.Song selected = libraryIndex == null ? loaded : libraryIndex.addTrackInstruments(loaded);
                // Read bridge files before auto-matching, even if a roster update is still in flight.
                Map<String,String> fresh = agent.refreshBridgeSnapshots();
                SwingUtilities.invokeLater(() -> {
                    if (revision != songSelectionRevision.get()) return;
                    song = selected; assignments.clear();
                    if (restoredAssignments != null) assignments.putAll(restoredAssignments);
                    songPartsModel.clear();
                    for (Music.Part part : selected.parts()) songPartsModel.addElement(part);
                    songLabel.setText("Song: " + song.gamePath() + " (" + song.parts().size() + " parts)");
                    songLabel.setToolTipText(song.file().toString());
                    if (!songPartsModel.isEmpty()) songPartsList.setSelectedIndex(0);
                    if (settings.autoAcceptMatches() && restoredAssignments == null
                            && roster.stream().allMatch(c->availableQuickslots().containsKey(c.key()))) {
                        Map<String,Integer> proposal = QuickslotAssigner.assignWithCapabilities(song.parts(),mergeLocalInstruments(roster,fresh),availableQuickslots(),assignments,lastSyncedInstruments);
                        assignments.putAll(proposal);
                        log("Auto-assigned " + proposal.size() + " parts by quickslot availability and continuity");
                    }
                    refreshTable();
                    // Highlight the chosen file without causing another load event.
                    updatingMusicLists = true;
                    try { if (musicSongsModel.contains(path)) musicSongs.setSelectedValue(path,true); }
                    finally { updatingMusicLists = false; }
                    log("Loaded ABC and SHA-256 " + song.sha256());
                    if(afterLoaded!=null)afterLoaded.run();
                });
            } catch (Exception ex) { error(ex.getMessage()); }
        });
    }
    private CharacterInfo selectedMember() {
        int row = rosterTable.getSelectedRow();
        if (row < 0) { error("Select a character in the band roster first"); return null; }
        int index = rosterTable.convertRowIndexToModel(row);
        if (index >= roster.size()) return null;
        return roster.get(index);
    }
    private record PendingAction(CharacterInfo member, String kind, String path, String track, String hash) { }

    /** Return focus to the CONTROLLING computer only, after all action results arrive.
     * Unlike the existing FOCUS game action, this never targets a LOTRO window.
     * X11 also needs an explicit stack raise: Swing toFront() can focus without raising. */
    private void focusThisConductor(long request) {
        if (!connected || request != latestFocusRequest || !isDisplayable()) return;
        try {
            agent.afterLocalInput(() -> {
                if (!connected || request != latestFocusRequest) return;
                try {
                    // Even the Swing activation must wait for all earlier local
                    // input; a stale deferred timer must not interrupt chat.
                    SwingUtilities.invokeAndWait(() -> {
                        if (!connected || request != latestFocusRequest || !isDisplayable()) return;
                        if ((getExtendedState() & Frame.ICONIFIED) != 0)
                            setExtendedState(getExtendedState() & ~Frame.ICONIFIED);
                        toFront();
                        requestFocus();
                    });
                    if (!connected || request != latestFocusRequest) return;
                    if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")
                            || System.getenv("DISPLAY") == null) {
                        log("Requested Conductor window activation after command group");
                    } else {
                        ConductorWindowRaiser.Result result =
                                ConductorWindowRaiser.raiseX11(getTitle(), ProcessHandle.current().pid());
                        if (connected && request == latestFocusRequest) log(result.description());
                    }
                    // Allow the activation to settle before the next input task.
                    Thread.sleep(120);
                } catch (Exception ex) { log("Conductor refocus failed: " + ex.getMessage()); }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Application is shutting down.
        }
    }
    static boolean refocusCategory(String kind, SettingsStore preferences) {
        return switch (kind) {
            case "MUSIC", "EQUIP", "EQUIP_CHANGED", "SPACE" -> preferences.refocusConductorAfterMusicEquipSpace();
            case "PREPARE", "FOCUS", "EQUIP_RESET" -> false;
            default -> preferences.refocusConductorAfterOtherCommands();
        };
    }
    private void sendActions(List<PendingAction> actions) {
        if(actions.isEmpty()) return;
        if(!connected) { error("Connection is closed"); return; }
        long request = ++latestFocusRequest;
        boolean focusAfter = options.host() && actions.stream()
                .allMatch(action -> refocusCategory(action.kind(),settings));
        if(!focusAfter) {
            for(PendingAction action:actions) sendActionRaw(action, "");
            return;
        }
        // Tokens are attached to actions, and echoed by their actual owning agents.
        // A fixed delay after queuing would often race remote or multi-client input.
        final javax.swing.Timer[] timer = new javax.swing.Timer[1];
        List<String> tokens = focusTracker.begin(actions.size(), () -> {
            if(timer[0] != null) timer[0].stop();
            // An older group finishing must not steal focus from a newer command.
            if(request == latestFocusRequest) {
                javax.swing.Timer defer = new javax.swing.Timer(120,e -> focusThisConductor(request));
                defer.setRepeats(false); defer.start();
            }
        });
        timer[0] = new javax.swing.Timer(120_000,e -> {
            int pending = 0;
            // Earlier tokens may already have acknowledged; expire the still-pending group.
            for(String token:tokens) if(focusTracker.pending(token) > 0) {
                // Missing completions do not establish that local input is idle.
                // Invalidate the refocus callback before expiring the group.
                if (request == latestFocusRequest) ++latestFocusRequest;
                pending = focusTracker.expire(token);
                break;
            }
            if(pending > 0) log("Refocus timeout: " + pending +
                    " action(s) did not acknowledge completion; refocus skipped; inspect the development log.");
        });
        timer[0].setRepeats(false);
        timer[0].start();
        for(int i=0;i<actions.size();i++) {
            if(!sendActionRaw(actions.get(i),tokens.get(i)))
                focusTracker.complete(tokens.get(i));
        }
    }
    private boolean sendActionRaw(PendingAction action, String correlation) {
        CharacterInfo c=action.member();
        if(!connected) {log("Connection closed while dispatching " + action.kind());return false;}
        try {
            if(correlation.isEmpty())client.send("ACTION",c.nodeId(),c.name(),action.kind(),action.path(),action.track(),action.hash());
            else client.send("ACTION",c.nodeId(),c.name(),action.kind(),action.path(),action.track(),action.hash(),correlation);
            log("Requested " + action.kind() + " on " + c.name() + " / " + c.computer());
            return true;
        } catch(Exception e) {log("Failed to dispatch " + action.kind() + ": " + e.getMessage());return false;}
    }
    private void sendAction(CharacterInfo c, String kind, String path, String track, String hash) {
        sendActions(List.of(new PendingAction(c,kind,path,track,hash)));
    }
    private void actSelected(String kind) {
        CharacterInfo c = selectedMember(); if (c != null) sendAction(c,kind,"","","");
    }
    /** Only shortcuts on the musician's actual computer qualify it for an instrument. */
    private Map<String,Set<String>> availableQuickslots() {
        Map<String,Set<String>> result=new HashMap<>(bandCapabilities);
        for(CharacterInfo member:roster) if(member.nodeId().equals(client.id())) {
            ProfileStore.Profile profile=profiles.find(member.name());
            if(profile!=null)result.put(member.key(),Set.copyOf(profile.quickslots().keySet()));
        }
        return result;
    }
    private void spaceAll() {
        if(!confirmAction("Send one Space keypress to every registered LOTRO window?\n"
                +"This does not confirm or guarantee any music-mode state.",
                "Space (All)",JOptionPane.QUESTION_MESSAGE))return;
        sendActions(roster.stream().map(c -> new PendingAction(c,"SPACE","","","")).toList());
        log("Space requested for "+roster.size()+" registered window(s)");
    }
    private void suggestParts() {
        if (song == null) { error("Load an ABC song first"); return; }
        Music.Song requestedSong = song;
        workers.submit(() -> {
            // Refresh every local bridge report file before evaluating any candidates.
            Map<String,String> fresh = agent.refreshBridgeSnapshots();
            SwingUtilities.invokeLater(() -> {
                if (song != requestedSong) { log("Song changed while checking instruments; retry suggestions"); return; }
                List<CharacterInfo> snapshot = mergeLocalInstruments(roster,fresh);
                List<String> missing=snapshot.stream()
                        .filter(c->!availableQuickslots().containsKey(c.key()))
                        .map(c->c.name()+" ("+c.computer()+")").toList();
                if(!missing.isEmpty()) {
                    log("Waiting for quickslot capabilities from: "+String.join(", ",missing)
                            +". Update/re-register characters on those machines if needed.");
                    return;
                }
                Map<String,Integer> suggestions = QuickslotAssigner.assignWithCapabilities(song.parts(),snapshot,availableQuickslots(),assignments,lastSyncedInstruments);
                if (suggestions.isEmpty()) {
                    log("No available quickslot mappings for remaining parts; check instrument bindings or assign manually.");
                    updateSongSummary(); return;
                }
                StringBuilder details = new StringBuilder("Apply these suggestions?\n\n");
                for (CharacterInfo c : snapshot) if (suggestions.containsKey(c.key())) {
                    details.append(c.name()).append(" (quickslot: ")
                            .append(InstrumentMatcher.requiredInstrument(song.parts().stream().filter(part -> part.id() == suggestions.get(c.key())).findFirst().orElseThrow().title()))
                            .append(") => X:")
                            .append(suggestions.get(c.key())).append("\n");
                }
                if (!settings.autoAcceptMatches() && !confirmAction(details.toString(),
                        "Quickslot-based assignments",JOptionPane.QUESTION_MESSAGE)) return;
                assignments.putAll(suggestions);
                refreshTable();
                log("Assigned " + suggestions.size() + " parts by saved quickslot, prioritizing previous instruments");
            });
        });
    }
    private void dispatchParts(String kind) {
        if (song == null) { error("Load an ABC song first"); return; }
        List<CharacterInfo> assigned = roster.stream().filter(c -> assignments.containsKey(c.key())).toList();
        if (assigned.isEmpty()) { error("Assign at least one track in the Music tab first"); return; }
        List<SharedTrackApproval.Conflict> overlaps = SharedTrackApproval.conflicts(song,assignments,roster);
        // Deliberate doubled parts are supported, but are NEVER silently accepted.
        // This one-time approval still appears if ordinary confirmations are disabled.
        if (!overlaps.isEmpty() && !sharedTrackApproval.approved(song,assignments,roster)) {
            String[] choices = {"Continue with shared parts", "Cancel"};
            JTextArea details = new JTextArea(SharedTrackApproval.warning(overlaps),
                    Math.min(16,8 + overlaps.size()*3),54);
            details.setEditable(false);
            details.setLineWrap(true);
            details.setWrapStyleWord(true);
            details.setCaretPosition(0);
            int decision = JOptionPane.showOptionDialog(this, new JScrollPane(details),
                    "Shared ABC parts — approval required", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, choices, choices[1]);
            if (decision != 0) { log("Shared-track action cancelled"); return; }
            sharedTrackApproval.approve(song,assignments,roster);
            songPartsList.repaint();
            log("Approved intentional shared ABC parts for this song and current assignments");
        }
        long covered = assigned.stream().map(c -> assignments.get(c.key())).distinct().count();
        if (covered < song.parts().size()) {
            if (!confirmAction("Only " + covered + " of " + song.parts().size() +
                " distinct ABC parts are assigned. Continue?", "Unfilled parts", JOptionPane.WARNING_MESSAGE)) return;
        }
        if (!confirmAction("Send " + kind + " to " + assigned.size() + " registered characters?\n" +
                (options.live() ? "This will focus game windows and type chat commands." : "DRY-RUN: no game input."),
                kind.equals("SELECT") ? "Select SongBook parts" : "Prepare band", JOptionPane.QUESTION_MESSAGE)) return;
        if (kind.equals("PREPARE")) {
            lastSyncedInstruments.clear();
            for (CharacterInfo c : assigned) {
                int id=assignments.get(c.key());
                for (Music.Part part : song.parts()) if(part.id()==id) {
                    String required=InstrumentMatcher.requiredInstrument(part.title());
                    if(required!=null) lastSyncedInstruments.put(c.key(),required);
                    break;
                }
            }
            lastPreparedSong = song;
            lastPreparedAssignments = Map.copyOf(assignments);
            lastPreparedLink.setEnabled(true);
            lastPreparedLink.setToolTipText("Return to last Sync All request: " + song.gamePath());
        }
        sendActions(assigned.stream().map(c -> new PendingAction(c,kind,
                song.gamePath(),Integer.toString(assignments.get(c.key())),song.sha256())).toList());
        if (kind.equals("PREPARE") && settings.refocusLeaderAfterSync()) {
            CharacterInfo leader = chosenLeader();
            if (leader == null || roster.stream().noneMatch(c -> c.key().equals(leader.key()))) {
                log("Cannot refocus leader after Sync All: select a registered leader.");
            } else {
                // Sent AFTER every prepare request. The owning agent's single-threaded
                // input queue runs FOCUS after any earlier PREPAREs on that computer.
                // Other computers cannot steal local focus from the leader's computer.
                sendAction(leader,"FOCUS","","","");
            }
        }
    }
    private void startLeader(CharacterInfo c) {
        if (c == null) { log("Choose a registered leader first"); return; }
        if (!confirmAction("Send /playstart through " + c.name() + "?",
                "Start synchronized playback",JOptionPane.WARNING_MESSAGE)) return;
        log("/playstart sent to " + c.name());
        sendAction(c,"START","","","");
    }
    public void onNetworkMessage(Wire.Message message) {
        SwingUtilities.invokeLater(() -> {
            try {
                switch (message.type()) {
                    case "ROSTER_BEGIN" -> {
                        message.size(2);
                        int expected = Integer.parseInt(message.at(0));
                        long revision = Long.parseLong(message.at(1));
                        if (expected < 0 || revision < 0) { pendingRoster = null; log("Ignored invalid roster start"); return; }
                        expectedRosterSize = expected;
                        pendingRosterRevision = revision;
                        pendingRoster = new ArrayList<>();
                        pendingCapabilities = new HashMap<>();
                    }
                    case "ROSTER_ITEM" -> {
                        message.size(6);
                        if (pendingRoster == null) { log("Ignored roster item outside snapshot"); return; }
                        pendingRoster.add(new CharacterInfo(message.at(0),message.at(1),message.at(2),
                                message.at(3),message.at(4),message.at(5)));
                        if (pendingRoster.size() > expectedRosterSize) {
                            pendingRoster = null; log("Ignored oversized roster snapshot");
                        }
                    }
                    case "ROSTER_CAPREADY" -> {
                        message.size(2);
                        if(pendingRoster == null || pendingCapabilities == null)return;
                        String key=message.at(0)+"::"+message.at(1).toLowerCase(Locale.ROOT);
                        if(pendingRoster.stream().anyMatch(c->c.key().equals(key)))
                            pendingCapabilities.putIfAbsent(key,new HashSet<>());
                    }
                    case "ROSTER_CAP" -> {
                        message.size(3);
                        if(pendingRoster == null || pendingCapabilities == null)return;
                        String key=message.at(0)+"::"+message.at(1).toLowerCase(Locale.ROOT);
                        if(pendingRoster.stream().noneMatch(c->c.key().equals(key)))return;
                        if(message.at(2).isBlank() || message.at(2).length()>150)return;
                        pendingCapabilities.computeIfAbsent(key,k->new HashSet<>()).add(message.at(2));
                    }
                    case "ROSTER_END" -> {
                        message.size(0);
                        if (pendingRoster == null || pendingRoster.size() != expectedRosterSize) {
                            log("Ignored incomplete roster snapshot"); pendingRoster = null; return;
                        }
                        if (pendingRosterRevision >= acceptedRosterRevision) {
                            roster = List.copyOf(pendingRoster);
                            Map<String,Set<String>> finalCaps=new HashMap<>();
                            for(var entry:pendingCapabilities.entrySet())finalCaps.put(entry.getKey(),Set.copyOf(entry.getValue()));
                            bandCapabilities=Map.copyOf(finalCaps);
                            acceptedRosterRevision = pendingRosterRevision;
                            refreshTable();
                            applyAutoSuggestions();
                        }
                        pendingRoster = null;
                    }
                    case "EVENT" -> {
                        if(message.fields().size() != 4 && message.fields().size() != 5)
                            throw new IOException("Invalid completion event");
                        log("RESULT from " + message.at(0) + "/" + message.at(1) + ": " + message.at(2) + " — " + message.at(3));
                        if(message.fields().size() == 5) focusTracker.complete(message.at(4));
                    }
                    case "ERROR" -> error("Band host: " + message.at(0));
                    case "WELCOME" -> log("Authenticated to the band host");
                    default -> log("Host message: " + message.type());
                }
            } catch (Exception e) { log("Invalid host message: " + e.getMessage()); }
        });
    }
    private void refreshTable() {
        sharedTrackApproval.invalidateIfChanged(song,assignments,roster);
        rebuildingTable = true;
        try {
            rosterModel.setRowCount(0);
            for (CharacterInfo c : roster) {
                Integer part = assignments.get(c.key());
                rosterModel.addRow(new Object[]{c.name(),c.instrument(),c.computer(),c.window(),part == null ? "—" : part,c.state()});
            }
            countLabel.setText(roster.size() + " musicians");
            refreshMusicChoices();
            updateSongSummary();
            if (selectedRosterKey != null) {
                for (int i=0; i<roster.size(); i++) if (roster.get(i).key().equals(selectedRosterKey)) {
                    int view = rosterTable.convertRowIndexToView(i);
                    if (view >= 0) rosterTable.setRowSelectionInterval(view,view);
                    return;
                }
                selectedRosterKey = null;
            }
            rosterTable.clearSelection();
        } finally { rebuildingTable = false; }
    }
    public void log(String message) {
        SwingUtilities.invokeLater(() -> {
            String singleLine = message.replace('\n',' ').strip();
            activityLabel.setText(singleLine.length() > 155 ? singleLine.substring(0,152) + "…" : singleLine);
            activityLabel.setToolTipText(singleLine);
            console.append(message + "\n");
            if (console.getDocument().getLength() > 30000) console.replaceRange("",0,12000);
            console.setCaretPosition(console.getDocument().getLength());
        });
    }
    private void error(String message) {
        log("WARNING: " + message);
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,message,"Conductor",JOptionPane.WARNING_MESSAGE));
    }
    private void shutdown() {
        connected = false;
        try { client.close(); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        focusTracker.clear();
        loginPanel.close(); agent.close(); workers.shutdownNow(); dispose();
        System.exit(0);
    }
}
