package org.lotro.conductor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free smoke/integration test suite: java -jar ... --self-test */
public final class DevTests {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static void expectFailure(Throwing task, String what) throws Exception {
        try { task.run(); throw new AssertionError("Expected failure: " + what); }
        catch (AssertionError e) { throw e; } catch (Exception e) { checks++; }
    }
    private interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        wireAndAuth();
        optionsAndMusic();
        musicLibraryBrowser();
        songBookDataAndQuickslotAssignment();
        browsingFormatsAndNormalSongEquip();
        bridge();
        bridgeIdentityAndStates();
        delayedIdentityAndCachedProfiles();
        profilesAndMatching();
        settingsAndWatcher();
        x11WindowRaceAndGuard();
        slowChatTypingBarriers();
        networkAndCommands();
        distributedCapabilitiesAndInput();
        localAgentPipeline();
        quickslotsAndSetlists();
        sharedTrackAssignments();
        sharedTracksAcrossNetwork();
        quickslotAndSwitchRegression();
        customButtonsAndCommandStates();
        leaderRefocusAfterSync();
        conductorRefocusAfterCommands();
        conductorX11RaiseSafety();
        compactUiAndWorkflow();
        System.out.println("PASS — " + checks + " assertions; network/X11/bridge, quickslots, leader/conductor raise, and truthful command states");
    }
    /** Focus requests are allowed only through an authenticated coordinator and
     * execute in the same ordered input queue as the leader's PREPARE commands. */
    private static void leaderRefocusAfterSync() throws Exception {
        Path temp = Files.createTempDirectory("conductor-leader-focus-");
        try {
            Path config = temp.resolve("settings.properties");
            SettingsStore prefs = new SettingsStore(config);
            check(!prefs.refocusLeaderAfterSync(), "leader refocus is disabled by default");
            prefs.setRefocusLeaderAfterSync(true);
            check(new SettingsStore(config).refocusLeaderAfterSync(), "leader refocus persists when enabled");
            prefs.setRefocusLeaderAfterSync(false);
            check(!new SettingsStore(config).refocusLeaderAfterSync(), "leader refocus persists when disabled");
            Path abc = temp.resolve("leader.abc");
            Files.writeString(abc, "X:1\nT:Leader - Basic Lute\nK:C\nC|\n");
            Music.Song song = Music.read(temp,abc);
            try (BandServer server = new BandServer(0,"focus-pass",true)) {
                server.start();
                BlockingQueue<Wire.Message> controllerInbox = new LinkedBlockingQueue<>();
                java.util.concurrent.atomic.AtomicReference<LocalAgent> ownerRef = new java.util.concurrent.atomic.AtomicReference<>();
                BandClient.Listener ownerListener = new BandClient.Listener() {
                    public void received(Wire.Message msg) {
                        if (msg.type().equals("DO")) ownerRef.get().onMessage(msg);
                    }
                    public void disconnected(String reason) { }
                };
                try (BandClient controller = new BandClient("127.0.0.1",server.port(),"controller","focus-pass",true,queueListener(controllerInbox));
                     BandClient owner = new BandClient("127.0.0.1",server.port(),"leader-linux","focus-pass",false,ownerListener)) {
                    List<String> events = new CopyOnWriteArrayList<>();
                    DemoBackend mock = new DemoBackend("leader-linux", events::add);
                    try (LocalAgent agent = new LocalAgent(mock,temp,true, events::add)) {
                        ownerRef.set(agent);
                        agent.setNetwork(owner);
                        GameWindow game = mock.listWindows().get(0);
                        agent.register("Leader","Basic Lute",game,null);
                        untilRosterSize(controllerInbox,1);
                        controller.send("ACTION",owner.id(),"Leader","PREPARE",song.gamePath(),"1",song.sha256());
                        controller.send("ACTION",owner.id(),"Leader","FOCUS","","","");
                        until(controllerInbox,"EVENT", m -> m.at(1).equals("Leader") && m.at(2).equals("INPUT SENT: FOCUS"));
                        List<String> input = events.stream().filter(s -> s.startsWith("DEMO")).toList();
                        check(input.size() == 3, "prepare sends two chat commands then one focus-only operation");
                        check(input.get(0).contains("/conductor select") && input.get(1).contains(" sync") && input.get(2).contains("focus-only"),
                                "leader focus executes after sync within remote owner's input queue");
                        check(input.get(2).contains(game.id()), "focus targets the registered leader window");
                    }
                }
            }
        } finally { deleteTree(temp); }
    }

    /** Raising Conductor must target only its own unique PID+title (never a game client).
     * An activation-only call is not sufficient on X11; verify explicit windowraise. */
    private static void conductorX11RaiseSafety() {
        String title = "LOTRO Conductor 0.12.4 — LinuxA [HOST]";
        String windows = "0x01000001  0  9001  host LOTRO Conductor 0.12.4 — LinuxA [HOST]\n"
                + "0x02000002  0  1555  host The Lord of the Rings Online\n"
                + "0x03000003  0  1234  host LOTRO Conductor 0.12.4 — LinuxA [HOST]\n"
                + "0x04000004  0  9001  host Other Java Dialog\n";
        check("0x01000001".equals(ConductorWindowRaiser.selectUniqueWindow(windows,9001,title)),
                "Conductor frame is located using exact title and owning PID");
        check(ConductorWindowRaiser.selectUniqueWindow(windows,1234,"Other Java Dialog") == null,
                "no fuzzy or cross-PID Conductor target matching");
        check(ConductorWindowRaiser.selectUniqueWindow(windows + "0x05000005  0  9001  host " + title + "\n",9001,title) == null,
                "ambiguous matching frames are rejected");
        List<String> calls = new ArrayList<>();
        ConductorWindowRaiser.Runner success = (seconds, args) -> {
            calls.add(String.join(" ",args));
            return switch (String.join(" ",args)) {
                case "wmctrl -l -p" -> new ProcessTools.Result(0,windows);
                case "xdotool getwindowname 16777217" -> new ProcessTools.Result(0,title + "\n");
                case "xdotool getwindowpid 16777217" -> new ProcessTools.Result(0,"9001\n");
                case "wmctrl -ia 0x01000001", "xdotool windowraise 16777217" -> new ProcessTools.Result(0,"");
                default -> new ProcessTools.Result(1,"Unexpected action");
            };
        };
        var result = ConductorWindowRaiser.raiseX11(title,9001,success);
        check(result.raised(),"the verified Conductor frame can be raised");
        check(calls.size()==5 && calls.get(3).equals("wmctrl -ia 0x01000001")
                && calls.get(4).equals("xdotool windowraise 16777217"),
                "explicit X11 raise follows activation, not just Swing focus");
        calls.clear();
        var wrongOwner = ConductorWindowRaiser.raiseX11(title,9001,(seconds,args)-> {
            calls.add(String.join(" ",args));
            if(args[0].equals("wmctrl")) return new ProcessTools.Result(0,windows);
            if(args[1].equals("getwindowname")) return new ProcessTools.Result(0,title);
            return new ProcessTools.Result(0,"9999");
        });
        check(!wrongOwner.raised() && calls.stream().noneMatch(v->v.contains("windowraise") || v.contains("-ia")),
                "an XID whose owner changed cannot be activated or raised");
        calls.clear();
        var noMatch = ConductorWindowRaiser.raiseX11(title,7777,(seconds,args)-> {
            calls.add(String.join(" ",args));
            return new ProcessTools.Result(0,windows);
        });
        check(!noMatch.raised() && calls.size()==1,"no matching Conductor frame means no X11 action");
        calls.clear();
        var failedListing = ConductorWindowRaiser.raiseX11(title,9001,(seconds,args)-> {
            calls.add(String.join(" ",args));
            return new ProcessTools.Result(1,"BadWindow");
        });
        check(!failedListing.raised() && calls.size()==1,"failed X11 listing never raises a guessed window");
    }

    /** Two settings remain independent. Completion markers must survive remote
     * agent dispatch, and out-of-order/duplicate results must not refocus early. */
    private static void conductorRefocusAfterCommands() throws Exception {
        Path temp = Files.createTempDirectory("conductor-return-focus-");
        try {
            Path config = temp.resolve("settings.properties");
            SettingsStore prefs = new SettingsStore(config);
            check(!prefs.refocusConductorAfterMusicEquipSpace() && !prefs.refocusConductorAfterOtherCommands(),
                    "both new focus preferences default off");
            check(!ConductorFrame.refocusCategory("MUSIC",prefs) &&
                    !ConductorFrame.refocusCategory("START",prefs),"disabled preferences change no action");
            prefs.setRefocusConductorAfterMusicEquipSpace(true);
            SettingsStore restored = new SettingsStore(config);
            check(restored.refocusConductorAfterMusicEquipSpace() && !restored.refocusConductorAfterOtherCommands(),
                    "primary-action focus preference persists separately");
            for(String kind : List.of("MUSIC","EQUIP","EQUIP_CHANGED","SPACE"))
                check(ConductorFrame.refocusCategory(kind,restored),kind+" is in primary-action focus group");
            check(!ConductorFrame.refocusCategory("FOLLOW",restored),"other actions still excluded");
            prefs.setRefocusConductorAfterOtherCommands(true);
            restored = new SettingsStore(config);
            for(String kind : List.of("START","FOLLOW","CUSTOM","SELECT","REPORT"))
                check(ConductorFrame.refocusCategory(kind,restored),kind+" is in other-command focus group");
            for(String kind : List.of("PREPARE","FOCUS","EQUIP_RESET"))
                check(!ConductorFrame.refocusCategory(kind,restored),kind+" never returns focus to Conductor");
            prefs.setRefocusConductorAfterMusicEquipSpace(false);
            restored=new SettingsStore(config);
            check(!ConductorFrame.refocusCategory("SPACE",restored) &&
                    ConductorFrame.refocusCategory("FOLLOW",restored),"focus groups toggle independently");
            prefs.setRefocusConductorAfterOtherCommands(false);
            restored=new SettingsStore(config);
            check(!ConductorFrame.refocusCategory("FOLLOW",restored),"second focus preference turns off independently");
            check(!CommandFocusTracker.validToken("bad") &&
                    CommandFocusTracker.validToken(UUID.randomUUID().toString()),"correlation ID validation");

            CommandFocusTracker tracker = new CommandFocusTracker();
            int[] focusCount={0};
            List<String> tokens=tracker.begin(3,()->focusCount[0]++);
            check(tracker.complete(tokens.get(2)) && focusCount[0]==0,"last response arriving first cannot focus");
            check(!tracker.complete(tokens.get(2)) && focusCount[0]==0,"duplicate response cannot complete batch");
            check(!tracker.complete("unknown-token"),"unknown results cannot affect focus");
            check(tracker.complete(tokens.get(0)) && focusCount[0]==0,"one outstanding action still prevents focus");
            check(tracker.complete(tokens.get(1)) && focusCount[0]==1,"focus runs only after all results");
            check(!tracker.complete(tokens.get(1)) && focusCount[0]==1,"focus callback runs exactly once");
            List<String> timeout=tracker.begin(3,()->focusCount[0]++);
            check(tracker.complete(timeout.get(0)) && tracker.pending(timeout.get(1))==2,
                    "timeout group counts only outstanding actions");
            check(tracker.expire(timeout.get(1))==2 && focusCount[0]==2,
                    "timeout can finish a partially acknowledged group");
            check(!tracker.complete(timeout.get(2)),"late results from expired batch are ignored");
            List<String> cancelled=tracker.begin(1,()->focusCount[0]++);
            tracker.clear();
            check(!tracker.complete(cancelled.get(0)) && focusCount[0]==2,
                    "shutdown cancels pending focus safely");

            // Actual TCP coordinator -> remote Java agent -> RESULT -> EVENT path.
            try (BandServer server=new BandServer(0,"refocus-markers",true)) {
                server.start();
                BlockingQueue<Wire.Message> inbox=new LinkedBlockingQueue<>();
                java.util.concurrent.atomic.AtomicReference<LocalAgent> agentRef=new java.util.concurrent.atomic.AtomicReference<>();
                BandClient.Listener listener=new BandClient.Listener() {
                    public void received(Wire.Message m) {
                        if(m.type().equals("DO")) agentRef.get().onMessage(m);
                    }
                    public void disconnected(String reason) { }
                };
                try (BandClient controller=new BandClient("127.0.0.1",server.port(),"host","refocus-markers",true,queueListener(inbox));
                     BandClient remote=new BandClient("127.0.0.1",server.port(),"linux-b","refocus-markers",false,listener)) {
                    List<String> output=new CopyOnWriteArrayList<>();
                    DemoBackend simulated=new DemoBackend("linux-b",output::add);
                    try(LocalAgent agent=new LocalAgent(simulated,temp,true,output::add)) {
                        agentRef.set(agent);
                        agent.setNetwork(remote);
                        agent.register("R1","Basic Lute",simulated.listWindows().get(0),null);
                        agent.register("R2","Basic Harp",simulated.listWindows().get(1),null);
                        untilRosterSize(inbox,2);
                        String musicToken=UUID.randomUUID().toString();
                        String spaceToken=UUID.randomUUID().toString();
                        controller.send("ACTION",remote.id(),"R1","MUSIC","","","",musicToken);
                        controller.send("ACTION",remote.id(),"R2","SPACE","","","",spaceToken);
                        Wire.Message one=until(inbox,"EVENT",m->m.fields().size()==5 && m.at(4).equals(musicToken));
                        Wire.Message two=until(inbox,"EVENT",m->m.fields().size()==5 && m.at(4).equals(spaceToken));
                        check(one.at(1).equals("R1") && one.at(2).equals("INPUT SENT: MUSIC"),
                                "coordinator forwards Music completion with unique token");
                        check(two.at(1).equals("R2") && two.at(2).equals("INPUT SENT: SPACE"),
                                "coordinator forwards Space completion with unique token");
                        check(output.stream().filter(v->v.startsWith("DEMO")).toList().size()==2,
                                "remote agent sends only the two requested game inputs");
                        String failedToken=UUID.randomUUID().toString();
                        controller.send("ACTION",remote.id(),"R1","EQUIP","Not Mapped","","",failedToken);
                        Wire.Message bad=until(inbox,"EVENT",m->m.fields().size()==5 && m.at(4).equals(failedToken));
                        check(bad.at(2).startsWith("FAILED:"),"failed remote Equip still acknowledges batch completion");
                        String missingToken=UUID.randomUUID().toString();
                        controller.send("ACTION",remote.id(),"missing","MUSIC","","","",missingToken);
                        Wire.Message missing=until(inbox,"EVENT",m->m.fields().size()==5 && m.at(4).equals(missingToken));
                        check(missing.at(2).contains("not registered"),
                                "coordinator reports rejected tracked action so focus cannot hang");
                    }
                }
            }
        } finally { deleteTree(temp); }
    }

    /** UI-focused logic tests without requiring a graphical environment or LOTRO. */
    private static void compactUiAndWorkflow() throws Exception {
        Path dir=Files.createTempDirectory("conductor-compact-ui-");
        try {
            Path file=dir.resolve("settings.properties");
            SettingsStore old=new SettingsStore(file);
            check(SettingsStore.CUSTOM_BUTTONS==5,"five configurable music buttons");
            old.setCustomButton(0,"/bow","All");
            old.setCustomButton(1,"/wave","Sinduriel");
            old.setCustomButton(2,"/dance","Arvael");
            SettingsStore upgraded=new SettingsStore(file);
            check(upgraded.customCommand(0).equals("/bow") && upgraded.customCommand(1).equals("/wave")
                    && upgraded.customCommand(2).equals("/dance"),"existing three commands survive upgrade");
            check(upgraded.customCommand(3).isEmpty() && upgraded.customCommand(4).isEmpty()
                    && upgraded.customTarget(3).equals("All"),"new command slots default empty / All");
            upgraded.setCustomButton(3,"/cheer","All");
            upgraded.setCustomButton(4,"/bow","Sinduriel");
            SettingsStore restored=new SettingsStore(file);
            check(restored.customCommand(3).equals("/cheer") && restored.customCommand(4).equals("/bow")
                    && restored.customTarget(4).equals("Sinduriel"),"new command slots persist");
            check(ConductorFrame.compactCommandLabel("/bow").equals("/bow")
                    && ConductorFrame.compactCommandLabel("/longdancecommand").length()<=12,
                    "button labels fit narrow music tab");
            CharacterInfo a=new CharacterInfo("node","PC","A","","win1","registered");
            CharacterInfo b=new CharacterInfo("node","PC","B","","win2","registered");
            CharacterInfo c=new CharacterInfo("node","PC","C","","win3","registered");
            List<CharacterInfo> roster=List.of(a,b,c);
            check(ConductorFrame.nextUnassignedIndex(roster,Map.of(a.key(),1),0)==1,
                    "manual assignment advances to next unassigned musician");
            check(ConductorFrame.nextUnassignedIndex(roster,Map.of(a.key(),1,b.key(),2),1)==2,
                    "manual assignment skips already-assigned musician");
            check(ConductorFrame.nextUnassignedIndex(roster,Map.of(a.key(),1,c.key(),2),2)==1,
                    "manual assignment wraps around the roster");
            check(ConductorFrame.nextUnassignedIndex(roster,Map.of(a.key(),1,b.key(),2,c.key(),3),2)==-1,
                    "manual assignment retains choice once all are assigned");
            check(ConductorFrame.nextUnassignedIndex(List.of(),Map.of(),0)==-1,
                    "empty roster never produces invalid index");
        } finally {
            Files.deleteIfExists(dir.resolve("settings.properties"));Files.deleteIfExists(dir);
        }
    }

    /** No setlist required for Equip; optional library fields must never fabricate metadata. */
    private static void browsingFormatsAndNormalSongEquip() throws Exception {
        Path root=Files.createTempDirectory("conductor-display-equip-");
        try {
            Path abc=root.resolve("songs/duet.abc");
            Files.createDirectories(abc.getParent());
            Files.writeString(abc,"X:9\nT:Duet - Basic Lute\nK:C\nC|\nX:10\nT:Duet - Basic Harp\nK:C\nC|\n");
            Music.Song song=Music.read(root,abc);
            CharacterInfo alice=new CharacterInfo("node","PC","Alice","","win1","registered");
            CharacterInfo bob=new CharacterInfo("node","PC","Bob","","win2","registered");
            CharacterInfo remote=new CharacterInfo("other","Windows","Charlie","","win3","registered");
            SongEquipPlanner.Result plain=SongEquipPlanner.plan(song,Map.of(alice.key(),9,bob.key(),10),
                    List.of(alice,bob),"node",Map.of());
            check(plain.local().size()==2 && plain.local().get(0).instrument().equals("Basic Lute")
                    && plain.local().get(1).instrument().equals("Basic Harp"),
                    "Equip of ordinary ABC song resolves visible track assignments");
            check(plain.warnings().isEmpty() && plain.remote()==0,"ordinary equip needs no setlist/bridge");
            SongEquipPlanner.Result withRemote=SongEquipPlanner.plan(song,Map.of(alice.key(),9,remote.key(),10),
                    List.of(alice,remote),"node",Map.of());
            check(withRemote.local().size()==1 && withRemote.remote()==1,"remote quickslots are not sent locally");
            Files.writeString(abc,"X:9\nT:Unlabeled piece\nK:C\nC|\n");
            Music.Song unlabeled=Music.read(root,abc);
            SongEquipPlanner.Result missing=SongEquipPlanner.plan(unlabeled,Map.of(alice.key(),9),
                    List.of(alice),"node",Map.of());
            check(missing.local().isEmpty() && missing.warnings().size()==1,
                    "unknown instrument is reported, not guessed");
            SongEquipPlanner.Result planner=SongEquipPlanner.plan(unlabeled,Map.of(alice.key(),9),
                    List.of(alice),"node",Map.of(9,"Basic Harp"));
            check(planner.local().size()==1 && planner.local().get(0).instrument().equals("Basic Harp"),
                    "explicit Planner instrument remains supported");
            Path catalog=root.resolve("SongBook3Data.plugindata");
            Files.writeString(catalog,"return {Songs={{Filepath='/songs/';Filename='duet';Artist='Composer';Tracks={"+
                    "{Id='9';Name='Melody (3:05) - Basic Lute';PartName='Basic Lute';},"+
                    "{Id='10';Name='Harmony (3:05) - Basic Harp';PartName='Basic Harp';}}},"+
                    "{Filepath='/songs/';Filename='untimed';Tracks={{Id='1';Name='Plain name';}}}}}");
            SongBookCatalog.Index index=SongBookCatalog.read(root,catalog);
            SongBookCatalog.Entry details=index.entries().get(abc);
            check(details.partCount()==2 && details.duration().equals("3:05"),
                    "SongBook tracks supply part count and embedded duration");
            check(SongDisplayFormat.PARTS_TITLE_ARTIST_TIME.render(details)
                    .equals("(2) Melody - Basic Lute - Composer (3:05)"),
                    "parts/title/artist/time preset formats metadata without duplicate time");
            check(SongDisplayFormat.ARTIST_TITLE_TIME_PARTS.render(details)
                    .equals("Composer - Melody - Basic Lute (3:05) (2)"),
                    "artist/title/time/parts preset ordering");
            check(SongDisplayFormat.PARTS_FILENAME_ARTIST_TIME.render(details)
                    .equals("(2) duet - Composer (3:05)"),
                    "filename-based preset uses exact filename");
            SongBookCatalog.Entry untimed=index.entries().get(root.resolve("songs/untimed.abc"));
            check(untimed!=null && untimed.duration().isEmpty() && untimed.artist().isEmpty(),
                    "missing time/artist remain missing");
            check(!SongDisplayFormat.PARTS_TITLE_ARTIST_TIME.render(untimed).contains("null")
                    && SongDisplayFormat.PARTS_TITLE_ARTIST_TIME.render(untimed).equals("(1) Plain name"),
                    "missing optional fields omitted cleanly");
            Path pref=root.resolve("settings.properties");
            SettingsStore settings=new SettingsStore(pref);
            settings.setSongDisplayFormat(SongDisplayFormat.ARTIST_TITLE_TIME_PARTS);
            check(new SettingsStore(pref).songDisplayFormat()==SongDisplayFormat.ARTIST_TITLE_TIME_PARTS,
                    "SongBook browse caption format persists");
            check(SongDisplayFormat.parse("INVALID")==SongDisplayFormat.PARTS_TITLE_ARTIST_TIME,
                    "unknown legacy caption format safely falls back");
        } finally {deleteTree(root);}
    }

    private static void customButtonsAndCommandStates() throws Exception {
        CommandStates state=new CommandStates();
        check(state.display().equals("sync=none  music=none  follow=none"),"status starts unknown/unsent");
        state.sent("PREPARE");
        check(state.sync().equals("sent"),"sync command only indicates sent");
        state.bridgeReady(false);
        check(state.sync().equals("sent"),"unconfirmed bridge report does not erase sent");
        state.bridgeReady(true);
        check(state.sync().equals("ready"),"only the bridge ready report confirms sync readiness");
        state.sent("PREPARE");
        check(state.sync().equals("ready"),"duplicate prepare does not lose confirmed readiness");
        state.sent("MUSIC");state.sent("FOLLOW");
        check(state.music().equals("sent") && state.follow().equals("sent"),
                "music and follow remain sent, without invented game state");
        state.sent("START");
        check(state.sync().equals("none"),"playstart resets sync until next prepare");
        check(state.music().equals("sent") && state.follow().equals("sent"),
                "playstart does not claim music or follow state changes");
        check(CommandValidator.requireCommand("/bow").equals("/bow"),"simple slash command accepted");
        check(CommandValidator.requireCommand("/say Hello world").equals("/say Hello world"),
                "single-line command and arguments allowed");
        for(String invalid:List.of("bow","/bow\n/music","/bow\tX","/","/a".repeat(110)))
            expectFailure(()->CommandValidator.requireCommand(invalid),"reject invalid custom slash command");
        check(CommandValidator.validOrEmpty(" ").isEmpty(),"empty command becomes unassigned");
        Path directory=Files.createTempDirectory("conductor-command-settings-");
        try {
            Path file=directory.resolve("settings.properties");
            SettingsStore preferences=new SettingsStore(file);
            for(int i=0;i<SettingsStore.CUSTOM_BUTTONS;i++)
                check(preferences.customCommand(i).isEmpty() && preferences.customTarget(i).equals("All"),
                        "custom command defaults empty and targets all");
            preferences.setCustomButton(0,"/bow","All");
            preferences.setCustomButton(1,"/dance","Sinduriel");
            preferences.setFellowTwoShortcut("Alt+2");
            SettingsStore restored=new SettingsStore(file);
            check(!restored.skipUnchangedEquip(),"skip unchanged Equip starts OFF for backward compatibility");
            restored.setSkipUnchangedEquip(true);
            check(new SettingsStore(file).skipUnchangedEquip(),"skip unchanged Equip option persists enabled");
            restored.setSkipUnchangedEquip(false);
            check(!new SettingsStore(file).skipUnchangedEquip(),"skip unchanged Equip option persists disabled");
            check(restored.customCommand(0).equals("/bow"),"custom button label persists");
            check(restored.customCommand(1).equals("/dance") && restored.customTarget(1).equals("Sinduriel"),
                    "per-button recipient persists");
            check(restored.fellowTwoShortcut().equals("Alt+2"),"Select fellow 2 persists");
            expectFailure(()->restored.setCustomButton(0,"/bow\n/quit","All"),"multiline command blocked");
            check(restored.customCommand(0).equals("/bow"),"invalid save keeps previous command");
        } finally {deleteTree(directory);}
    }

    private static void quickslotAndSwitchRegression() throws Exception {
        LastEquipTracker history=new LastEquipTracker();
        Shortcut lute=Shortcut.parse("Ctrl+1"), different=Shortcut.parse("Ctrl+2");
        check(!history.isUnchanged("Sinduriel","Basic Lute",lute),"no remembered Equip before first command");
        history.recordSuccess("Sinduriel","Basic Lute",lute);
        check(history.isUnchanged("SINDURIEL","basic lute",lute),"same character and instrument compare case-insensitively");
        check(!history.isUnchanged("Sinduriel","Basic Lute",different),"changed slot mapping is never skipped");
        check(!history.isUnchanged("Arvael","Basic Lute",lute),"equip history is per character");
        history.clear("Sinduriel");
        check(!history.isUnchanged("Sinduriel","Basic Lute",lute),"history clears after window remapping");
        for(String key:Shortcut.BAR_KEYS) {
            check(Shortcut.parse(key).toString().equals(key),"plain quickslot position "+key);
            for(String bar:List.of("No modifier","Ctrl","Alt","Shift")) {
                Shortcut shortcut=Shortcut.barSlot(bar,key);
                check(Shortcut.parse(shortcut.toString()).equals(shortcut),"bar/slot round trip "+bar+" "+key);
            }
        }
        check(Shortcut.parse("Ctrl+-").x11().equals("ctrl+minus"),"xdotool minus key name");
        check(Shortcut.parse("Shift+=").x11().equals("shift+equal"),"xdotool equals key name");
        check(Shortcut.parse("=").keyCode()==java.awt.event.KeyEvent.VK_EQUALS,"Windows equals virtual key");
        check(Shortcut.parse("-").keyCode()==java.awt.event.KeyEvent.VK_MINUS,"Windows minus virtual key");
        check(Shortcut.capture(java.awt.event.KeyEvent.VK_EQUALS,
                java.awt.event.InputEvent.ALT_DOWN_MASK).toString().equals("Alt+="),
                "key capture retains pressed equals key");
        check(Shortcut.parse("Ctrl+Tab").x11().equals("ctrl+Tab"),"configurable follow key supports Tab");
        check(Shortcut.parse("Backspace").keyCode()==java.awt.event.KeyEvent.VK_BACK_SPACE,
                "fellow targeting supports Windows Backspace key");
        Path home=Files.createTempDirectory("conductor-immediate-equip-tests-");
        try {
            Path settingsPath=home.resolve("settings.properties");
            SettingsStore preferences=new SettingsStore(settingsPath);
            check(!preferences.autoEquipSetlist(),"auto-equip defaults OFF");
            check(!preferences.excludeLeaderMusic(),"leader is included in /music by default");
            check(preferences.nextFellowShortcut().isEmpty(),"follow keybind requires configuration");
            preferences.setAutoEquipSetlist(true);
            preferences.setExcludeLeaderMusic(true);
            preferences.setNextFellowShortcut("Ctrl+Tab");
            SettingsStore restored=new SettingsStore(settingsPath);
            check(restored.autoEquipSetlist(),"auto-equip setting persists");
            check(restored.excludeLeaderMusic(),"leader /music exclusion setting persists");
            check(restored.nextFellowShortcut().equals("Ctrl+TAB"),"follow keybind roundtrips");
            expectFailure(()->restored.setNextFellowShortcut("Alt+;"),"invalid follow keybind rejected");
            Path missingStatus=home.resolve("never-written.plugindata");
            LocalAgent.Binding binding=new LocalAgent.Binding("Sinduriel","Basic Lute",
                    new GameWindow("0x123","The Lord of the Rings Online™"),missingStatus);
            class Fake implements GameBackend {
                final List<String> sent=new ArrayList<>();
                boolean valid=true;
                @Override public List<GameWindow> listWindows(){return List.of(binding.window());}
                @Override public boolean isValidWindow(GameWindow window){return valid;}
                @Override public void sendChat(GameWindow window,String text) {sent.add(text);}
                @Override public void sendShortcut(GameWindow window,Shortcut shortcut) {sent.add("slot:"+shortcut);}
                @Override public String description(){return "test";}
            }
            Fake fake=new Fake();
            InstrumentSwitcher.equip(binding,"Basic Lute",Shortcut.parse("Ctrl+="),fake,s->{});
            check(fake.sent.equals(List.of("slot:Ctrl+=")),
                    "equip ALWAYS sends one shortcut even when instrument already matches or bridge missing");
            fake.sent.clear();
            InstrumentSwitcher.equip(binding,"Basic Harp",Shortcut.parse("1"),fake,s->{});
            check(fake.sent.equals(List.of("slot:1")),"equip does not send music toggles");
            fake.sent.clear();fake.valid=false;
            expectFailure(()->InstrumentSwitcher.equip(binding,"Basic Harp",Shortcut.parse("2"),fake,s->{}),
                    "invalid LOTRO window cannot receive equip key");
            check(fake.sent.isEmpty(),"invalid window blocks all game input");
            List<CharacterInfo> roster=List.of(
                    new CharacterInfo("host","pc","Leader","Lute","window1","registered"),
                    new CharacterInfo("host","pc","Fellow","Harp","window2","registered"),
                    new CharacterInfo("other","win","Fellow2","Flute","window3","registered"));
            check(BandTargets.exceptLeader(roster,roster.get(0)).equals(roster.subList(1,3)),
                    "music and follow exclude exactly the selected leader across computers");
            expectFailure(()->BandTargets.exceptLeader(roster,null),"cannot guess a leader");
            expectFailure(()->BandTargets.exceptLeader(roster,
                    new CharacterInfo("old","pc","Leader","Lute","other","old")),"stale leader blocked");
        } finally {
            try(var stream=Files.walk(home)) {
                for(Path path:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        }
    }

    private static void quickslotsAndSetlists() throws Exception {
        check(Shortcut.parse("ctrl+shift+2").toString().equals("Ctrl+Shift+2"),"portable shortcut normalization");
        check(Shortcut.parse("Alt+F12").x11().equals("alt+f12"),"X11 quickslot shortcut encoding");
        check(Shortcut.parse("1").keyCode()==java.awt.event.KeyEvent.VK_1,"Windows quickslot key code");
        for(String invalid:List.of("Enter","Ctrl","Ctrl+Ctrl+1","Ctrl+1;rm -rf", "Meta+P", "F13", "Ctrl++1"))
            expectFailure(()->Shortcut.parse(invalid),"block invalid key or shell injection");
        Path root=Files.createTempDirectory("conductor-slots-setlist-");
        try {
            Path profiles=root.resolve("profiles.properties");
            ProfileStore store=new ProfileStore(profiles);
            store.upsert(new ProfileStore.Profile("Sinduriel","Basic Lute",null,
                Map.of("Basic Harp","Alt+F1","Lute of Ages","Ctrl+2")));
            ProfileStore saved=new ProfileStore(profiles);
            check(saved.find("Sinduriel").quickslots().get("Basic Harp").equals("Alt+F1"),
                "quickslot mappings persist with each character profile");
            check(saved.find("Sinduriel").quickslots().size()==2,"multiple instrument shortcuts survive reload");
            check(saved.find("Sinduriel").shortcutFor("basic harp").equals("Alt+F1"),
                    "quickslot lookup tolerates Planner instrument capitalization");
            // The bridge changing current instrument must not erase saved quickslot bindings.
            saved.upsert(new ProfileStore.Profile("Sinduriel","Basic Harp",null,saved.find("Sinduriel").quickslots()));
            check(new ProfileStore(profiles).find("Sinduriel").quickslots().size()==2,
                "instrument status refresh preserves quickslot mappings");
            Path music=root.resolve("songs");Files.createDirectories(music);
            Files.copy(Path.of("examples","demo-band.abc"),music.resolve("demo-band.abc"));
            String json="""
                {"format":"lotro-conductor-setlist","version":1,"name":"Test set",
                 "songs":[{"gamePath":"/demo-band","parts":[
                 {"trackId":1,"character":"Sinduriel","instrument":"Basic Lute"},
                 {"trackId":2,"character":"Arvael","instrument":"Basic Harp"}]}]}
                """;
            Path file=root.resolve("test.json");Files.writeString(file,json);
            // Exact paths win; legacy v0.6.0 paths are only repaired if the
            // single matching song exists immediately below Music/Songs.
            Path legacyFolder=music.resolve("songs/Condensed Music/2 Parts");
            Files.createDirectories(legacyFolder);
            Path nested=legacyFolder.resolve("Amaranth2.abc");
            Files.copy(Path.of("examples","demo-band.abc"),nested);
            Path legacyJson=root.resolve("legacy.json");
            Files.writeString(legacyJson, """
                {"format":"lotro-conductor-setlist","version":1,"name":"Legacy",
                 "songs":[{"gamePath":"/Condensed Music/2 Parts/Amaranth2","parts":[]}]}
                """);
            ConductorSetlist legacy=ConductorSetlist.read(legacyJson);
            check(legacy.fileFor(music,legacy.songs().get(0)).equals(nested),
                    "legacy v0.6.0 exports recover missing songs directory");
            Files.createDirectories(music.resolve("Condensed Music/2 Parts"));
            Path exact=music.resolve("Condensed Music/2 Parts/Amaranth2.abc");
            Files.copy(Path.of("examples","demo-band.abc"),exact);
            check(legacy.fileFor(music,legacy.songs().get(0)).equals(exact),
                    "explicit setlist path takes priority over legacy fallback");
            Files.delete(exact);
            Path capitalFolder=music.resolve("Songs/Condensed Music/2 Parts");
            Files.createDirectories(capitalFolder);
            Path capital=capitalFolder.resolve("Amaranth2.abc");
            Files.copy(Path.of("examples","demo-band.abc"),capital);
            expectFailure(()->legacy.fileFor(music,legacy.songs().get(0)),
                    "two case-distinct songs roots must not be guessed");
            Files.delete(capital);
            ConductorSetlist.Entry correct=new ConductorSetlist.Entry(
                    "/songs/Condensed Music/2 Parts/Amaranth2",List.of());
            check(legacy.fileFor(music,correct).equals(nested),
                    "new Planner paths resolve directly under Music/songs");
            ConductorSetlist imported=ConductorSetlist.read(file);
            check(imported.name().equals("Test set") && imported.songs().size()==1,"Planner setlist JSON import");
            Music.Song abc=Music.read(music,imported.fileFor(music,imported.songs().get(0)));
            List<CharacterInfo> roster=List.of(new CharacterInfo("node","localhost","Sinduriel","Basic Lute","1","ready"),
                new CharacterInfo("node","localhost","Arvael","Basic Harp","2","ready"));
            Map<String,Integer> assigned=imported.assignmentsFor(imported.songs().get(0),roster,abc);
            check(assigned.size()==2 && assigned.get(roster.get(0).key())==1 && assigned.get(roster.get(1).key())==2,
                "Planner's real X: IDs bind to the correct musicians");
            expectFailure(()->imported.assignmentsFor(imported.songs().get(0),List.of(roster.get(0)),abc),
                "missing musician must stop automatic setlist loading");
            Path wrong=root.resolve("wrong.json");
            Files.writeString(wrong,json.replace("\"trackId\":2","\"trackId\":999"));
            ConductorSetlist bad=ConductorSetlist.read(wrong);
            expectFailure(()->bad.assignmentsFor(bad.songs().get(0),roster,abc),"missing ABC part must stop song load");
            Files.writeString(wrong,json.replace("/demo-band","/../../secrets"));
            expectFailure(()->ConductorSetlist.read(wrong),"path traversal disallowed");
            Files.writeString(wrong,json.replace("\"version\":1","\"version\":8"));
            expectFailure(()->ConductorSetlist.read(wrong),"unrecognized setlist version rejected");
            expectFailure(()->SimpleJson.parse("{\"k\":null,\"k\":1}"),"duplicate JSON keys rejected");
        } finally {deleteTree(root);}
    }

    /** Different characters playing the same ABC X: part is legitimate. An explicit
     * approval is required for the precise song + roster + assignment, not for
     * subsequent unrelated songs or changed assignments. */
    private static void sharedTrackAssignments() throws Exception {
        Path folder=Files.createTempDirectory("conductor-shared-part-");
        try {
            Path abc=folder.resolve("duet.abc");
            Files.writeString(abc,"X:1\nT:Shared Lute\nK:C\nC|\nX:2\nT:Drum\nK:C\nG|\n");
            Music.Song song=Music.read(folder,abc);
            CharacterInfo a=new CharacterInfo("node-a","Linux A","Sinduriel","Lute","w1","registered");
            CharacterInfo b=new CharacterInfo("node-b","Linux B","Arvael","Lute","w2","registered");
            List<CharacterInfo> roster=List.of(a,b);
            Map<String,Integer> mapping=new HashMap<>();
            mapping.put(a.key(),1);
            mapping.put(b.key(),1);
            List<SharedTrackApproval.Conflict> overlaps=SharedTrackApproval.conflicts(song,mapping,roster);
            check(overlaps.size()==1 && overlaps.get(0).trackId()==1,
                    "two machines can deliberately share one track ID");
            check(overlaps.get(0).musicians().stream().anyMatch(v->v.contains("Linux A"))
                    && overlaps.get(0).musicians().stream().anyMatch(v->v.contains("Linux B")),
                    "duplicate approval identifies each musician and computer");
            check(SharedTrackApproval.warning(overlaps).contains("X:1")
                    && SharedTrackApproval.warning(overlaps).contains("Arvael"),
                    "approval warning names the exact shared track and participants");
            SharedTrackApproval consent=new SharedTrackApproval();
            check(!consent.approved(song,mapping,roster),"shared part is not approved automatically");
            consent.approve(song,mapping,roster);
            check(consent.approved(song,mapping,roster),"explicit approval covers exactly the current layout");
            check(consent.approved(song,Map.of(b.key(),1,a.key(),1),List.of(b,a)),
                    "reordered roster or map does not invalidate identical arrangements");
            mapping.put(b.key(),2);
            consent.invalidateIfChanged(song,mapping,roster);
            check(!consent.approved(song,mapping,roster),"changing a part invalidates old approval");
            mapping.put(b.key(),1);
            check(!consent.approved(song,mapping,roster),
                    "reverting an edited part does not silently reinstate approval");
            consent.approve(song,mapping,roster);
            Path changed=folder.resolve("other.abc");
            Files.writeString(changed,Files.readString(abc));
            Music.Song otherSong=Music.read(folder,changed);
            consent.invalidateIfChanged(otherSong,mapping,roster);
            check(!consent.approved(otherSong,mapping,roster),"new song needs separate approval");
            check(SharedTrackApproval.conflicts(song,Map.of(a.key(),1,b.key(),2),roster).isEmpty(),
                    "unique part mappings do not trigger an overlap warning");
            Path setlist=folder.resolve("shared.json");
            Files.writeString(setlist,"""
                    {"format":"lotro-conductor-setlist","version":1,"name":"Duet",
                     "songs":[{"gamePath":"/duet","parts":[
                     {"trackId":1,"character":"Sinduriel","instrument":"Basic Lute"},
                     {"trackId":1,"character":"Arvael","instrument":"Basic Lute"}]}]}
                    """);
            ConductorSetlist imported=ConductorSetlist.read(setlist);
            Map<String,Integer> setlistAssignments=imported.assignmentsFor(imported.songs().get(0),roster,song);
            check(setlistAssignments.size()==2 && setlistAssignments.get(a.key())==1
                    && setlistAssignments.get(b.key())==1,
                    "Planner imports can intentionally give two musicians the same X: part");
            Files.writeString(setlist,Files.readString(setlist).replace("\"Arvael\"","\"sinduriel\""));
            expectFailure(()->ConductorSetlist.read(setlist),
                    "one character cannot appear twice in a song's setlist assignments");
        } finally { deleteTree(folder); }
    }

    /** No protocol uniqueness rule should block independent remote musicians
     * receiving the same valid X: part, if the conductor explicitly approves it. */
    private static void sharedTracksAcrossNetwork() throws Exception {
        try (BandServer server=new BandServer(0,"shared-parts-test",true)) {
            server.start();
            BlockingQueue<Wire.Message> hostInbox=new LinkedBlockingQueue<>();
            BlockingQueue<Wire.Message> followerInbox=new LinkedBlockingQueue<>();
            try (BandClient host=new BandClient("127.0.0.1",server.port(),"host","shared-parts-test",true,
                        queueListener(hostInbox));
                 BandClient follower=new BandClient("127.0.0.1",server.port(),"follower","shared-parts-test",false,
                        queueListener(followerInbox))) {
                follower.send("REGISTER","Sinduriel","Basic Lute","win1");
                follower.send("REGISTER","Arvael","Basic Lute","win2");
                check(untilRosterSize(hostInbox,2)==2,"host sees both remote musicians before shared sync");
                String hash="a".repeat(64);
                host.send("ACTION",follower.id(),"Sinduriel","PREPARE","/duet","1",hash);
                host.send("ACTION",follower.id(),"Arvael","PREPARE","/duet","1",hash);
                Wire.Message first=until(followerInbox,"DO",m->m.at(1).equals("PREPARE"));
                Wire.Message second=until(followerInbox,"DO",m->m.at(1).equals("PREPARE"));
                check(Set.of(first.at(0),second.at(0)).equals(Set.of("Sinduriel","Arvael")),
                        "both remote musicians receive prepare messages for shared X: part");
                check(first.at(3).equals("1") && second.at(3).equals("1"),
                        "network retains the exact shared track ID");
            }
        }
    }
    private static void wireAndAuth() throws Exception {
        String line = Wire.encode("MESSAGE", "hello|world", "a:b\nÅé", "");
        Wire.Message msg = Wire.decode(line);
        check(msg.at(0).equals("hello|world"), "Wire must escape pipes");
        check(msg.at(1).equals("a:b\nÅé"), "Wire must encode UTF-8/newlines");
        check(msg.at(2).isEmpty(), "Wire must preserve empty fields");
        expectFailure(() -> Wire.decode("NOTVALID|%%%%"), "broken Base64");
        check(Auth.equalsSecure(Auth.response("password", "nonce", "id", "agent"),
                Auth.response("password", "nonce", "id", "agent")), "HMAC reproducible");
        check(!Auth.equalsSecure(Auth.response("password", "nonce1", "id", "agent"),
                Auth.response("password", "nonce2", "id", "agent")), "HMAC must depend on challenge");
    }
    private static void optionsAndMusic() throws Exception {
        expectFailure(() -> Options.parse(new String[]{"--host", "--key", "short"}), "short key");
        check(Options.parse(new String[]{"--host", "--key", "abcdefgh"}).host(), "CLI host");
        Options local = Options.parse(new String[]{"--local"});
        check(local.local() && local.host() && local.key().length() >= 8, "Local mode uses an internal key");
        expectFailure(() -> Options.parse(new String[]{"--local", "--join", "localhost"}), "No LAN in local mode");
        try (BandServer loopback = new BandServer(0, local.key(), true)) {
            check(loopback != null, "Loopback-only server can be created");
        }
        Path base = Files.createTempDirectory("conductor-test-");
        try {
            Path file = base.resolve("Folk").resolve("test song.abc");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "X:1\nT:Basic Lute\nM:4/4\nK:C\nC4 |\nX:2\nT:Basic Harp\nM:4/4\nK:C\nG4 |\n");
            Music.Song song = Music.read(base,file);
            check(song.gamePath().equals("/Folk/test song"), "extension-free game path");
            check(song.parts().size() == 2, "ABC parts count");
            check(song.parts().get(1).id() == 2, "ABC X: id");
            Music.verifyLocalFile(base,song.gamePath(),song.sha256());
            checks++;
            expectFailure(() -> Music.verifyLocalFile(base,song.gamePath(),"0".repeat(64)), "hash mismatch");
            expectFailure(() -> Music.validateGamePath("/../escape"), "path traversal");
            expectFailure(() -> Music.read(base,base.resolve("missing.abc")), "missing file");
        } finally { deleteTree(base); }
    }
    /** SongBook3's actual table grammar, artist metadata, path safety and matching continuity. */
    private static void songBookDataAndQuickslotAssignment() throws Exception {
        Path root = Files.createTempDirectory("conductor-songbook-data-");
        try {
            Path folder=root.resolve("songs/Condensed/Two Parts");
            Files.createDirectories(folder);
            Path abc=folder.resolve("Demo.abc");
            Files.writeString(abc,"X:1\nT:Duet - Basic Lute\nK:C\nC|\nX:2\nT:Duet - Basic Harp\nK:C\nC|\n");
            String lua="return { Songs = { { Filepath = '/songs/Condensed/Two Parts/'; " +
                    "Filename = 'Demo'; Artist='François'; Tracks={ " +
                    "{ Id='1'; Name='Duet - Basic Lute'; PartName='Basic Lute'; }; " +
                    "{ Id='2'; Name='Duet - Basic Harp'; PartName='Basic Harp'; }; }; }; }; " +
                    "Directories={ '/songs/'; '/songs/Condensed/'; '/songs/Condensed/Two Parts/'; }; }";
            Path indexFile=root.resolve("SongBook3Data.plugindata");
            Files.writeString(indexFile,lua,StandardCharsets.UTF_8);
            MusicLibraryIndex index=MusicLibraryIndex.fromSongBook(root,indexFile);
            check(index.songs().equals(List.of(abc)),"SongBook library preserves nested folder and file name");
            check(index.songs(root,"demo","").equals(List.of(abc)),"filename search works");
            check(index.songs(root,"duet","").equals(List.of(abc)),"song title search works");
            check(index.songs(root,"","françois").equals(List.of(abc)),"artist search works");
            check(index.songs(root,"","notthisartist").isEmpty(),"artist search filters correctly");
            check(index.folders(root.resolve("songs"),"").contains(root.resolve("songs/Condensed")),
                    "directory listing follows SongBook metadata");
            check(index.details(abc).instruments().get(2).equals("Basic Harp"),"PartName metadata retained");
            check(index.addTrackInstruments(Music.read(root,abc)).parts().size()==2,
                    "ABC stays authoritative for part IDs");
            Files.writeString(abc,"X:1\nT:First Part\nK:C\nC|\nX:2\nT:Second Part\nK:C\nC|\n");
            Music.Song decorated=index.addTrackInstruments(Music.read(root,abc));
            check("Basic Harp".equals(InstrumentMatcher.requiredInstrument(decorated.parts().get(1).title())),
                    "PartName from SongBook provides instrument when ABC T: is non-descriptive");
            // Deliberately invalid Lua expression is never executed.
            Files.writeString(indexFile,"return os.execute('bad')");
            expectFailure(()->MusicLibraryIndex.fromSongBook(root,indexFile),"reject executable Lua instead of running it");
            Files.writeString(indexFile,"return {Songs={{Filepath='/../'; Filename='x'; Tracks={};}}}");
            expectFailure(()->MusicLibraryIndex.fromSongBook(root,indexFile),"ignore unsafe library paths");
            SettingsStore settings=new SettingsStore(root.resolve("preferences.properties"));
            settings.setSongBookLibraryFile(indexFile.toString());
            check(new SettingsStore(settings.path()).songBookLibraryFile().equals(indexFile.toString()),
                    "SongBook library file setting persists");

            CharacterInfo a=new CharacterInfo("node","PC","A","Basic Lute","win1","registered");
            CharacterInfo b=new CharacterInfo("node","PC","B","Basic Harp","win2","registered");
            ProfileStore.Profile pa=new ProfileStore.Profile("A","Basic Lute",null,
                    Map.of("Basic Lute","ctrl+1","Basic Harp","ctrl+2"));
            ProfileStore.Profile pb=new ProfileStore.Profile("B","Basic Harp",null,
                    Map.of("Basic Lute","ctrl+3"));
            List<Music.Part> parts=List.of(new Music.Part(1,"Song - Basic Lute"),
                    new Music.Part(2,"Song - Basic Harp"));
            Map<String,Integer> selected=QuickslotAssigner.assign(parts,List.of(a,b),List.of(pa,pb),Map.of(),
                    Map.of(a.key(),"Basic Harp",b.key(),"Basic Lute"));
            check(selected.get(a.key())==2 && selected.get(b.key())==1,
                    "global matcher maximizes coverage and keeps last instruments");
            check(QuickslotAssigner.assign(parts,List.of(a,b),List.of(pa,pb),Map.of(b.key(),1),
                    Map.of()).get(a.key())==2,"manual assignment stays locked");
            check(QuickslotAssigner.assign(List.of(new Music.Part(5,"Basic Drum")),List.of(a,b),
                    List.of(pa,pb),Map.of(),Map.of()).isEmpty(),"missing quickslot remains unassigned");
            check("Basic Harp".equals(InstrumentMatcher.requiredInstrument("Duet - Basic Harp")),
                    "instrument extraction from ABC track title");
        } finally { deleteTree(root); }
    }
    /** Browser lists actual folders and clicks select one ABC without a separate load button. */
    private static void musicLibraryBrowser() throws Exception {
        Path root = Files.createTempDirectory("conductor-browser-");
        try {
            Path folk = root.resolve("Folk");
            Path classical = root.resolve("Classical");
            Path nested = folk.resolve("Duets");
            Files.createDirectories(nested);
            Files.createDirectories(classical);
            Path one = folk.resolve("SongA.abc"), two = nested.resolve("SongB.ABC"),
                    three = classical.resolve("Waltz.abc");
            Files.writeString(one,"X:1\nT:Basic Lute\nK:C\nC4|\n");
            Files.writeString(two,"X:1\nT:Harp\nK:C\nC4|\n");
            Files.writeString(three,"X:1\nT:Flute\nK:C\nC4|\n");
            Files.writeString(root.resolve("notes.txt"),"not music");
            MusicLibraryIndex index = MusicLibraryIndex.scan(root);
            check(index.songs().size() == 3, "browser indexes ABC files case-insensitively");
            check(index.folders(root, "").equals(List.of(classical,folk)),
                    "folder list displays immediate children");
            check(index.folders(folk, "").equals(List.of(root,nested)),
                    "folder list includes parent navigation");
            check(index.songs(folk, "").equals(List.of(one)),
                    "song list displays only selected folder");
            check(index.songs(folk, "songb").equals(List.of(two)),
                    "song search finds matches in nested folders");
            check(index.folders(root, "duet").equals(List.of(nested)),
                    "folder search finds nested folders");
            check(index.songs(root, "nomatch").isEmpty(), "song search narrows results");
            check(MusicLibraryIndex.scan(root.resolve("missing")).songs().isEmpty(),
                    "missing music folder yields empty browser");
        } finally { deleteTree(root); }
    }

    private static void bridge() throws Exception {
        Path file = Files.createTempFile("conductor-bridge", ".plugindata");
        try {
            Files.writeString(file,"\"CG1%Bellara%Basic Lute%/my-song%4%\"",StandardCharsets.UTF_8);
            BridgeStatus.Data data = BridgeStatus.read(file);
            check(data.name().equals("Bellara"), "bridge character");
            check(data.instrument().equals("Basic Lute"), "bridge instrument");
            check(data.track() == 4, "bridge part ID");
            java.nio.file.attribute.FileTime lastWrite = BridgeStatus.modified(file);
            expectFailure(() -> BridgeStatus.waitForNewReport(file, lastWrite,
                    java.time.Duration.ofMillis(180)), "old report must not be treated as new");
            Thread writer = new Thread(() -> {
                try {
                    Thread.sleep(220);
                    Files.writeString(file, "\"CG1%Bellara%Basic Harp%/my-song%2%\"", StandardCharsets.UTF_8);
                    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(2)));
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            writer.start();
            BridgeStatus.Data fresh = BridgeStatus.waitForNewReport(file,lastWrite,java.time.Duration.ofSeconds(2));
            check(fresh.instrument().equals("Basic Harp") && fresh.track() == 2,"fresh report observed after save");
            writer.join(2000);
        } finally { Files.deleteIfExists(file); }
    }
    private static void bridgeIdentityAndStates() throws Exception {
        Path root = Files.createTempDirectory("conductor-identify-");
        try {
            Path one = root.resolve("account/AllServers/Sinduriel/ConductorStatus.plugindata");
            Files.createDirectories(one.getParent());
            String token = BridgeDiscovery.newToken();
            check(token.matches("[0-9a-f]{32}"), "secure identity token is 128-bit hexadecimal");
            Files.writeString(one, "return \"CG2%Sinduriel%Basic Harp%/song%2%observed-on%ready%"+token+"%\"");
            BridgeStatus.Data data = BridgeStatus.read(one);
            check(data.name().equals("Sinduriel") && data.track() == 2,"CG2 retains character and part");
            check(data.hasModeEvidence() && data.ready(),"CG2 exposes observed music mode and local ready evidence");
            check(data.token().equals(token),"CG2 carries identification nonce");
            check(BridgeDiscovery.matching(root, token).size() == 1,"only exact token matches");
            check(BridgeDiscovery.matching(root, BridgeDiscovery.newToken()).isEmpty(),"stale reports cannot identify windows");
            Path two = root.resolve("account/AllServers/Other/ConductorStatus.plugindata");
            Files.createDirectories(two.getParent());
            Files.writeString(two, "\"CG2%Other%Basic Lute%/song%1%unknown%unconfirmed%"+token+"%\"");
            expectFailure(() -> BridgeDiscovery.awaitUnique(root, token, java.time.Duration.ofMillis(200)),
                    "ambiguous identity nonce must fail closed");
            Files.delete(two);
            check(BridgeDiscovery.awaitUnique(root,token,java.time.Duration.ofMillis(250)).isPresent(),
                    "fresh unique bridge challenge may be accepted");
        } finally { deleteTree(root); }

        Path sandbox = Files.createTempDirectory("conductor-identity-integration-");
        BandServer server = new BandServer(0,"identitypass"); server.start();
        try (BandClient conductor = new BandClient("127.0.0.1",server.port(),"host","identitypass",true,
                     queueListener(new LinkedBlockingQueue<>()));
             BandClient machine = new BandClient("127.0.0.1",server.port(),"x11","identitypass",false,
                     queueListener(new LinkedBlockingQueue<>()))) {
            GameWindow one = new GameWindow("0xabc1", "The Lord of the Rings Online™");
            GameWindow two = new GameWindow("0xabc2", "The Lord of the Rings Online™");
            GameBackend challengeBackend = new GameBackend() {
                @Override public List<GameWindow> listWindows() { return List.of(one,two); }
                @Override public boolean isValidWindow(GameWindow w) { return listWindows().contains(w); }
                @Override public void sendChat(GameWindow w, String cmd) throws Exception {
                    check(cmd.startsWith("/conductor identify "),"identify sends only challenge command");
                    String nonce = cmd.substring("/conductor identify ".length());
                    String character = w.equals(one) ? "Sinduriel" : "Bellara";
                    Path target = sandbox.resolve("Acct/AllServers/"+character+"/ConductorStatus.plugindata");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target,"\"CG2%"+character+"%Basic Lute%/demo%1%unknown%unconfirmed%"+nonce+"%\"");
                }
                @Override public String description() { return "mock X11 challenges"; }
            };
            try (LocalAgent agent = new LocalAgent(challengeBackend, sandbox, true, msg -> {})) {
                agent.setNetwork(machine);
                List<String> outcomes = agent.identifyWindows(sandbox);
                check(outcomes.size() == 2 && agent.bindings().size() == 2,
                        "two windows can be registered without title/PID guessing");
                check(agent.reportFor("Sinduriel") != null,"auto discovered character's report attached");
                check(agent.identifyWindows(sandbox).stream().allMatch(x -> x.contains("already registered")),
                        "subsequent probes skip existing bindings");
            }
        } finally { server.close(); deleteTree(sandbox); }
    }

    /** Saved files identify a character; a delayed fresh challenge connects it to a window. */
    private static void delayedIdentityAndCachedProfiles() throws Exception {
        Path root = Files.createTempDirectory("conductor-delayed-identity-");
        Path file1 = root.resolve("account/AllServers/Sinduriel/ConductorStatus.plugindata");
        Path file2 = root.resolve("account/AllServers/Bellara/ConductorStatus.plugindata");
        Files.createDirectories(file1.getParent());
        Files.createDirectories(file2.getParent());
        // Each file is already filled but must NOT be trusted to prove its X11 window.
        Files.writeString(file1, "return \"CG2%Sinduriel%Basic Lute%%0%unknown%unconfirmed%%\"");
        Files.writeString(file2, "return \"CG2%Bellara%Basic Harp%%0%unknown%unconfirmed%%\"");
        ProfileStore cache = new ProfileStore(root.resolve("profiles.properties"));
        cache.upsert(new ProfileStore.Profile("Sinduriel", "Basic Lute", file1));
        cache.upsert(new ProfileStore.Profile("Bellara", "Basic Harp", file2));
        BandServer server = new BandServer(0,"delayedIdentity123"); server.start();
        List<Thread> writers = new ArrayList<>();
        List<String> progress = new CopyOnWriteArrayList<>();
        try (BandClient conductor = new BandClient("127.0.0.1",server.port(),"test-host","delayedIdentity123",true,
                    queueListener(new LinkedBlockingQueue<>()));
             BandClient machine = new BandClient("127.0.0.1",server.port(),"test-agent","delayedIdentity123",false,
                    queueListener(new LinkedBlockingQueue<>()))) {
            GameWindow first = new GameWindow("0xa1", "The Lord of the Rings Online™");
            GameWindow second = new GameWindow("0xa2", "The Lord of the Rings Online™");
            GameBackend slowBackend = new GameBackend() {
                @Override public List<GameWindow> listWindows() { return List.of(first,second); }
                @Override public boolean isValidWindow(GameWindow w) { return listWindows().contains(w); }
                @Override public void sendChat(GameWindow window, String command) {
                    String nonce = command.substring("/conductor identify ".length());
                    Path file = window.equals(first) ? file1 : file2;
                    String who = window.equals(first) ? "Sinduriel" : "Bellara";
                    int delayMs = window.equals(first) ? 5100 : 650;
                    Thread writer = new Thread(() -> {
                        try {
                            Thread.sleep(delayMs);
                            Files.writeString(file,"return \"CG2%" + who + "%Basic Lute%%0%unknown%unconfirmed%" + nonce + "%\"");
                        } catch (Exception e) { throw new RuntimeException(e); }
                    });
                    writers.add(writer);
                    writer.start();
                }
                @Override public String description() { return "simulated delayed bridge"; }
            };
            try (LocalAgent agent = new LocalAgent(slowBackend, root, true, progress::add, cache)) {
                agent.setNetwork(machine);
                List<String> outcomes = agent.identifyWindows(null, java.time.Duration.ofSeconds(9),
                        progress::add, () -> false);
                check(agent.bindings().size() == 2, "saved paths + delayed files identify both LOTRO windows");
                check(agent.bindings().stream().anyMatch(b -> b.character().equals("Sinduriel") && b.window().id().equals("0xa1")),
                        "slow 5+ second PluginData update binds correct X11 window");
                check(agent.bindings().stream().anyMatch(b -> b.character().equals("Bellara") && b.window().id().equals("0xa2")),
                        "second window response may arrive first");
                check(progress.stream().anyMatch(v -> v.contains("2 saved bridge paths")),
                        "identification uses cached character files without a PluginData folder");
                check(outcomes.stream().noneMatch(v -> v.contains("no identity response")),
                        "valid slow responses do not trigger timeout");
            }
        } finally {
            for (Thread t : writers) t.join(10000);
            server.close(); deleteTree(root);
        }
        // A valid old file never provides sufficient evidence to bind a window.
        Path stale = Files.createTempDirectory("conductor-stale-identity-");
        try {
            Path file = stale.resolve("ConductorStatus.plugindata");
            Files.writeString(file,"\"CG2%Stale%Lute%%0%unknown%unconfirmed%oldtoken%\"");
            check(BridgeDiscovery.matchingFiles(List.of(file),BridgeDiscovery.newToken()).isEmpty(),
                    "an existing character report is NOT proof of window association");
        } finally { deleteTree(stale); }
    }

    private static void settingsAndWatcher() throws Exception {
        Path dir = Files.createTempDirectory("conductor-watch-test-");
        try {
            Path preferences = dir.resolve("settings.properties");
            SettingsStore defaults = new SettingsStore(preferences);
            check(defaults.confirmActions() && !defaults.autoAcceptMatches() && !defaults.showDevLog(),
                    "safe settings defaults, development log hidden");
            defaults.setConfirmActions(false);
            defaults.setAutoAcceptMatches(true);
            defaults.setShowDevLog(true);
            defaults.setIdentifyWaitSeconds(180);
            SettingsStore reloaded = new SettingsStore(preferences);
            check(!reloaded.confirmActions() && reloaded.autoAcceptMatches() && reloaded.showDevLog(),
                    "settings and log visibility persist across runs");
            check(reloaded.identifyWaitSeconds() == 180, "identification timeout persisted across runs");

            Path status = dir.resolve("ConductorStatus.plugindata");
            BlockingQueue<Path> seen = new LinkedBlockingQueue<>();
            try (BridgeMonitor monitor = new BridgeMonitor(seen::offer, System.out::println)) {
                monitor.track(status); // new file is currently absent
                Files.writeString(status, "\"CG1%Test%Basic Lute%%0%\"");
                check(status.equals(seen.poll(3,TimeUnit.SECONDS)), "watcher notices new status file");
                // Prove an actual content update is recognized without /conductor report.
                Files.writeString(status, "\"CG1%Test%Basic Harp%%0%\"");
                check(status.equals(seen.poll(3,TimeUnit.SECONDS)), "watcher notices changed instrument");
                check(BridgeStatus.read(status).instrument().equals("Basic Harp"), "changed status readable");
            }
        } finally { deleteTree(dir); }
    }
    private static void profilesAndMatching() throws Exception {
        Path dir = Files.createTempDirectory("conductor-profiles-");
        try {
            Path profileFile = dir.resolve("settings").resolve("profiles.properties");
            ProfileStore first = new ProfileStore(profileFile);
            Path status = dir.resolve("some path").resolve("ConductorStatus.plugindata");
            first.upsert(new ProfileStore.Profile("Sinduriel", "Basic Lute", status));
            first.upsert(new ProfileStore.Profile("Harpist", "Basic Harp", null));
            ProfileStore second = new ProfileStore(profileFile);
            check(second.list().size() == 2, "profiles persist between launches");
            check(second.find("sinduriel").bridgeStatus().equals(status), "status file path restored");
            check(second.find("Harpist").instrument().equals("Basic Harp"), "instrument restored");
            second.forget("harpist");
            check(new ProfileStore(profileFile).list().size() == 1, "saved profile can be forgotten");

            List<Music.Part> parts = List.of(new Music.Part(1,"Basic Lute"),
                    new Music.Part(2,"Basic Harp"),new Music.Part(3,"Basic Lute"));
            CharacterInfo lute = new CharacterInfo("node","computer","LutePlayer","Basic Lute","lotro","registered");
            CharacterInfo harp = new CharacterInfo("node","computer","HarpPlayer","Basic Harp","lotro2","registered");
            CharacterInfo lute2 = new CharacterInfo("node","computer","AnotherLute","Basic Lute","lotro3","registered");
            check(InstrumentMatcher.explicitlyMatches("Basic Lute Melody", "Basic Lute"), "recognize exact instrument label");
            check(!InstrumentMatcher.explicitlyMatches("Lute of Ages Solo", "Basic Lute"), "do not confuse lute variants");
            Map<String,Integer> suggested = InstrumentMatcher.suggest(parts, List.of(lute, harp, lute2), Map.of());
            check(suggested.size() == 1 && suggested.get(harp.key()) == 2,
                    "ambiguous multiple-lute matches remain manual");
            check(InstrumentMatcher.suggest(parts, List.of(lute, harp), Map.of()).size() == 1,
                    "one lute cannot be randomly assigned among two matching parts");
            Map<String,Integer> preserved = InstrumentMatcher.suggest(parts, List.of(lute,harp), Map.of(harp.key(), 3));
            check(preserved.get(harp.key()) == null, "manual assignments are not overwritten");
        } finally { deleteTree(dir); }
    }
    /** Unit-test the exact X11 BadWindow failure format without a real desktop or LOTRO. */
    private static void x11WindowRaceAndGuard() throws Exception {
        String one = "0x06000008  0 9250 kb-win The Lord of the Rings Online™\n";
        String two = "0x08a0000a  0 9522 kb-win The Lord of the Rings Online™\n";
        String bad = "X Error of failed request:  BadWindow (invalid Window parameter)\n" + one + two;
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        X11Backend backend = new X11Backend((seconds, argv) -> {
            if (argv[0].equals("wmctrl")) {
                return attempts.incrementAndGet() == 1 ? new ProcessTools.Result(1, bad) :
                    new ProcessTools.Result(0, one + two);
            }
            if (argv[0].equals("xdotool") && argv[1].equals("getwindowname"))
                return new ProcessTools.Result(0, "The Lord of the Rings Online™\n");
            throw new AssertionError("Unexpected X11 subprocess: " + String.join(" ", argv));
        });
        List<GameWindow> found = backend.listWindows();
        check(found.size() == 2, "BadWindow listing should retry and find both real LOTRO windows");
        check(attempts.get() == 2, "BadWindow should be retried");
        check(backend.isValidWindow(found.get(0)), "Registered XID should be independently verifiable");
        check(X11Backend.parseWindowList(bad).size() == 2,
                "BadWindow diagnostic lines must not corrupt partial listing");
        check(X11Backend.parseWindowList("0x02e00003 0 1972 kb-win nemo-desktop\n").isEmpty(),
                "Unrelated windows must never count as LOTRO");

        java.util.concurrent.atomic.AtomicInteger rescans = new java.util.concurrent.atomic.AtomicInteger();
        X11Backend partial = new X11Backend((seconds, argv) -> {
            rescans.incrementAndGet();
            return new ProcessTools.Result(1, bad);
        });
        check(partial.listWindows().size() == 2, "Use valid partial listing if all retries race");
        check(rescans.get() == 3, "Transient wmctrl enumeration failure has bounded retries");

        java.util.concurrent.atomic.AtomicInteger keystrokes = new java.util.concurrent.atomic.AtomicInteger();
        X11Backend missingTarget = new X11Backend((seconds, argv) -> {
            if (argv[0].equals("xdotool") && argv[1].equals("getwindowname"))
                return new ProcessTools.Result(1, "BadWindow");
            keystrokes.incrementAndGet();
            return new ProcessTools.Result(0, "");
        });
        check(!missingTarget.isValidWindow(found.get(0)), "Missing target cannot be controlled");
        expectFailure(() -> missingTarget.sendChat(found.get(0), "/conductor report"),
                "Must reject stale target before typing");
        check(keystrokes.get() == 0, "Stale XID must not cause any input or focus changes");

        X11Backend wrongFocus = new X11Backend((seconds, argv) -> {
            if (argv[0].equals("xdotool") && argv[1].equals("getwindowname"))
                return new ProcessTools.Result(0, "The Lord of the Rings Online™\n");
            if (argv[0].equals("xdotool") && argv[1].equals("getactivewindow"))
                return new ProcessTools.Result(0, "123\n");
            if (argv[0].equals("xdotool") && argv[1].equals("key"))
                throw new AssertionError("Keyboard command sent to wrong foreground window");
            return new ProcessTools.Result(0, "");
        });
        expectFailure(() -> wrongFocus.sendChat(found.get(0), "/conductor report"),
                "Must abort after unsuccessful focus request");
        expectFailure(() -> wrongFocus.focusWindow(found.get(0)),
                "Identify/focus-only action must also fail closed on wrong window");
        checks++;
    }

    /** Slow-clients: do not refocus the next LOTRO window until chat has opened,
     * all text is emitted, Return is sent, and the post-submit barrier elapsed.
     * These are testable delivery barriers, not claims about in-game acknowledgement.
     */
    private static void slowChatTypingBarriers() throws Exception {
        Path dir=Files.createTempDirectory("conductor-pacing-");
        try {
            SettingsStore prefs=new SettingsStore(dir.resolve("settings.properties"));
            check(prefs.chatTiming().equals(ChatInputTiming.DEFAULT), "Default chat pacing exists");
            ChatInputTiming timing=new ChatInputTiming(30, 65, 25, 140);
            prefs.setChatTiming(timing);
            check(new SettingsStore(prefs.path()).chatTiming().equals(timing),
                    "Each machine persists input pacing independently");
            // v0.12.6 four-value settings stay valid; a per-character component is opt-in.
            check(timing.afterEnterPerCharacterMs() == 0 && timing.afterEnterDelayMs("/long path") == 140,
                    "Legacy four-argument pacing preserves the original fixed after-Enter wait");
            ChatInputTiming adaptive = new ChatInputTiming(30, 65, 25, 100, 15);
            check(adaptive.afterEnterDelayMs("/x") == 130,
                    "Short command gets the minimum plus two characters");
            check(adaptive.afterEnterDelayMs("/play song sync") == 100 + 15 * 15,
                    "Spaces and slash characters count toward the delay");
            check(adaptive.afterEnterDelayMs("a\uD83D\uDC9Ab") == 145,
                    "Delay counts Unicode code points rather than UTF-16 storage units");
            check(new ChatInputTiming(0, 0, 0, 100, 100).afterEnterDelayMs("a".repeat(1000))
                    == ChatInputTiming.MAX_SETTLE_MS, "Unusually long commands cannot block the queue indefinitely");
            prefs.setChatTiming(adaptive);
            check(new SettingsStore(prefs.path()).chatTiming().equals(adaptive),
                    "Adaptive per-character input timing persists on this computer");
            // A pre-upgrade settings file lacks the new key; it must default to no extra delay.
            Files.writeString(prefs.path(), "chatOpenMs=300\nchatKeyDelayMs=45\nchatBeforeEnterMs=220\nchatAfterEnterMs=550\n");
            check(new SettingsStore(prefs.path()).chatTiming().equals(ChatInputTiming.DEFAULT),
                    "Existing v0.12.6 settings load without migration");
            expectFailure(() -> new ChatInputTiming(0, 20, 20, 20, 101),
                    "Out-of-range per-character settling delay rejected");
            expectFailure(() -> new ChatInputTiming(-1, 20, 20, 20), "Negative chat-open delay rejected");
            expectFailure(() -> new ChatInputTiming(0, 201, 20, 20), "Overlarge character delay rejected");
            check(new ChatInputTiming(300, 120, 220, 500).typeTimeoutSeconds(350) >= 42,
                    "Long typed commands get a proportional process timeout");
            java.util.concurrent.atomic.AtomicLong focused=new java.util.concurrent.atomic.AtomicLong(0);
            java.util.List<String> operations=new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.List<Long> milliseconds=new java.util.concurrent.CopyOnWriteArrayList<>();
            X11Backend backend=new X11Backend((timeout, args) -> {
                if(args[0].equals("wmctrl") && args[1].equals("-ia")) {
                    focused.set(Long.parseUnsignedLong(args[2].substring(2),16));
                    operations.add("FOCUS:"+args[2]); milliseconds.add(System.nanoTime());
                } else if(args[0].equals("xdotool") && args[1].equals("getactivewindow")) {
                    return new ProcessTools.Result(0,Long.toUnsignedString(focused.get())+"\n");
                } else if(args[0].equals("xdotool") && args[1].equals("getwindowname")) {
                    return new ProcessTools.Result(0,"The Lord of the Rings Online™\n");
                } else if(args[0].equals("xdotool") && args[1].equals("key")) {
                    operations.add("ENTER:"+focused.get()); milliseconds.add(System.nanoTime());
                } else if(args[0].equals("xdotool") && args[1].equals("type")) {
                    operations.add("TYPE:"+focused.get()+":"+args[4]+":"+args[6]); milliseconds.add(System.nanoTime());
                    if(!args[5].equals("--")) throw new AssertionError("Unrecognized xdotool argument order");
                } else throw new AssertionError("Unexpected x11 subprocess: "+String.join(" ",args));
                return new ProcessTools.Result(0,"");
            });
            backend.setChatTiming(timing);
            GameWindow first=new GameWindow("0x00000101","The Lord of the Rings Online™");
            GameWindow second=new GameWindow("0x00000102","The Lord of the Rings Online™");
            backend.sendChat(first,"/music");
            backend.sendChat(second,"/playstart");
            check(operations.equals(java.util.List.of(
                    "FOCUS:0x00000101","ENTER:257","TYPE:257:65:/music","ENTER:257",
                    "FOCUS:0x00000102","ENTER:258","TYPE:258:65:/playstart","ENTER:258")),
                    "X11 typed commands and Return events stay bound to the intended window, in order");
            long lastEnterFirst=milliseconds.get(3); // last Return for first character
            long nextFocus=milliseconds.get(4);
            check(nextFocus-lastEnterFirst >= 110_000_000L,
                    "Input call remains active after final Enter before next window can focus");
            // Test the real X11 backend with a short command then a much longer one.
            // All commands are injected via a mock runner; no live desktop input occurs.
            ChatInputTiming adaptiveWait = new ChatInputTiming(0, 0, 0, 25, 12);
            backend.setChatTiming(adaptiveWait);
            int index = milliseconds.size();
            backend.sendChat(first, "/x");
            backend.sendChat(second, "/play a long song sync");
            backend.sendChat(first, "/z");
            long shortDelayNs = milliseconds.get(index+4) - milliseconds.get(index+3);
            long longDelayNs = milliseconds.get(index+8) - milliseconds.get(index+7);
            check(shortDelayNs >= 40_000_000L,
                    "Short command settles proportionally before next window gets focus");
            check(longDelayNs >= 260_000_000L,
                    "Long command waits longer before allowing the next LOTRO window focus");
        } finally {
            try(var paths=Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {Files.deleteIfExists(path);} catch(Exception ignored) { }
                });
            }
        }
    }

    private static void networkAndCommands() throws Exception {
        BandServer server = new BandServer(0,"sharedpassword"); server.start();
        BlockingQueue<Wire.Message> hostInbox = new LinkedBlockingQueue<>();
        BlockingQueue<Wire.Message> agentInbox = new LinkedBlockingQueue<>();
        BandClient.Listener hostListener = queueListener(hostInbox);
        BandClient.Listener remoteListener = queueListener(agentInbox);
        try (BandClient host = new BandClient("127.0.0.1",server.port(),"win-master","sharedpassword",true,hostListener);
             BandClient agent = new BandClient("127.0.0.1",server.port(),"linux-agent","sharedpassword",false,remoteListener)) {
            check(!host.id().equals(agent.id()), "distinct node IDs");
            // Stream more roster entries than a single protocol message could hold.
            for (int i=1;i<=36;i++) agent.send("REGISTER", "Char"+i,"Basic Lute","test-window-"+i);
            check(untilRosterSize(agentInbox, 36) == 36, "Roster streams across individual messages");
            // A remote agent must not be permitted to issue conductor commands.
            agent.send("ACTION",agent.id(),"Char1","START","","","");
            check(until(agentInbox,"ERROR",m -> m.at(0).contains("Only the host")) != null,
                    "remote agent cannot send master actions");
            host.send("ACTION",agent.id(),"Char1","REPORT","","","");
            Wire.Message command = until(agentInbox,"DO",m -> m.at(0).equals("Char1"));
            check(command.at(1).equals("REPORT"), "host routes a report to correct computer");
            agent.send("RESULT","Char1","DRY-RUN: REPORT","not confirmed by game");
            check(until(hostInbox,"EVENT",m -> m.at(1).equals("Char1")) != null,"host receives remote result");
            agent.send("REMOVE","Char1");
            check(untilRosterSize(hostInbox, 35) == 35,"removed character from roster");
            // Closing an agent removes its remaining roster entries.
        } finally { server.close(); }
        // Reject an incorrect secret rather than silently joining a band.
        BandServer secure = new BandServer(0,"rightpassword"); secure.start();
        try { expectFailure(() -> new BandClient("127.0.0.1",secure.port(),"intruder","wrongpassword",false,queueListener(new LinkedBlockingQueue<>())),"wrong secret rejected"); }
        finally { secure.close(); }
    }
    /** Host+remote share instrument capabilities, but only the owning node knows
     * or executes keyboard bindings. Space is one key per targeted game window. */
    private static void distributedCapabilitiesAndInput() throws Exception {
        Path temp=Files.createTempDirectory("conductor-distributed-");
        try(BandServer server=new BandServer(0,"two-machine-pass",true)) {
            server.start();
            BlockingQueue<Wire.Message> hostInbox=new LinkedBlockingQueue<>();
            BlockingQueue<Wire.Message> remoteInbox=new LinkedBlockingQueue<>();
            java.util.concurrent.atomic.AtomicReference<LocalAgent> hostRef=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<LocalAgent> remoteRef=new java.util.concurrent.atomic.AtomicReference<>();
            BandClient.Listener hostListener=new BandClient.Listener() {
                public void received(Wire.Message m) {
                    if(m.type().equals("DO"))hostRef.get().onMessage(m);
                    else hostInbox.add(m);
                }
                public void disconnected(String reason) { }
            };
            BandClient.Listener remoteListener=new BandClient.Listener() {
                public void received(Wire.Message m) {
                    if(m.type().equals("DO"))remoteRef.get().onMessage(m);
                    else remoteInbox.add(m);
                }
                public void disconnected(String reason) { }
            };
            try(BandClient conductor=new BandClient("127.0.0.1",server.port(),"master","two-machine-pass",true,hostListener);
                BandClient follower=new BandClient("127.0.0.1",server.port(),"follower","two-machine-pass",false,remoteListener)) {
                ProfileStore hostProfiles=new ProfileStore(temp.resolve("host.properties"));
                ProfileStore remoteProfiles=new ProfileStore(temp.resolve("remote.properties"));
                hostProfiles.upsert(new ProfileStore.Profile("HostFlute","Basic Flute",null,Map.of("Basic Flute","Ctrl+1")));
                remoteProfiles.upsert(new ProfileStore.Profile("RemoteLute","Basic Lute",null,Map.of("Basic Lute","Alt+2")));
                List<String> hostTyped=new CopyOnWriteArrayList<>();
                List<String> remoteTyped=new CopyOnWriteArrayList<>();
                DemoBackend hostBackend=new DemoBackend("host",hostTyped::add);
                DemoBackend simulatedRemote = new DemoBackend("follower",remoteTyped::add);
                java.util.concurrent.atomic.AtomicBoolean failNextShortcut = new java.util.concurrent.atomic.AtomicBoolean();
                GameBackend remoteBackend = new GameBackend() {
                    @Override public List<GameWindow> listWindows() {return simulatedRemote.listWindows();}
                    @Override public void sendShortcut(GameWindow window, Shortcut shortcut) throws Exception {
                        if(failNextShortcut.getAndSet(false)) throw new IOException("simulated input driver failure");
                        simulatedRemote.sendShortcut(window,shortcut);
                    }
                    @Override public void sendChat(GameWindow window, String command) {simulatedRemote.sendChat(window,command);}
                    @Override public String description() {return "failable fake windows";}
                };
                try(LocalAgent hostAgent=new LocalAgent(hostBackend,temp,true,hostTyped::add,hostProfiles);
                    LocalAgent remoteAgent=new LocalAgent(remoteBackend,temp,true,remoteTyped::add,remoteProfiles)) {
                    hostRef.set(hostAgent);remoteRef.set(remoteAgent);
                    hostAgent.setNetwork(conductor);remoteAgent.setNetwork(follower);
                    hostAgent.register("HostFlute","Basic Flute",hostBackend.listWindows().get(0),null);
                    remoteAgent.register("RemoteLute","Basic Lute",remoteBackend.listWindows().get(0),null);
                    // Observe roster capability snapshots on the host; mappings never transmit shortcuts.
                    Set<String> complete=Set.of(conductor.id()+"::hostflute",follower.id()+"::remotelute");
                    Map<String,Set<String>> caps=awaitCapabilities(hostInbox,complete,null);
                    check(caps.get(conductor.id()+"::hostflute").contains("Basic Flute"),"host binding announced");
                    check(caps.get(follower.id()+"::remotelute").contains("Basic Lute"),"remote binding announced");
                    check(caps.values().stream().flatMap(Collection::stream).noneMatch(k->k.contains("Ctrl+")||k.contains("Alt+")),
                            "no actual keyboard shortcuts are transmitted with capabilities");
                    var members=List.of(new CharacterInfo(conductor.id(),"host","HostFlute","Basic Flute","window",""),
                            new CharacterInfo(follower.id(),"follower","RemoteLute","Basic Lute","window",""));
                    Map<String,Integer> proposed=QuickslotAssigner.assignWithCapabilities(
                            List.of(new Music.Part(2,"Basic Lute"),new Music.Part(3,"Basic Flute")),members,caps,Map.of(),Map.of());
                    check(proposed.get(members.get(0).key())==3 && proposed.get(members.get(1).key())==2,
                            "Assign All matches characters to their owning computer's quickslot capabilities");
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP","Basic Lute","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("INPUT SENT: EQUIP"));
                    check(remoteTyped.stream().anyMatch(k->k.contains("quickslot Alt+2")),
                            "remote Equip resolves and activates the follower's private mapping");
                    check(hostTyped.stream().noneMatch(k->k.contains("Alt+2")),"host never presses a follower quickslot");
                    remoteTyped.clear();
                    // With the setting enabled, sending the same instrument after
                    // a successful normal Equip must not press the same shortcut twice.
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_CHANGED","Basic Lute","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("EQUIP SKIPPED: unchanged"));
                    check(remoteTyped.stream().noneMatch(k->k.startsWith("DEMO")),
                            "unchanged remote Equip skips the key even after earlier normal Equip");
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP","Basic Lute","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("INPUT SENT: EQUIP"));
                    check(remoteTyped.stream().filter(k->k.contains("quickslot Alt+2")).count()==1,
                            "turning off skip mode restores unconditional equip");
                    remoteTyped.clear();
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_CHANGED","Basic Flute","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).startsWith("FAILED:"));
                    check(remoteTyped.stream().noneMatch(k->k.startsWith("DEMO")),"unmapped remote instrument fails without input");
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_CHANGED","Basic Lute","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("EQUIP SKIPPED: unchanged"));
                    check(remoteTyped.stream().noneMatch(k->k.startsWith("DEMO")),
                            "failed equip leaves previous successful instrument intact");
                    // One SPACE command per registered window, both computers.
                    conductor.send("ACTION",conductor.id(),"HostFlute","SPACE","","","");
                    conductor.send("ACTION",follower.id(),"RemoteLute","SPACE","","","");
                    Set<String> spaced=new HashSet<>();
                    long spaceDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                    while(spaced.size()<2 && System.nanoTime()<spaceDeadline) {
                        Wire.Message event=hostInbox.poll(250,TimeUnit.MILLISECONDS);
                        if(event!=null && event.type().equals("EVENT") && event.at(3).equals("SPACE"))
                            spaced.add(event.at(1));
                    }
                    check(spaced.equals(Set.of("HostFlute","RemoteLute")),"both computers report Space input sent");
                    check(hostTyped.stream().filter(k->k.contains("quickslot SPACE")).count()==1,
                            "exactly one space key sent to host window");
                    check(remoteTyped.stream().filter(k->k.contains("quickslot SPACE")).count()==1,
                            "exactly one space key sent to remote window");
                    // Edits are reflected without re-registering or losing the window binding.
                    remoteProfiles.upsert(new ProfileStore.Profile("RemoteLute","Basic Lute",null,
                            Map.of("Basic Drum","Shift+=")));
                    remoteAgent.publishCapabilities("RemoteLute");
                    Map<String,Set<String>> changed=awaitCapabilities(hostInbox,complete,"Basic Drum");
                    check(changed.get(follower.id()+"::remotelute").equals(Set.of("Basic Drum")),
                            "remote quickslot edits replace old capabilities atomically");
                    Map<String,Integer> newMatch=QuickslotAssigner.assignWithCapabilities(
                            List.of(new Music.Part(2,"Basic Lute")),members,changed,Map.of(),Map.of());
                    check(newMatch.isEmpty(),"old remote instrument capability cannot remain assignable");
                    remoteTyped.clear();
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP","Basic Drum","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("INPUT SENT: EQUIP"));
                    check(remoteTyped.stream().anyMatch(k->k.contains("quickslot Shift+=")),
                            "remote Equip uses newly saved quickslot mapping");
                    remoteTyped.clear();
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_CHANGED","Basic Drum","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("EQUIP SKIPPED: unchanged"));
                    check(remoteTyped.stream().noneMatch(k->k.startsWith("DEMO")),
                            "repeat Drum shortcut is skipped on follower");
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_RESET","","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("EQUIP HISTORY CLEARED"));
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_CHANGED","Basic Drum","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("INPUT SENT: EQUIP"));
                    check(remoteTyped.stream().filter(k->k.contains("quickslot Shift+=")).count()==1,
                            "resetting Equip history causes next identical instrument to be sent");
                    remoteTyped.clear();
                    remoteProfiles.upsert(new ProfileStore.Profile("RemoteLute","Basic Lute",null,
                            Map.of("Basic Drum","Ctrl+8")));
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_CHANGED","Basic Drum","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("INPUT SENT: EQUIP"));
                    check(remoteTyped.stream().filter(k->k.contains("quickslot Ctrl+8")).count()==1,
                            "changing a saved binding requires a new keypress even for the same instrument");
                    remoteTyped.clear();
                    conductor.send("ACTION",conductor.id(),"HostFlute","EQUIP_CHANGED","Basic Flute","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("HostFlute")&&m.at(2).equals("INPUT SENT: EQUIP"));
                    check(hostTyped.stream().anyMatch(k->k.contains("quickslot Ctrl+1")),
                            "host maintains separate history for local characters");
                    hostTyped.clear();
                    conductor.send("ACTION",conductor.id(),"HostFlute","EQUIP_CHANGED","Basic Flute","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("HostFlute")&&m.at(2).equals("EQUIP SKIPPED: unchanged"));
                    check(hostTyped.stream().noneMatch(k->k.startsWith("DEMO")),
                            "host unchanged Equip also skips without extra input");
                    remoteProfiles.upsert(new ProfileStore.Profile("RemoteLute","Basic Lute",null,
                            Map.of("Basic Drum","Alt+3")));
                    remoteTyped.clear();
                    failNextShortcut.set(true);
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_CHANGED","Basic Drum","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).startsWith("FAILED: simulated input driver failure"));
                    check(remoteTyped.stream().noneMatch(k->k.contains("quickslot Alt+3")),
                            "failed backend keystroke does not appear as sent");
                    conductor.send("ACTION",follower.id(),"RemoteLute","EQUIP_CHANGED","Basic Drum","","");
                    until(hostInbox,"EVENT",m->m.at(1).equals("RemoteLute")&&m.at(2).equals("INPUT SENT: EQUIP"));
                    check(remoteTyped.stream().filter(k->k.contains("quickslot Alt+3")).count()==1,
                            "failed input is retried because equip history was not updated");
                }
            }
        }finally{deleteTree(temp);}
    }
    private static Map<String,Set<String>> awaitCapabilities(BlockingQueue<Wire.Message> queue,
            Set<String> expected, String requiredInstrument) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        Map<String,Set<String>> caps=new HashMap<>();
        boolean inRoster=false;
        while(System.nanoTime()<deadline) {
            Wire.Message msg=queue.poll(200,TimeUnit.MILLISECONDS);
            if(msg==null)continue;
            switch(msg.type()) {
                case "ROSTER_BEGIN" -> {caps.clear();inRoster=true;}
                case "ROSTER_CAPREADY" -> {if(inRoster)caps.put(msg.at(0)+"::"+msg.at(1).toLowerCase(Locale.ROOT),new HashSet<>());}
                case "ROSTER_CAP" -> {if(inRoster)caps.computeIfAbsent(msg.at(0)+"::"+msg.at(1).toLowerCase(Locale.ROOT),k->new HashSet<>()).add(msg.at(2));}
                case "ROSTER_END" -> {
                    if(inRoster&&caps.keySet().containsAll(expected)&&
                            (requiredInstrument==null||caps.values().stream().anyMatch(c->c.contains(requiredInstrument))))
                        return caps;
                    inRoster=false;
                }
                default -> { }
            }
        }
        throw new AssertionError("Timed out waiting for complete distributed quickslot capabilities");
    }
    private static void localAgentPipeline() throws Exception {
        Path dir = Files.createTempDirectory("conductor-pipeline-");
        Path music = dir.resolve("demo.abc");
        Files.writeString(music, "X:1\nT:Basic Lute\nK:C\nC4|\n");
        Music.Song song = Music.read(dir,music);
        BandServer host = new BandServer(0,"localagentpass"); host.start();
        BlockingQueue<Wire.Message> conductorInbox = new LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicReference<LocalAgent> agentRef = new java.util.concurrent.atomic.AtomicReference<>();
        List<String> typed = new CopyOnWriteArrayList<>();
        BandClient.Listener remote = new BandClient.Listener() {
            @Override public void received(Wire.Message m) { if (m.type().equals("DO")) agentRef.get().onMessage(m); }
            @Override public void disconnected(String why) { }
        };
        try (BandClient conductor = new BandClient("127.0.0.1",host.port(),"conductor","localagentpass",true,queueListener(conductorInbox));
             BandClient remoteClient = new BandClient("127.0.0.1",host.port(),"linux","localagentpass",false,remote)) {
            DemoBackend backend = new DemoBackend("linux",typed::add);
            ProfileStore equippedProfiles = new ProfileStore(dir.resolve("profiles.properties"));
            equippedProfiles.upsert(new ProfileStore.Profile("Lutist","Basic Harp",null,
                    Map.of("Basic Harp","Ctrl+=")));
            try (LocalAgent agent = new LocalAgent(backend, dir, true,typed::add,equippedProfiles)) {
                agentRef.set(agent);
                agent.setNetwork(remoteClient);
                Path status = dir.resolve("ConductorStatus.plugindata");
                Files.writeString(status, "\"CG1%Lutist%Basic Harp%/demo%1%\"");
                agent.register("Lutist","Basic Lute",backend.listWindows().get(0),status);
                Map<String,String> fresh = agent.refreshBridgeSnapshots();
                check("Basic Harp".equals(fresh.get("lutist")), "suggestion refresh uses current report instrument");
                CharacterInfo fromBridge = new CharacterInfo(remoteClient.id(),"linux","Lutist",fresh.get("lutist"),"w","ready");
                check(InstrumentMatcher.suggest(List.of(new Music.Part(2,"Basic Harp")),
                        List.of(fromBridge),Map.of()).get(fromBridge.key()) == 2,
                        "refreshed bridge report changes suggestion outcome");
                expectFailure(() -> agent.register("Another","Basic Harp",backend.listWindows().get(0),null),
                        "two characters cannot bind to the same window");
                untilRosterSize(conductorInbox,1);
                conductor.send("ACTION",remoteClient.id(),"Lutist","SELECT",song.gamePath(),"1",song.sha256());
                until(conductorInbox,"EVENT",m -> m.at(2).contains("INPUT SENT: SELECT"));
                check(typed.stream().anyMatch(s -> s.contains("/conductor select /demo | 1")), "select-only dispatches bridge command");
                check(typed.stream().noneMatch(s -> s.contains("/play ")), "select-only must not start music");
                typed.clear();
                conductor.send("ACTION",remoteClient.id(),"Lutist","CUSTOM","/bow","","");
                until(conductorInbox,"EVENT",m -> m.at(2).contains("INPUT SENT: CUSTOM"));
                check(typed.stream().anyMatch(t -> t.contains("/bow")),
                        "custom command routes to registered remote character without shell execution");
                typed.clear();
                conductor.send("ACTION",remoteClient.id(),"Lutist","CUSTOM","/bow\n/quit","","");
                until(conductorInbox,"EVENT",m -> m.at(2).contains("FAILED"));
                check(typed.stream().noneMatch(t -> t.startsWith("DEMO")),
                        "invalid multi-command injection sends no game input");
                typed.clear();
                conductor.send("ACTION",remoteClient.id(),"Lutist","PREPARE",song.gamePath(),"1",song.sha256());
                until(conductorInbox,"EVENT",m -> m.at(2).contains("INPUT SENT: PREPARE"));
                check(typed.stream().anyMatch(s -> s.contains("/conductor select /demo | 1")), "bridge select dispatched");
                check(typed.stream().anyMatch(s -> s.contains("/play \"/demo\" 1 sync")), "sync playback dispatched");
                typed.clear();
                conductor.send("ACTION",remoteClient.id(),"Lutist","PREPARE",song.gamePath(),"1","0".repeat(64));
                until(conductorInbox,"EVENT",m -> m.at(2).contains("FAILED"));
                check(typed.stream().noneMatch(s -> s.contains("DEMO") && s.contains("/play")), "wrong file hash blocked typing");
                typed.clear();
                // FOLLOW must dispatch to one character on its own computer: a
                // targeted keyboard shortcut, then the game's /follow command.
                conductor.send("ACTION",remoteClient.id(),"Lutist","FOLLOW","Ctrl+TAB","","");
                until(conductorInbox,"EVENT",m -> m.at(2).contains("INPUT SENT: FOLLOW"));
                List<String> followInput=typed.stream().filter(t->t.startsWith("DEMO")).toList();
                check(followInput.size()==2 && followInput.get(0).contains("quickslot Ctrl+TAB") &&
                        followInput.get(1).contains("/follow"),
                        "follow executes fellow-selection key before /follow in bound game window");
                typed.clear();
                conductor.send("ACTION",remoteClient.id(),"Lutist","FOLLOW","Ctrl+;","","");
                until(conductorInbox,"EVENT",m -> m.at(2).contains("FAILED"));
                check(typed.stream().noneMatch(t -> t.startsWith("DEMO")),
                        "invalid follow shortcut cannot inject keys or chat");
                typed.clear();
                conductor.send("ACTION",remoteClient.id(),"Lutist","MUSIC","","","");
                until(conductorInbox,"EVENT",m -> m.at(2).contains("INPUT SENT: MUSIC"));
                check(typed.stream().filter(t->t.startsWith("DEMO")).toList().size()==1 &&
                        typed.stream().anyMatch(t->t.startsWith("DEMO") && t.contains("/music")),
                        "music toggle dispatches immediately without checking bridge mode");
                typed.clear();
                conductor.send("ACTION",remoteClient.id(),"Lutist","START","","","");
                until(conductorInbox,"EVENT",m -> m.at(2).contains("INPUT SENT: START"));
                check(typed.stream().filter(t->t.startsWith("DEMO")).toList().size()==1 &&
                        typed.stream().anyMatch(t->t.startsWith("DEMO") && t.contains("/playstart")),
                        "playstart dispatches immediately without waiting for readiness");
                typed.clear();
                Files.delete(status); // deliberately remove the stale/slow bridge file
                agent.equipAssignedInstruments(List.of(new LocalAgent.EquipRequirement("Lutist","Basic Harp")),
                        () -> true).get(5,TimeUnit.SECONDS);
                List<String> equipInput=typed.stream().filter(t->t.startsWith("DEMO")).toList();
                check(equipInput.size()==1 && equipInput.get(0).contains("quickslot Ctrl+="),
                        "local setlist equip sends one mapped key despite missing bridge and already held instrument");
                check(equipInput.stream().noneMatch(t->t.contains("/music")),
                        "local setlist equip never guesses music mode or toggles it");
            }
        } finally { host.close(); deleteTree(dir); }
    }
    /** Consume streamed snapshots until the requested roster size is reached. */
    private static int untilRosterSize(BlockingQueue<Wire.Message> queue, int size) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        int expected = -1, count = 0;
        while (System.nanoTime() < deadline) {
            Wire.Message m = queue.poll(300, TimeUnit.MILLISECONDS);
            if (m == null) continue;
            switch (m.type()) {
                case "ROSTER_BEGIN" -> { expected = Integer.parseInt(m.at(0)); count = 0; }
                case "ROSTER_ITEM" -> { if (expected >= 0) { m.size(6); count++; } }
                case "ROSTER_END" -> {
                    if (expected == size && count == size) return count;
                    expected = -1;
                }
            }
        }
        throw new AssertionError("Timed out waiting for streamed roster of size " + size);
    }
    private static BandClient.Listener queueListener(BlockingQueue<Wire.Message> queue) {
        return new BandClient.Listener() {
            @Override public void received(Wire.Message msg) { queue.add(msg); }
            @Override public void disconnected(String why) { }
        };
    }
    private static Wire.Message until(BlockingQueue<Wire.Message> queue, String kind,
                                      java.util.function.Predicate<Wire.Message> predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            Wire.Message msg = queue.poll(300, TimeUnit.MILLISECONDS);
            if (msg != null && msg.type().equals(kind) && predicate.test(msg)) return msg;
        }
        throw new AssertionError("Timed out waiting for " + kind);
    }
    private static void deleteTree(Path path) throws IOException {
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
