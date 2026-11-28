package org.lotro.conductor;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Local launcher workflow. The only outbound automation is queued local
 * desktop input, never a BandServer/BandClient message. */
final class LoginPanel extends JPanel implements AutoCloseable {
    private final LocalAgent agent;
    private final boolean live;
    private final LauncherPlatform platform;
    private final Consumer<String> logger;
    private final Runnable invalidateRefocus;
    private final Predicate<String> confirmation;
    private final Consumer<LauncherWindow> launcherSelected;
    private final LauncherPreferences prefs;
    private final LauncherVault vault;
    private final LauncherLayoutStore layouts;
    private final JComboBox<LauncherWindow> windows=new JComboBox<>();
    private final JTextField script=new JTextField();
    private final JTextField accountName=new JTextField();
    private final JPasswordField password=new JPasswordField();
    private final JLabel state=new JLabel("Open or start the launcher, then refresh.");
    private final JLabel calibration=new JLabel("Login page: not calibrated • World page: not calibrated");
    private final JLabel vaultState=new JLabel();
    private final AccountModel model=new AccountModel();
    private final JTable table=new JTable(model);
    private final JButton stop=new JButton("Stop login");
    private final List<JComponent> busyControls=new ArrayList<>();
    private final List<JComponent> liveControls=new ArrayList<>();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private JSpinner keyDelay,loginDelay,launchDelay,refocusDelay,timeout;
    private LauncherWindow calibratedWindow, authorizedWindow, designatedWindow;
    private boolean updatingWindows;
    private LauncherLogin.Layout layout,savedLayout;
    private volatile boolean closed;
    private boolean busy;
    private List<LauncherLogin.Account> pending=List.of();

    private static final class Row {
        String name; boolean checked;
        Row(String name,boolean checked){this.name=name;this.checked=checked;}
    }
    private final class AccountModel extends AbstractTableModel {
        final List<Row> rows=new ArrayList<>();
        @Override public int getRowCount(){return rows.size();}
        @Override public int getColumnCount(){return 3;}
        @Override public String getColumnName(int col){return new String[]{"Login","Account","Password"}[col];}
        @Override public Class<?> getColumnClass(int col){return col==0?Boolean.class:String.class;}
        @Override public Object getValueAt(int row,int col){
            Row r=rows.get(row);
            return switch(col){
                case 0->r.checked;
                case 1->r.name;
                default->!vault.unlocked()?"Locked":vault.has(r.name)?"Saved (encrypted)":"Not saved";
            };
        }
        @Override public boolean isCellEditable(int row,int col){return !busy&&col==0;}
        @Override public void setValueAt(Object v,int row,int col){
            if(col==0&&!busy){rows.get(row).checked=Boolean.TRUE.equals(v);savePrefs();fireTableCellUpdated(row,col);}
        }
    }

