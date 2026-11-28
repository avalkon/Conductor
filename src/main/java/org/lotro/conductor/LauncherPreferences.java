package org.lotro.conductor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Local launcher preferences. No password field exists in this format. */
final class LauncherPreferences {
    private final Path file;
    String script="";
    List<String> accounts=List.of();
    Set<String> checked=Set.of();
    LauncherLogin.Timing timing=LauncherLogin.Timing.DEFAULT;
    LauncherPreferences(Path file)throws IOException {
        this.file=file.toAbsolutePath().normalize();
        if(!Files.isRegularFile(this.file))return;
        Properties p=new Properties();
        try(InputStream in=Files.newInputStream(this.file)){p.load(in);}
        script=p.getProperty("launcherScript","");
        LinkedHashSet<String> names=new LinkedHashSet<>();
        int count=integer(p,"accountCount",0);
        for(int i=0;i<Math.min(100,Math.max(0,count));i++){
            String name=p.getProperty("account."+i,"");
            if(!name.isBlank()&&name.length()<=128&&name.chars().allMatch(c->c>=32&&c<=126))names.add(name);
        }
        accounts=List.copyOf(names);
        Set<String> selected=new HashSet<>();
        for(int i=0;i<accounts.size();i++)if(Boolean.parseBoolean(p.getProperty("checked."+i,"false")))selected.add(accounts.get(i));
        checked=Set.copyOf(selected);
        try{timing=new LauncherLogin.Timing(integer(p,"keyDelayMs",45),integer(p,"afterLoginMs",2000),
                integer(p,"afterLaunchMs",6000),integer(p,"refocusMs",1500),integer(p,"timeoutMs",60000));}
        catch(IllegalArgumentException ignored){timing=LauncherLogin.Timing.DEFAULT;}
    }
    private static int integer(Properties p,String key,int fallback){
        try{return Integer.parseInt(p.getProperty(key,Integer.toString(fallback)));}
        catch(NumberFormatException e){return fallback;}
    }
    void save()throws IOException {
        Properties p=new Properties();
        p.setProperty("launcherScript",script);
        p.setProperty("accountCount",Integer.toString(accounts.size()));
        for(int i=0;i<accounts.size();i++){
            p.setProperty("account."+i,accounts.get(i));
            p.setProperty("checked."+i,Boolean.toString(checked.contains(accounts.get(i))));
        }
        p.setProperty("keyDelayMs",Integer.toString(timing.keyDelayMs()));
        p.setProperty("afterLoginMs",Integer.toString(timing.afterLoginMs()));
        p.setProperty("afterLaunchMs",Integer.toString(timing.afterLaunchMs()));
        p.setProperty("refocusMs",Integer.toString(timing.refocusMs()));
        p.setProperty("timeoutMs",Integer.toString(timing.timeoutMs()));
        Files.createDirectories(file.getParent());
        Path tmp=Files.createTempFile(file.getParent(),"launcher-",".tmp");
        try{
            try(OutputStream out=Files.newOutputStream(tmp)){p.store(out,"Conductor local launcher preferences (account names only)");}
            try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
}
