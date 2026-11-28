package org.lotro.conductor;

import java.awt.Rectangle;
import java.io.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Separate, local-only launcher window discovery and verified foreground access. */
interface LauncherPlatform {
    List<LauncherWindow> list() throws Exception;
    Rectangle focus(LauncherWindow window) throws Exception;
    Rectangle foreground(LauncherWindow window) throws Exception;
    void start(Path script) throws Exception;
    default String activeWindowId() throws Exception { throw new IOException("Active window detection unavailable"); }
    static boolean lotroTitle(String title) {
        String lower=title.strip().toLowerCase(Locale.ROOT);
        return lower.contains("lord of the rings online")||lower.matches("^lotro(?:\\b|launcher|client).*");
    }
    interface Guard extends AutoCloseable {
        void verify() throws Exception;
        @Override default void close() { }
    }
    default Guard guard(LauncherWindow window) throws Exception {
        foreground(window);
        return () -> foreground(window);
    }

    static LauncherPlatform desktop() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows") ? new Windows() : new X11();
    }

    static boolean launcherExecutable(String name) {
        if (name == null) return false;
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return Set.of("lotrolauncher","lotrolauncher.exe","turbinelauncher","turbinelauncher.exe").contains(base);
    }
    static boolean launcherClass(String property) {
        for (String line : property.split("\\R")) {
            if (!line.startsWith("WM_CLASS(")) continue;
            Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(line);
            while (m.find()) if (launcherExecutable(m.group(1))) return true;
        }
        return false;
    }
    static boolean launcherProcess(long pid) {
        if(pid<=0)return false;
        return ProcessHandle.of(pid).map(p -> {
            var info = p.info();
            String command=info.command().orElse("");
            if (launcherExecutable(command)) return true;
            String base=command.replace('\\','/').toLowerCase(Locale.ROOT);
            base=base.substring(base.lastIndexOf('/')+1);
            String[] args=info.arguments().orElse(new String[0]);
            return base.startsWith("wine") && args.length>0 && launcherExecutable(args[0]);
        }).orElse(false);
    }
    static String processInstance(long pid) {
        return ProcessHandle.of(pid).flatMap(p -> p.info().startInstant()).map(Object::toString).orElse("");
    }

    static Path checkedScript(Path script) throws IOException {
        Path p = script.toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) throw new IOException("Choose an existing launcher script or executable");
        return p;
    }
    static void spawn(Path p, List<String> argv) throws IOException {
        new ProcessBuilder(argv).directory(p.getParent().toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    final class X11 implements LauncherPlatform {
        @FunctionalInterface interface Runner { String run(int timeout, String... args) throws Exception; }
        private final Runner run;
        X11() { this(ProcessTools::run); }
        X11(Runner run) { this.run = run; }
        private static long xid(String id) throws IOException {
            if (!id.matches("0x[0-9a-fA-F]+")) throw new IOException("Invalid X11 launcher ID");
            return Long.parseUnsignedLong(id.substring(2),16);
        }
        private record WindowRow(String id,long pid,String title) { }
        private List<WindowRow> rows() throws Exception {
            String output="";
            for(int attempt=0;attempt<3;attempt++){
                try{output=run.run(5,"wmctrl","-l","-p");break;}
                catch(IOException e){
                    if(attempt==2||!e.getMessage().contains("BadWindow"))throw e;
                    Thread.sleep(110);
                }
            }
            List<WindowRow> result=new ArrayList<>();
            for(String line:output.split("\\R")){
                String[] p=line.strip().split("\\s+",5);
                if(p.length!=5||!p[0].matches("0x[0-9a-fA-F]+"))continue;
                try{result.add(new WindowRow(String.format("0x%08x",xid(p[0])),Long.parseLong(p[2]),p[4]));}
                catch(NumberFormatException ignored){}
            }
            return result;
        }
        private LauncherWindow candidate(WindowRow row)throws Exception{
            long pid=row.pid();
            String properties="";
            try{properties=run.run(5,"xprop","-id",row.id(),"WM_CLASS","_NET_WM_PID");}
            catch(InterruptedException e){throw e;}
            catch(Exception ignored){/* Wine/WM_CLASS information is optional. */}
            Matcher m=Pattern.compile("(?m)^_NET_WM_PID\\([^)]*\\)\\s*=\\s*([0-9]+)\\s*$").matcher(properties);
            if(m.find()){
                long advertised=Long.parseLong(m.group(1));
                if(pid>0&&advertised>0&&pid!=advertised)throw new IOException("Window PID changed during discovery");
                if(advertised>0)pid=advertised;
            }
            String started=pid>0?processInstance(pid):"";
            long opened=0;
            if(!started.isBlank())try{opened=java.time.Instant.parse(started).toEpochMilli();}catch(Exception ignored){}
            boolean recognized=launcherClass(properties)||launcherProcess(pid);
            if(!recognized&&!lotroTitle(row.title()))throw new IOException("Not a LOTRO window");
            // Keep an exact XID, PID, process start time if exposed, class and
            // original title. Wine can omit a Linux PID/start time; such a row
            // is a manual candidate only, not automatically trusted.
            String classToken=Arrays.stream(properties.split("\\R")).filter(x->x.startsWith("WM_CLASS(")).findFirst().orElse("");
            String instance=started+"|"+classToken+"|"+row.title();
            return new LauncherWindow(row.id(),pid,row.title(),instance,recognized&&!started.isBlank(),opened);
        }
        public List<LauncherWindow> list() throws Exception{
            List<LauncherWindow> found=new ArrayList<>();
            for(WindowRow row:rows()){
                String title=row.title().toLowerCase(Locale.ROOT);
                if(!lotroTitle(row.title())&&!title.contains("launcher")&&!title.contains("turbine"))continue;
                try{found.add(candidate(row));}
                catch(InterruptedException e){throw e;}
                catch(Exception ignored){}
            }
            return LauncherWindow.ranked(found);
        }
        private void validate(LauncherWindow w)throws Exception{
            WindowRow row=rows().stream().filter(r->r.id().equals(w.id())).findFirst()
                    .orElseThrow(()->new IOException("Selected LOTRO window disappeared; refresh"));
            LauncherWindow current=candidate(row);
            if(!current.sameInstance(w))throw new IOException("Launcher instance changed; refresh and verify again");
        }
        @Override public String activeWindowId()throws Exception{
            String active=run.run(5,"xdotool","getactivewindow").strip();
            return String.format("0x%08x",Long.parseUnsignedLong(active));
        }
        public Rectangle focus(LauncherWindow w) throws Exception {
            validate(w);
            run.run(5,"wmctrl","-ia",w.id());
            Thread.sleep(220);
            return foreground(w);
        }
        public Rectangle foreground(LauncherWindow w) throws Exception {
            validate(w);
            String active = run.run(5,"xdotool","getactivewindow").strip();
            if (!Long.toUnsignedString(xid(w.id())).equals(active))
                throw new IOException("Selected LOTRO launcher is not foreground");
            String geometry = run.run(5,"xdotool","getwindowgeometry","--shell",Long.toUnsignedString(xid(w.id())));
            Rectangle bounds = geometry(geometry);
            if (!Long.toUnsignedString(xid(w.id())).equals(run.run(5,"xdotool","getactivewindow").strip()))
                throw new IOException("Launcher focus changed during verification");
            return bounds;
        }
        @Override public Guard guard(LauncherWindow w)throws Exception{
            foreground(w);
            String expected=Long.toUnsignedString(xid(w.id()));
            return ()->{
                if(!expected.equals(run.run(5,"xdotool","getactivewindow").strip()))
                    throw new IOException("Launcher lost foreground during credential entry");
            };
        }
        static Rectangle geometry(String text) throws IOException {
            Map<String,Integer> values = new HashMap<>();
            Matcher m = Pattern.compile("(?m)^(X|Y|WIDTH|HEIGHT)=(-?[0-9]+)$").matcher(text.replace("\\r",""));
            while (m.find()) values.put(m.group(1),Integer.parseInt(m.group(2)));
            if (!values.keySet().containsAll(List.of("X","Y","WIDTH","HEIGHT")) ||
                    values.get("WIDTH") < 100 || values.get("HEIGHT") < 100)
                throw new IOException("Cannot read launcher window geometry");
            return new Rectangle(values.get("X"),values.get("Y"),values.get("WIDTH"),values.get("HEIGHT"));
        }
        public void start(Path script) throws Exception {
            Path p=checkedScript(script);
            if (Files.isExecutable(p)) spawn(p,List.of(p.toString()));
            else if (p.getFileName().toString().endsWith(".sh")) spawn(p,List.of("/bin/bash",p.toString()));
            else throw new IOException("Select an executable script or .sh file");
        }
    }

    final class Windows implements LauncherPlatform {
        @FunctionalInterface interface Runner { String run(int timeout,String... args) throws Exception; }
        private final Runner run;
        private final boolean nativeGuard;
        Windows() { this.run=ProcessTools::run;this.nativeGuard=true; }
        Windows(Runner run) { this.run=run;this.nativeGuard=false; }
        private static final String NATIVE = """
            $ErrorActionPreference = 'Stop'
            Add-Type -TypeDefinition @'
            using System;
            using System.Runtime.InteropServices;
            public static class LauncherNative {
                [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
                [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
                [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
                public static string Identity() {
                    IntPtr h=GetForegroundWindow();uint pid;
                    GetWindowThreadProcessId(h,out pid);
                    return h.ToInt64()+"|"+pid;
                }
                [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT rect);
                public static string Foreground() {
                    IntPtr h=GetForegroundWindow();
                    RECT r;
                    if (h==IntPtr.Zero || !GetWindowRect(h,out r)) throw new Exception("No foreground window");
                    return h.ToInt64()+"|"+r.Left+"|"+r.Top+"|"+(r.Right-r.Left)+"|"+(r.Bottom-r.Top);
                }
            }
            '@
            """;
        private String ps(String code) throws Exception {
            return run.run(15,"powershell.exe","-NoProfile","-NonInteractive","-Command",code);
        }
        private static String processGuard(LauncherWindow w) throws IOException {
            if (w.pid() <= 0 || w.pid() > Integer.MAX_VALUE || !w.id().matches("[0-9]+") || !w.instance().matches("[0-9]+"))
                throw new IOException("Invalid Windows launcher identity");
            return "$ErrorActionPreference = 'Stop'; $p=Get-Process -Id " + w.pid() +
                    "; if ($p.MainWindowHandle.ToInt64() -ne " + w.id() +
                    " -or $p.StartTime.ToFileTimeUtc() -ne " + w.instance() +
                    " -or ($p.ProcessName -notmatch '^(lotrolauncher|turbinelauncher)$' -and " +
                    "$p.MainWindowTitle -notmatch 'lord of the rings online|^lotro')) { exit 4 }; ";
        }
        public List<LauncherWindow> list() throws Exception {
            String raw=ps("Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and ($_.ProcessName -match '^(lotrolauncher|turbinelauncher)$' -or $_.MainWindowTitle -match 'lord of the rings online|^lotro') } | ForEach-Object { [string]$_.MainWindowHandle.ToInt64() + '|' + $_.Id + '|' + $_.StartTime.ToFileTimeUtc() + '|' + $_.ProcessName + '|' + $_.MainWindowTitle }");
            List<LauncherWindow> found=new ArrayList<>();
            for (String line:raw.split("\\R")) {
                String[] p=line.split("\\|",5);
                if(p.length!=5||!p[0].matches("[0-9]+")||!p[1].matches("[0-9]+")||!p[2].matches("[0-9]+"))continue;
                boolean recognized=launcherExecutable(p[3]);
                if(!recognized&&!lotroTitle(p[4]))continue;
                long opened=Math.max(0,Long.parseLong(p[2])/10000L-11644473600000L);
                found.add(new LauncherWindow(p[0],Long.parseLong(p[1]),p[4],p[2],recognized,opened));
            }
            return LauncherWindow.ranked(found);
        }
        @Override public String activeWindowId()throws Exception{
            String raw=ps(NATIVE+"\n[LauncherNative]::Identity()").strip();
            String[] p=raw.split("\\|",2);
            if(p.length!=2||!p[0].matches("[0-9]+"))throw new IOException("Cannot identify foreground window");
            return p[0];
        }
        public Rectangle focus(LauncherWindow w) throws Exception {
            ps(processGuard(w)+"$shell=New-Object -ComObject WScript.Shell; if (-not $shell.AppActivate("+w.pid()+")) { exit 3 }");
            Thread.sleep(220);
            return foreground(w);
        }
        public Rectangle foreground(LauncherWindow w) throws Exception {
            String raw=ps(processGuard(w)+"\n"+NATIVE+"\n[LauncherNative]::Foreground()").strip();
            String[] p=raw.split("\\|");
            if(p.length!=5 || !w.id().equals(p[0])) throw new IOException("Selected LOTRO launcher is not foreground");
            Rectangle r=new Rectangle(Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]),Integer.parseInt(p[4]));
            if(r.width<100||r.height<100)throw new IOException("Invalid launcher bounds");
            return r;
        }
        /** Persistent probe only receives CHECK/QUIT, never a username or
         * password. Avoid starting PowerShell for every credential character. */
        @Override public Guard guard(LauncherWindow w)throws Exception{
            foreground(w);
            if(!nativeGuard)return LauncherPlatform.super.guard(w);
            String script=NATIVE+"\n[Console]::WriteLine('READY'); [Console]::Out.Flush(); "+
                    "while (($line=[Console]::ReadLine()) -ne $null) { "+
                    "if ($line -ne 'CHECK') { break }; [Console]::WriteLine([LauncherNative]::Identity()); [Console]::Out.Flush() }";
            String encoded=Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            Process p=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-EncodedCommand",encoded)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
            BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(p.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8));
            ExecutorService reads=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"launcher-foreground-probe");t.setDaemon(true);return t;});
            class Probe implements Guard {
                private String response(int seconds)throws Exception{
                    Future<String> f=reads.submit(reader::readLine);
                    try{return f.get(seconds,TimeUnit.SECONDS);}
                    catch(TimeoutException ex){f.cancel(true);throw new IOException("Windows foreground probe timed out");}
                }
                void ready()throws Exception{
                    if(!"READY".equals(response(15)))throw new IOException("Windows foreground probe could not initialize");
                }
                public void verify()throws Exception{
                    writer.write("CHECK");writer.newLine();writer.flush();
                    if(!(w.id()+"|"+w.pid()).equals(response(4)))
                        throw new IOException("Launcher lost foreground during credential entry");
                }
                public void close(){
                    p.destroyForcibly();reads.shutdownNow();
                    try{writer.close();}catch(IOException ignored){}
                    try{reader.close();}catch(IOException ignored){}
                }
            }
            Probe probe=new Probe();
            try{probe.ready();return probe;}catch(Exception e){probe.close();throw e;}
        }
        public void start(Path script) throws Exception {
            Path p=checkedScript(script);
            String name=p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!(name.endsWith(".exe")||name.endsWith(".bat")||name.endsWith(".cmd")||name.endsWith(".ps1")))
                throw new IOException("Choose a .exe, .bat, .cmd or .ps1 launcher");
            // Literal quoting, no credential argument. The chosen local script is
            // explicitly authorized by the user, not provided by a network peer.
            String code="& '"+p.toString().replace("'","''")+"'";
            String encoded=Base64.getEncoder().encodeToString(code.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
            spawn(p,List.of("powershell.exe","-NoProfile","-NonInteractive","-EncodedCommand",encoded));
        }
    }
}
