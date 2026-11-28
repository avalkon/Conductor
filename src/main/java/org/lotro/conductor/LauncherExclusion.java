package org.lotro.conductor;

import java.util.concurrent.atomic.AtomicReference;

/** Session-local exclusion of the selected launcher, independent of its title.
 * Never persist transient HWNDs, PIDs or XIDs across Conductor restarts. */
final class LauncherExclusion {
    private final AtomicReference<LauncherWindow> selected=new AtomicReference<>();
    void set(LauncherWindow window){selected.set(window);}
    LauncherWindow current(){return selected.get();}
    private static boolean sameXid(String a,String b){
        if(!a.matches("0x[0-9a-fA-F]+")||!b.matches("0x[0-9a-fA-F]+"))return false;
        try{return Long.parseUnsignedLong(a.substring(2),16)==Long.parseUnsignedLong(b.substring(2),16);}
        catch(NumberFormatException e){return false;}
    }
    private static boolean differentProcess(LauncherWindow w){
        if(w.pid()<=0||w.openedAtMillis()<=0)return false;
        // If process metadata cannot be read, continue excluding the chosen
        // identity rather than letting gameplay input reach the launcher.
        try{return ProcessHandle.of(w.pid()).flatMap(p->p.info().startInstant())
                .map(t->Math.abs(t.toEpochMilli()-w.openedAtMillis())>1).orElse(false);}
        catch(SecurityException e){return false;}
    }
    boolean x11(String id){
        LauncherWindow w=selected.get();
        return w!=null&&sameXid(w.id(),id);
    }
    boolean windows(String pid){
        LauncherWindow w=selected.get();
        if(w==null||!Long.toString(w.pid()).equals(pid))return false;
        if(differentProcess(w)){selected.compareAndSet(w,null);return false;}
        return true;
    }
    /** Only a complete, successful enumeration can retire a vanished XID.
     * A new PID/start time proves a reused handle is not the old launcher. */
    void observeX11(LauncherWindow w,String completeListing){
        if(w==null||selected.get()!=w)return;
        for(String line:completeListing.split("\\R")){
            String[] fields=line.strip().split("\\s+",5);
            if(fields.length<3||!sameXid(w.id(),fields[0]))continue;
            try{
                long pid=Long.parseLong(fields[2]);
                if((w.pid()>0&&pid>0&&w.pid()!=pid)||differentProcess(w))
                    selected.compareAndSet(w,null);
            }catch(NumberFormatException ignored){}
            return;
        }
        selected.compareAndSet(w,null);
    }
}
