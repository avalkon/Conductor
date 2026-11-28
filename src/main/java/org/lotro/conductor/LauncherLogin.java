package org.lotro.conductor;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Local-only orchestrator. Password input is direct Robot input, never clipboard,
 * subprocess arguments, properties, bridge reports, or LAN messages. */
final class LauncherLogin {
    private LauncherLogin() { }
    static final class Account implements AutoCloseable {
        final String name;
        private volatile char[] password;
        Account(String name, char[] password) {
            validate(name, password);
            this.name=name;
            this.password=password.clone();
        }
        static void validate(String name,char[] password) {
            if(name==null || name.isBlank() || name.length()>128 || !name.chars().allMatch(c->c>=32&&c<=126))
                throw new IllegalArgumentException("Use a non-empty printable ASCII account name (max 128 characters)");
            if(password==null || password.length==0 || password.length>256)
                throw new IllegalArgumentException("Enter a password (max 256 characters)");
            for(char c:password) if(c<32||c>126)
                throw new IllegalArgumentException("This login preview supports printable ASCII passwords on a US keyboard layout");
        }
        char[] password() {
            char[] p=password;
            if(p==null) throw new IllegalStateException("Password has been cleared");
            return p;
        }
        @Override public void close() {
            char[] p=password;password=null;
            if(p!=null) Arrays.fill(p,'\0');
        }
        @Override public String toString(){return "Login account (credentials hidden)";}
    }
    /** Delays are local per PC. Before another account, wait for game startup,
     * then require a refocus stability interval plus the actual login marker. */
    record Timing(int keyDelayMs,int afterLoginMs,int afterLaunchMs,int refocusMs,int timeoutMs) {
        static final Timing DEFAULT=new Timing(45,2000,6000,1500,60000);
        Timing {
            if(keyDelayMs<0||keyDelayMs>200 || afterLoginMs<0||afterLoginMs>120000 ||
                    afterLaunchMs<0||afterLaunchMs>120000 || refocusMs<250||refocusMs>15000 ||
                    timeoutMs<5000||timeoutMs>240000)
                throw new IllegalArgumentException("Login timing outside allowed range");
        }
    }
    interface Desktop {
        BufferedImage capture(Rectangle r) throws Exception;
        Point pointer();
        void click(Point p) throws Exception;
        void clearField() throws Exception;
        void type(char c) throws Exception;
        void enter() throws Exception;
        void releaseModifiers() throws Exception;
    }
    static Desktop robot(int delay) throws AWTException {
        Robot mouse=new Robot();
        mouse.setAutoDelay(60);
        KeyTyper.Keys keys=KeyTyper.robotKeys(delay);
        return new Desktop(){
            public BufferedImage capture(Rectangle r){return mouse.createScreenCapture(r);}
            public Point pointer(){return MouseInfo.getPointerInfo().getLocation();}
            public void click(Point p){
                mouse.mouseMove(p.x,p.y);
                try {mouse.mousePress(InputEvent.BUTTON1_DOWN_MASK);}
                finally{mouse.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);}
            }
            public void clearField() throws Exception {
                KeyTyper.shortcut(keys,Shortcut.parse("Ctrl+A"));
                KeyTyper.tap(keys,KeyEvent.VK_BACK_SPACE);
            }
            public void type(char c)throws Exception{KeyTyper.type(keys,c);}
            public void enter()throws Exception{KeyTyper.tap(keys,KeyEvent.VK_ENTER);}
            public void releaseModifiers(){
                keys.release(KeyEvent.VK_SHIFT);
                keys.release(KeyEvent.VK_CONTROL);
                keys.release(KeyEvent.VK_ALT);
            }
        };
    }
    /** Captured small screen patch of a page-specific static label. No credential
     * field pixels are required or persisted. Match tolerates slight rendering noise. */
    static final class Marker {
        final Rectangle area; // local coordinates inside selected window
        private final BufferedImage reference;
        Marker(Rectangle area,BufferedImage reference) {
            this.area=new Rectangle(area);
            this.reference=reference;
        }
        void write(java.io.DataOutputStream out)throws IOException{
            out.writeInt(area.x);out.writeInt(area.y);out.writeInt(area.width);out.writeInt(area.height);
            for(int y=0;y<area.height;y++)for(int x=0;x<area.width;x++)out.writeInt(reference.getRGB(x,y));
        }
        boolean matches(Desktop desktop,Rectangle bounds) throws Exception {
            Rectangle actual=new Rectangle(bounds.x+area.x,bounds.y+area.y,area.width,area.height);
            if(!bounds.contains(actual))return false;
            BufferedImage found=desktop.capture(actual);
            if(found.getWidth()!=reference.getWidth()||found.getHeight()!=reference.getHeight())return false;
            long close=0,ink=0,inkClose=0,total=(long)found.getWidth()*found.getHeight();
            for(int y=0;y<found.getHeight();y++)for(int x=0;x<found.getWidth();x++){
                int a=reference.getRGB(x,y),b=found.getRGB(x,y);
                boolean same=Math.abs((a>>16&255)-(b>>16&255))<=22 &&
                        Math.abs((a>>8&255)-(b>>8&255))<=22 &&
                        Math.abs((a&255)-(b&255))<=22;
                if(same)close++;
                if((a>>16&255)+(a>>8&255)+(a&255)>420){ink++;if(same)inkClose++;}
            }
            return close>=total*0.94 && inkClose>=ink*0.88;
        }
        static Marker capture(Desktop desktop,Rectangle bounds,Rectangle relative) throws Exception {
            if(relative.width<12||relative.height<10||!new Rectangle(0,0,bounds.width,bounds.height).contains(relative))
                throw new IOException("Marker must be inside the launcher");
            BufferedImage img=desktop.capture(new Rectangle(bounds.x+relative.x,bounds.y+relative.y,relative.width,relative.height));
            int min=765,max=0,ink=0;
            for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++){
                int rgb=img.getRGB(x,y);int v=(rgb>>16&255)+(rgb>>8&255)+(rgb&255);
                min=Math.min(min,v);max=Math.max(max,v); if(v>420)ink++;
            }
            // A blank dark background/solid white field is not a useful marker.
            if(max-min<130 || ink < 8 || ink >= img.getWidth()*img.getHeight()*0.92)
                throw new IOException("Choose a distinctive static text label, not an empty field or background");
            return new Marker(relative,img);
        }
    }
    record Layout(int width,int height,Point username,Point password,List<Marker> login,Marker world) {
        Layout {
            username=new Point(username);password=new Point(password);
            login=List.copyOf(login);
        }
        Layout withWorld(Marker marker){return new Layout(width,height,username,password,login,marker);}
        boolean sizeMatches(Rectangle bounds){return bounds.width==width&&bounds.height==height;}
        boolean loginMatches(Desktop desktop,Rectangle bounds)throws Exception{
            if(!sizeMatches(bounds))return false;
            for(Marker m:login)if(!m.matches(desktop,bounds))return false;
            return true;
        }
        boolean worldMatches(Desktop desktop,Rectangle bounds)throws Exception{
            return sizeMatches(bounds)&&world!=null&&world.matches(desktop,bounds);
        }
    }

    // Derived from the user's screenshot of the classic launcher login layout.
    // We use the text areas, not the username dropdown arrow or Log In button.
    static Layout captureClassicLogin(Desktop desktop,Rectangle b) throws Exception {
        double ratio=(double)b.width/b.height;
        if(ratio<1.13||ratio>1.55||b.width<500||b.height<375)
            throw new IOException("Launcher layout is not the expected classic form; resize or provide a matching launcher screenshot");
        Point account=relative(b,.67,.213), password=relative(b,.67,.292);
        if(!looksLikeField(desktop,b,account)||!looksLikeField(desktop,b,password))
            throw new IOException("Expected white username/password fields not found. Verify the launcher layout and screen scaling.");
        Marker username=Marker.capture(desktop,b,area(b,.526,.156,.116,.039));
        Marker pass=Marker.capture(desktop,b,area(b,.526,.235,.116,.039));
        return new Layout(b.width,b.height,account,password,List.of(username,pass),null);
    }
    private static boolean looksLikeField(Desktop d,Rectangle b,Point p)throws Exception{
        Rectangle box=new Rectangle(b.x+p.x-7,b.y+p.y-3,14,6);
        if(!b.contains(box))return false;
        BufferedImage img=d.capture(box);int bright=0;
        for(int y=0;y<img.getHeight();y++)for(int x=0;x<img.getWidth();x++){
            int rgb=img.getRGB(x,y);
            if((rgb>>16&255)>180&&(rgb>>8&255)>180&&(rgb&255)>180)bright++;
        }
        return bright>=img.getWidth()*img.getHeight()*0.6;
    }
    static Point relative(Rectangle b,double x,double y) {
        return new Point((int)Math.round(b.width*x),(int)Math.round(b.height*y));
    }
    static Rectangle area(Rectangle b,double x,double y,double w,double h){
        Point p=relative(b,x,y),size=relative(b,w,h);return new Rectangle(p.x,p.y,size.x,size.y);
    }
    static Marker captureWorld(Desktop desktop,Rectangle bounds,Layout layout)throws Exception {
        if(!layout.sizeMatches(bounds))throw new IOException("Launcher size changed; calibrate login again");
        if(layout.loginMatches(desktop,bounds))throw new IOException("Still on login page; wait for world selection");
        Point p=desktop.pointer();
        Rectangle rel=new Rectangle(p.x-bounds.x-45,p.y-bounds.y-12,90,24);
        return Marker.capture(desktop,bounds,rel);
    }

    interface Pause { void waitMs(long ms) throws Exception; }
    static final class Flow {
        private final LauncherPlatform platform;
        private final LauncherWindow window;
        private final Desktop desktop;
        private final Layout layout;
        private final Timing timing;
        private final BooleanSupplier cancelled;
        private final Consumer<String> status;
        private final Pause pause;
        Flow(LauncherPlatform platform,LauncherWindow window,Desktop desktop,Layout layout,Timing timing,
             BooleanSupplier cancelled,Consumer<String> status) {
            this(platform,window,desktop,layout,timing,cancelled,status,Thread::sleep);
        }
        Flow(LauncherPlatform platform,LauncherWindow window,Desktop desktop,Layout layout,Timing timing,
             BooleanSupplier cancelled,Consumer<String> status,Pause pause){
            this.platform=platform;this.window=window;this.desktop=desktop;this.layout=layout;
            this.timing=timing;this.cancelled=cancelled;this.status=status;this.pause=pause;
        }
        private void check() throws InterruptedException {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException("Login stopped");
        }
        void waitMs(long ms)throws Exception {
            for(long left=ms;left>0;){check();long step=Math.min(200,left);pause.waitMs(step);left-=step;}
            check();
        }
        private Rectangle verified(boolean login) throws Exception {
            check();
            Rectangle b=platform.foreground(window);
            if(!layout.sizeMatches(b))throw new IOException("Launcher size changed; calibrate again");
            boolean ok=login?layout.loginMatches(desktop,b):layout.worldMatches(desktop,b);
            boolean ambiguous=login?layout.worldMatches(desktop,b):layout.loginMatches(desktop,b);
            if(!ok||ambiguous)throw new IOException("Expected "+(login?"login":"world selection")+" page is not verified; stopped");
            check();
            return b;
        }
        /** New game windows can activate twice. Reacquire only the exact same
         * launcher and verify a stable foreground/page after a configurable gap.
         * Nothing is typed while it is absent, obscured, or on the wrong page. */
        void awaitPage(boolean login,boolean refocus) throws Exception {
            String page=login?"login page":"world selection";
            int maxAttempts=Math.max(1,timing.timeoutMs()/Math.max(250,timing.refocusMs()));
            long deadline=System.nanoTime()+(long)timing.timeoutMs()*1_000_000;
            for(int attempt=0;attempt<maxAttempts&&System.nanoTime()<deadline;attempt++) {
                check();
                try {
                    if(refocus)platform.focus(window);
                    verified(login);
                    waitMs(timing.refocusMs());
                    // A launched game may activate more than once. Re-activate
                    // the exact launcher a second time on login pages, then
                    // verify it remains foreground for the full settle interval.
                    if(login&&refocus){platform.focus(window);waitMs(timing.refocusMs());}
                    verified(login);
                    return;
                } catch(InterruptedException e){throw e;}
                catch(Exception e){
                    status.accept("Waiting for verified "+page+" (attempt "+(attempt+1)+")");
                    waitMs(timing.refocusMs());
                }
            }
            throw new IOException("Timed out waiting for verified "+page+"; inspect the launcher");
        }
        private void clickAndClear(Point relative) throws Exception {
            Rectangle b=verified(true);
            Point point=new Point(b.x+relative.x,b.y+relative.y);
            if(!b.contains(point))throw new IOException("Field is outside launcher");
            desktop.click(point);
            waitMs(100);
            verified(true);
            desktop.clearField();
            waitMs(100);
            Rectangle cleared=verified(true);
            if(!looksLikeField(desktop,cleared,relative))
                throw new IOException("Cleared field does not look like the calibrated login input; stopped");
        }
        private void type(char[] chars,LauncherPlatform.Guard guard) throws Exception {
            // Verify the exact foreground before every credential character. On
            // Windows this costs time; it deliberately favors privacy/reliability.
            // No password-dependent data is passed into the guard process.
            for(char c:chars){
                check();
                guard.verify();
                check();
                desktop.type(c);
            }
        }
        void firstEnter(Account account)throws Exception {
            awaitPage(true,true);
            try(LauncherPlatform.Guard guard=platform.guard(window)){
                verified(true);
                desktop.releaseModifiers();
                clickAndClear(layout.username());
                char[] name=account.name.toCharArray();
                try{type(name,guard);}finally{Arrays.fill(name,'\0');}
                verified(true);
                clickAndClear(layout.password());
                type(account.password(),guard);
                verified(true);
                guard.verify();check();
                desktop.enter();
            }
            status.accept("First Enter sent; login result is not confirmed");
        }
        void secondEnter()throws Exception {
            awaitPage(false,true);
            verified(false);
            desktop.enter();
            status.accept("Second Enter sent; game launch is not confirmed");
        }
        void run(List<Account> accounts)throws Exception {
            if(layout.world()==null)throw new IOException("Calibrate world-selection marker before running a batch");
            if(accounts.isEmpty())throw new IOException("Add at least one account");
            for(int i=0;i<accounts.size();i++){
                check();
                status.accept("Signing in account "+(i+1)+" of "+accounts.size());
                firstEnter(accounts.get(i));
                waitMs(timing.afterLoginMs());
                secondEnter();
                // Game startup may pull foreground twice. Let it settle before
                // the next iteration; firstEnter() then explicitly refocuses.
                if(i+1<accounts.size())waitMs(timing.afterLaunchMs());
            }
            status.accept("Login sequence sent for "+accounts.size()+" account(s); check game windows");
        }
    }
}