    LoginPanel(LocalAgent agent,boolean live,Path settingsDirectory,Consumer<String> logger,Runnable invalidateRefocus,Predicate<String> confirmation,Consumer<LauncherWindow> launcherSelected) {
        super(new BorderLayout(5,5));
        this.agent=agent;this.live=live;this.logger=logger;this.invalidateRefocus=invalidateRefocus;this.confirmation=Objects.requireNonNull(confirmation);
        this.launcherSelected=Objects.requireNonNull(launcherSelected);
        this.platform=LauncherPlatform.desktop();
        try{prefs=new LauncherPreferences(settingsDirectory.resolve("launcher.properties"));}
        catch(Exception e){throw new IllegalStateException("Cannot load launcher preferences",e);}
        vault=new LauncherVault(settingsDirectory.resolve("launcher.vault"));
        layouts=new LauncherLayoutStore(settingsDirectory.resolve("launcher-layout.bin"));
        try{savedLayout=layouts.load();layout=savedLayout;}
        catch(Exception e){logger.accept("LOGIN: Saved calibration unreadable; recalibrate launcher");}
        for(String name:prefs.accounts)model.rows.add(new Row(name,prefs.checked.contains(name)));
        script.setText(prefs.script);
        JPanel body=new ScrollBody();
        body.setBorder(BorderFactory.createEmptyBorder(6,10,6,10));
        body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));
        body.add(note("Local account sign-ins. Confirmation prompts follow Settings. Select accounts, unlock the vault, and use the verified launcher. This starts game clients; it does not choose the in-game character."));
        JPanel launch=section("1. Launcher");
        launch.add(new JLabel("Launcher script / executable (optional)"));
        JPanel path=new JPanel(new BorderLayout(4,0));path.add(script,BorderLayout.CENTER);
        path.add(button("Browse",()->{
            JFileChooser chooser=new JFileChooser();
            if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){
                script.setText(chooser.getSelectedFile().getAbsolutePath());savePrefs();
            }
        },false),BorderLayout.EAST);
        launch.add(path);
        busyControls.add(script);
        JPanel launchButtons=line();
        launchButtons.add(button("Start launcher",this::startLauncher,true));
        launchButtons.add(button("Refresh LOTRO windows",this::refresh,true));
        launch.add(launchButtons);
        windows.setPrototypeDisplayValue(new LauncherWindow("0x123456",0,"The Lord of the Rings Online Launcher",""));
        windows.setMaximumSize(new Dimension(Integer.MAX_VALUE,30));
        windows.addActionListener(e->{
            if(updatingWindows)return;
            if(!same(selectedWindow(),calibratedWindow)){
                calibratedWindow=selectedWindow();layout=savedLayout;
                if(!same(selectedWindow(),authorizedWindow))authorizedWindow=null;
            }
            designate(selectedWindow());
            updateCalibration();
        });
        launch.add(windows);busyControls.add(windows);liveControls.add(windows);
        JPanel choose=line();
        choose.add(button("Pick active window",this::pickActive,true));
        choose.add(button("Focus / verify selected",this::verifySelected,true));
        choose.add(button("Clear launcher",this::clearSelection,true));
        launch.add(choose);
        launch.add(note("Select the launcher by ID or Pick active window (switch within 4 seconds). Your chosen launcher is excluded from Computer. Clear launcher to undo the selection. Verify or calibrate before entering passwords."));
        body.add(launch);
        JPanel accounts=section("2. Saved accounts");
        JPanel vaultButtons=line();
        vaultButtons.add(vaultState);
        vaultButtons.add(button("Create / unlock vault",this::unlock,false));
        vaultButtons.add(button("Lock vault",()->{vault.close();password.setText("");updateVault();},false));
        accounts.add(vaultButtons);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(62);
        table.getColumnModel().getColumn(0).setPreferredWidth(54);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(125);
        table.getSelectionModel().addListSelectionListener(e->{
            if(!e.getValueIsAdjusting()){
                int i=table.getSelectedRow();
                if(i>=0&&i<model.rows.size()){accountName.setText(model.rows.get(i).name);password.setText("");}
            }
        });
        JScrollPane scroll=new JScrollPane(table);scroll.setColumnHeaderView(table.getTableHeader());scroll.setPreferredSize(new Dimension(380,128));scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE,150));accounts.add(scroll);
        busyControls.add(table);
        JPanel editor=new JPanel(new GridLayout(2,2,4,2));
        editor.add(new JLabel("Account / username"));editor.add(accountName);
        editor.add(new JLabel("Game password"));editor.add(password);
        accounts.add(editor);busyControls.add(accountName);busyControls.add(password);
        JPanel editButtons=line();
        editButtons.add(button("Save account",this::saveAccount,false));
        editButtons.add(button("Remove selected",this::removeSelected,false));
        editButtons.add(button("Check all",()->checkAll(true),false));
        editButtons.add(button("Uncheck all",()->checkAll(false),false));
        accounts.add(editButtons);
        accounts.add(note("Account names and checkboxes are saved locally. Game passwords are encrypted in launcher.vault. Unlock once per session; master password minimum 6 characters."));
        body.add(accounts);
        JPanel setup=section("3. Calibrate this launcher");
        setup.add(note("First use: show the normal username/password screen and calibrate. Test one saved account (first Enter), wait for world selection, click Capture world-page marker and hover over a distinctive static label within 4 seconds (do not click), then send the second Enter."));
        JPanel calibrationButtons=line();
        calibrationButtons.add(button("Calibrate login page",this::calibrateLogin,true));
        calibrationButtons.add(button("Test first Enter",this::testFirst,true));
        setup.add(calibrationButtons);
        JPanel second=line();
        second.add(button("Capture world-page marker",this::calibrateWorld,true));
        second.add(button("Send second Enter",this::testSecond,true));
        setup.add(second);
        setup.add(calibration);
        body.add(setup);
        JPanel pacing=section("4. Login timing (milliseconds, this computer)");
        JPanel times=new JPanel(new GridLayout(5,2,6,2));
        LauncherLogin.Timing t=prefs.timing;
        keyDelay=spinner(times,"Between typed keys",t.keyDelayMs(),0,200,5);
        loginDelay=spinner(times,"After first Enter",t.afterLoginMs(),0,120000,500);
        launchDelay=spinner(times,"After second Enter / game startup",t.afterLaunchMs(),0,120000,500);
        refocusDelay=spinner(times,"Launcher refocus delay",t.refocusMs(),250,15000,250);
        timeout=spinner(times,"Page wait timeout",t.timeoutMs(),5000,240000,1000);
        pacing.add(times);body.add(pacing);
        JPanel run=section("Batch login");
        run.add(note("Keep hands off the keyboard/mouse. After each game starts, Conductor reactivates the same launcher twice and waits for the verified login page. Unexpected pages, focus changes or timeouts stop input."));
        body.add(run);
        JScrollPane page=new JScrollPane(body);page.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        page.getVerticalScrollBar().setUnitIncrement(16);
        add(page,BorderLayout.CENTER);
        // Persistent controls are visible even when setup/timing is scrolled away.
        JPanel footer=new JPanel(new BorderLayout(2,2));footer.setBorder(BorderFactory.createEmptyBorder(1,8,4,8));
        JPanel actions=line();
        actions.add(button("Login checked accounts",this::batch,true));
        stop.setEnabled(false);stop.addActionListener(e->{cancelled.set(true);status("Stopping after current input operation…");});actions.add(stop);
        footer.add(actions,BorderLayout.NORTH);
        state.setBorder(BorderFactory.createEmptyBorder(1,4,2,4));footer.add(state,BorderLayout.SOUTH);
        add(footer,BorderLayout.SOUTH);
        updateVault();updateCalibration();setBusy(false);
        if(!live)status("Dry-run/demo: launcher input and script execution are disabled.");
    }
    private static class ScrollBody extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
        public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 16;}
        public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return Math.max(16,r.height-16);}
        public boolean getScrollableTracksViewportWidth(){return true;}
        public boolean getScrollableTracksViewportHeight(){return false;}
    }
    private static JPanel section(String title){
        JPanel panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(title));panel.setAlignmentX(Component.LEFT_ALIGNMENT);return panel;
    }
    private static JPanel line(){return new JPanel(new FlowLayout(FlowLayout.LEFT,4,2));}
    private static JTextArea note(String text){
        JTextArea area=new JTextArea(text);area.setWrapStyleWord(true);area.setLineWrap(true);area.setEditable(false);
        area.setColumns(35);area.setRows(Math.max(2,(text.length()+71)/72));area.setFocusable(false);area.setOpaque(false);area.setFont(UIManager.getFont("Label.font").deriveFont(11f));
        return area;
    }
    private JButton button(String label,Runnable task,boolean liveOnly){
        JButton b=new JButton(label);b.setMargin(new Insets(3,6,3,6));b.addActionListener(e->{
            if(!busy&&(!liveOnly||live))task.run();
        });
        busyControls.add(b);if(liveOnly)liveControls.add(b);return b;
    }
    private JSpinner spinner(JPanel p,String label,int value,int min,int max,int step){
        JSpinner s=new JSpinner(new SpinnerNumberModel(value,min,max,step));
        p.add(new JLabel(label));p.add(s);busyControls.add(s);s.addChangeListener(e->{if(keyDelay!=null&&timeout!=null)savePrefs();});return s;
    }
    private void updateVault(){
        vaultState.setText(vault.unlocked()?"Vault unlocked":vault.exists()?"Vault locked":"No vault yet");
        model.fireTableDataChanged();
    }
    private void updateCalibration(){
        calibration.setText((selectedWindow()!=null&&!selectedWindow().recognized()&&!same(selectedWindow(),authorizedWindow))?
                (same(selectedWindow(),designatedWindow)?"Launcher excluded: verify or calibrate first":"Preview candidate: select or verify to exclude"):layout==null?"Login page: not calibrated • World page: not calibrated":
                "Login page: calibrated • World page: "+(layout.world()!=null?"calibrated":"not calibrated"));
    }
    private void status(String message){
        SwingUtilities.invokeLater(()->{if(!closed){state.setText(message);state.setToolTipText(message);}});
        logger.accept("LOGIN: "+message);
    }
    private void error(String message){JOptionPane.showMessageDialog(this,message,"Launcher login",JOptionPane.WARNING_MESSAGE);}
    private boolean confirm(String message){return confirmation.test(message);}
    private void setBusy(boolean b){
        busy=b;
        for(JComponent c:busyControls)c.setEnabled(!b&&(!liveControls.contains(c)||live));
        stop.setEnabled(b&&live);
    }
    private LauncherLogin.Timing timing(){
        return new LauncherLogin.Timing((Integer)keyDelay.getValue(),(Integer)loginDelay.getValue(),(Integer)launchDelay.getValue(),
                (Integer)refocusDelay.getValue(),(Integer)timeout.getValue());
    }
    private void savePrefs(){
        try{
            prefs.script=script.getText().strip();
            prefs.accounts=model.rows.stream().map(r->r.name).toList();
            Set<String> checked=new HashSet<>();for(Row r:model.rows)if(r.checked)checked.add(r.name);
            prefs.checked=Set.copyOf(checked);
            if(timeout!=null)prefs.timing=timing();prefs.save();
        }catch(Exception ex){error("Cannot save local launcher preferences: "+ex.getMessage());}
    }
    private void unlock(){
        if(vault.unlocked()){status("Vault is already unlocked");return;}
        boolean create=!vault.exists();
        JPasswordField master=new JPasswordField(16),repeat=new JPasswordField(16);
        JPanel form=new JPanel(new GridLayout(create?2:1,2,4,4));
        form.add(new JLabel(create?"New master password":"Master password"));form.add(master);
        if(create){form.add(new JLabel("Repeat (min. 6 characters)"));form.add(repeat);}
        if(JOptionPane.showConfirmDialog(this,form,create?"Create encrypted login vault":"Unlock login vault",
                JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION){master.setText("");repeat.setText("");return;}
        char[] key=master.getPassword(),again=repeat.getPassword();
        master.setText("");repeat.setText("");
        try{
            if(create&&!Arrays.equals(key,again))throw new IllegalArgumentException("Master passwords do not match");
            if(create)vault.create(key);else vault.unlock(key);
            // Recover saved accounts that were written to the vault before a
            // metadata write was interrupted.
            Set<String> present=new HashSet<>();for(Row r:model.rows)present.add(r.name);
            for(String name:vault.names())if(present.add(name))model.rows.add(new Row(name,false));
            savePrefs();updateVault();status("Local vault unlocked");
        }catch(Exception ex){error(ex.getMessage());}
        finally{Arrays.fill(key,'\0');Arrays.fill(again,'\0');}
    }
    private void saveAccount(){
        if(!vault.unlocked()){error("Create or unlock the local vault first");return;}
        String name=accountName.getText().strip();
        char[] secret=password.getPassword();
        try{
            if(secret.length==0){if(!vault.has(name))throw new IllegalArgumentException("Enter the game password");}
            else vault.put(name,secret);
            if(model.rows.stream().noneMatch(r->r.name.equals(name)))model.rows.add(new Row(name,true));
            savePrefs();model.fireTableDataChanged();password.setText("");status("Account saved in local encrypted vault");
        }catch(Exception ex){error(ex.getMessage());}
        finally{Arrays.fill(secret,'\0');password.setText("");}
    }
    private void removeSelected(){
        int row=table.getSelectedRow();if(row<0){error("Select an account row");return;}
        if(!vault.unlocked()){error("Unlock the vault before removing an account");return;}
        if(!confirm("Remove the selected account and its saved password?"))return;
        try{vault.remove(model.rows.get(row).name);model.rows.remove(row);savePrefs();model.fireTableDataChanged();accountName.setText("");password.setText("");}
        catch(Exception ex){error(ex.getMessage());}
    }
    private void checkAll(boolean value){for(Row r:model.rows)r.checked=value;savePrefs();model.fireTableDataChanged();}
    private LauncherWindow selectedWindow(){return (LauncherWindow)windows.getSelectedItem();}
    private static boolean same(LauncherWindow a,LauncherWindow b){
        return a!=null&&a.sameInstance(b);
    }
    private LauncherWindow requireWindow(){
        LauncherWindow w=selectedWindow();if(w==null)throw new IllegalStateException("Refresh and select a LOTRO launcher window first");return w;
    }
    private LauncherLogin.Layout requireLayout(boolean world){
        if(layout==null||!same(selectedWindow(),calibratedWindow))throw new IllegalStateException("Calibrate the login page for the selected launcher first");
        if(!selectedWindow().recognized()&&!same(selectedWindow(),authorizedWindow))throw new IllegalStateException("Use Focus / verify selected or Calibrate login page to verify this launcher candidate first");
        if(world&&layout.world()==null)throw new IllegalStateException("Capture the world-page marker first");
        return layout;
    }

    @FunctionalInterface private interface Job{void run()throws Exception;}
    private void queue(Job task){
        if(!live||busy||closed)return;
        cancelled.set(false);setBusy(true);invalidateRefocus.run();
        try{
            agent.afterLocalInput(()->{
                try{if(cancelled.get()||closed)throw new InterruptedException();task.run();}
                catch(InterruptedException e){status("Login operation stopped");}
                catch(Exception ex){status("Stopped: "+ex.getMessage());}
                finally{
                    // Snapshots never survive a cancelled or completed task.
                    pending.forEach(LauncherLogin.Account::close);
                    SwingUtilities.invokeLater(()->{
                        pending=List.of();
                        if(!closed){setBusy(false);updateCalibration();}
                    });
                }
            });
        }catch(RuntimeException e){
            pending.forEach(LauncherLogin.Account::close);pending=List.of();setBusy(false);error("Local input queue is unavailable");
        }
    }
    private void waitCancelled(long ms)throws InterruptedException{
        for(long remaining=ms;remaining>0;){if(cancelled.get()||closed)throw new InterruptedException();
            long step=Math.min(remaining,200);Thread.sleep(step);remaining-=step;}
        if(cancelled.get()||closed)throw new InterruptedException();
    }
    private void designate(LauncherWindow target){
        if(same(target,designatedWindow)||(target==null&&designatedWindow==null))return;
        designatedWindow=target;launcherSelected.accept(target);
    }
    private void clearSelection(){
        updatingWindows=true;
        try{windows.setSelectedItem(null);}finally{updatingWindows=false;}
        calibratedWindow=null;authorizedWindow=null;layout=savedLayout;
        designate(null);updateCalibration();status("Launcher selection cleared");
    }
    /** Populate without accidentally designating each transient combo-box item.
     * Passive discovery may preview a same-title game; do not exclude it until
     * chosen explicitly, verified, or opened by Start launcher. */
    private void setWindows(List<LauncherWindow> found,LauncherWindow selected,boolean explicit){
        SwingUtilities.invokeLater(()->{
            if(closed)return;
            LauncherWindow previousAuthorization=authorizedWindow;
            LauncherWindow choice=found.stream().filter(w->same(w,selected)).findFirst()
                    .orElse(found.isEmpty()?null:found.get(0));
            updatingWindows=true;
            try{
                windows.removeAllItems();for(LauncherWindow w:found)windows.addItem(w);
                windows.setSelectedItem(choice);
            }finally{updatingWindows=false;}
            calibratedWindow=choice;layout=savedLayout;
            authorizedWindow=same(choice,previousAuthorization)?choice:null;
            designate(choice!=null&&(explicit||choice.recognized()||same(choice,designatedWindow))?choice:null);
            updateCalibration();
        });
    }
    private void refresh(){
        LauncherWindow selection=selectedWindow();
        boolean verified=same(selection,authorizedWindow)||same(selection,designatedWindow);
        List<LauncherWindow> before=new ArrayList<>();
        for(int i=0;i<windows.getItemCount();i++)before.add(windows.getItemAt(i));
        queue(()->{
            List<LauncherWindow> found=platform.list(),fresh=LauncherWindow.newSince(before,found);
            LauncherWindow preferred=!verified&&!fresh.isEmpty()?fresh.get(0):selection;
            setWindows(found,preferred,false);
            status(found.isEmpty()?"No LOTRO windows found; open the launcher and refresh.":"Found "+found.size()+" LOTRO window(s). Choose the launcher; candidates need verification.");});
    }
    private void startLauncher(){
        String raw=script.getText().strip();
        if(raw.isBlank()){error("Choose the launcher script or executable first");return;}
        if(!confirm("Run this local launcher script / executable?\n"+raw))return;
        savePrefs();
        LauncherWindow selection=selectedWindow();
        queue(()->{
            List<LauncherWindow> before=platform.list(),found=before;
            platform.start(Path.of(raw));status("Launcher script started; waiting for a new LOTRO window…");
            for(int i=0;i<30;i++){
                waitCancelled(1000);found=platform.list();
                List<LauncherWindow> fresh=LauncherWindow.newSince(before,found);
                if(!fresh.isEmpty()){
                    setWindows(found,fresh.get(0),true);
                    status("New LOTRO window selected. Verify the launcher login page before using it.");return;
                }
            }
            setWindows(found,selection,false);
            status("No new window yet. Choose the launcher manually or refresh once it opens.");
        });
    }
    private void pickActive(){
        if(!confirm("When the countdown starts, switch to the actual LOTRO launcher within 4 seconds. Leave its login screen visible."))return;
        queue(()->{
            status("Switch to the LOTRO launcher now (4 seconds)…");waitCancelled(4000);
            String id=platform.activeWindowId();
            List<LauncherWindow> found=platform.list();
            LauncherWindow picked=found.stream().filter(w->w.id().equalsIgnoreCase(id)).findFirst()
                    .orElseThrow(()->new IllegalStateException("Active window is not a listed LOTRO window; try again"));
            setWindows(found,picked,true);
            status("Selected "+picked.id()+". Verify or calibrate the login page before login.");
        });
    }
    private void verifySelected(){
        try{
            LauncherWindow w=requireWindow();
            LauncherLogin.Layout current=savedLayout;
            if(!confirm("Focus "+w+"? Make sure this is the actual launcher showing the login screen. No account information will be typed."))return;
            queue(()->{
                platform.focus(w);waitCancelled(350);
                if(current==null){status("Selected window focused. If it is the launcher, return here and calibrate the login page.");return;}
                var desktop=LauncherLogin.robot(0);
                Rectangle b=platform.foreground(w);
                if(!current.loginMatches(desktop,b)||current.worldMatches(desktop,b))
                    throw new IllegalStateException("Selected window does not match the saved login page. Choose the launcher or recalibrate.");
                platform.foreground(w);
                SwingUtilities.invokeLater(()->{if(!closed){authorizedWindow=w;calibratedWindow=w;layout=current;designate(w);updateCalibration();}});
                status("Selected launcher login page verified. Ready to use saved calibration.");
            });
        }catch(Exception ex){error(ex.getMessage());}
    }
    private void calibrateLogin(){
        try{
            LauncherWindow w=requireWindow();
            if(!confirm("Ensure the selected launcher is showing the normal Username and Password screen, with the classic layout. Close pop-ups. Conductor will capture only two small label regions and use the shown text field positions. Continue?"))return;
            queue(()->{
                Rectangle b=platform.focus(w);waitCancelled(350);
                LauncherLogin.Layout captured=LauncherLogin.captureClassicLogin(LauncherLogin.robot(0),platform.foreground(w));
                layouts.save(captured);
                SwingUtilities.invokeLater(()->{if(!closed){authorizedWindow=w;calibratedWindow=w;savedLayout=captured;layout=captured;designate(w);updateCalibration();}});
                status("Login page calibrated. Test one account before running a batch.");
            });
        }catch(Exception ex){error(ex.getMessage());}
    }
    private void calibrateWorld(){
        try{
            LauncherWindow w=requireWindow();LauncherLogin.Layout current=requireLayout(false);
            if(!confirm("Show the world/server selection screen (the screen where Enter starts the game). When the countdown starts, you have 4 seconds to hover the mouse over a distinctive static label on that screen. Do not click or choose an account/character name. Continue?"))return;
            queue(()->{
                platform.focus(w);status("Hover over the world-selection label now (4 seconds)");waitCancelled(4000);
                var desktop=LauncherLogin.robot(0);
                var marker=LauncherLogin.captureWorld(desktop,platform.foreground(w),current);
                LauncherLogin.Layout updated=current.withWorld(marker);
                layouts.save(updated);
                SwingUtilities.invokeLater(()->{if(!closed){savedLayout=updated;layout=updated;updateCalibration();}});
                status("World-selection marker captured");
            });
        }catch(Exception ex){error(ex.getMessage());}
    }
    private LauncherLogin.Account snapshot(Row row){
        char[] pwd=vault.copyPassword(row.name);
        try{return new LauncherLogin.Account(row.name,pwd);}finally{Arrays.fill(pwd,'\0');}
    }
    private LauncherLogin.Flow flow(LauncherWindow w,LauncherLogin.Layout l,LauncherLogin.Timing t)throws Exception{
        return new LauncherLogin.Flow(platform,w,LauncherLogin.robot(t.keyDelayMs()),l,t,()->cancelled.get()||closed,this::status);
    }
    private void testFirst(){
        try{
            LauncherWindow w=requireWindow();LauncherLogin.Layout l=requireLayout(false);LauncherLogin.Timing t=timing();
            int i=table.getSelectedRow();if(i<0)throw new IllegalStateException("Select one saved account row to test");
            if(!vault.unlocked())throw new IllegalStateException("Unlock the vault first");
            if(!confirm("Send the selected account's login (first Enter only) to the calibrated launcher? Keep hands off the keyboard/mouse."))return;
            pending=List.of(snapshot(model.rows.get(i)));
            List<LauncherLogin.Account> accounts=pending;
            queue(()->{flow(w,l,t).firstEnter(accounts.get(0));status("First Enter sent. Wait for world selection, then capture its marker.");});
        }catch(Exception ex){error(ex.getMessage());}
    }
    private void testSecond(){
        try{
            LauncherWindow w=requireWindow();LauncherLogin.Layout l=requireLayout(true);LauncherLogin.Timing t=timing();
            if(!confirm("Send the second Enter on the verified world-selection page to launch the game?"))return;
            queue(()->flow(w,l,t).secondEnter());
        }catch(Exception ex){error(ex.getMessage());}
    }
    private void batch(){
        List<LauncherLogin.Account> accounts=new ArrayList<>();
        try{
            LauncherWindow w=requireWindow();LauncherLogin.Layout l=requireLayout(true);LauncherLogin.Timing t=timing();
            if(!vault.unlocked())throw new IllegalStateException("Unlock the vault first");
            List<Row> selected=model.rows.stream().filter(r->r.checked).toList();
            if(selected.isEmpty())throw new IllegalStateException("Check the accounts to login");
            if(selected.stream().anyMatch(r->!vault.has(r.name)))throw new IllegalStateException("A checked account is missing a saved password");
            if(!confirm("Login "+selected.size()+" checked account(s) in table order? The launcher will be refocused between launches. Leave the desktop alone; Stop will cancel remaining input."))return;
            for(Row row:selected)accounts.add(snapshot(row));pending=List.copyOf(accounts);
            savePrefs();queue(()->flow(w,l,t).run(accounts));
        }catch(Exception ex){accounts.forEach(LauncherLogin.Account::close);pending=List.of();error(ex.getMessage());}
    }
    @Override public void close(){
        closed=true;cancelled.set(true);pending.forEach(LauncherLogin.Account::close);
        vault.close();password.setText("");
    }
}
