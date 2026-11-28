package org.lotro.conductor;

import java.util.*;

/** A possible LOTRO launcher. Recognized is a discovery hint only; all
 * credentials still require a verified login page and foreground window. */
record LauncherWindow(String id,long pid,String title,String instance,boolean recognized,long openedAtMillis) {
    LauncherWindow(String id,long pid,String title,String instance){
        this(id,pid,title,instance,true,0);
    }
    boolean sameInstance(LauncherWindow other){
        return other!=null && id.equals(other.id()) && pid==other.pid() && instance.equals(other.instance());
    }
    @Override public String toString() {
        return (recognized?"Launcher":"Candidate")+" ["+id+"]"+(pid>0?" PID "+pid:"")+" — "+title;
    }
    /** Keep recognized launchers first; among same-title candidates, newest
     * observable process first. Reverse discovery order for equal/unknown times. */
    static List<LauncherWindow> ranked(List<LauncherWindow> input){
        List<LauncherWindow> result=new ArrayList<>(input);
        Collections.reverse(result);
        result.sort(Comparator.comparing(LauncherWindow::recognized).reversed()
                .thenComparing(Comparator.comparingLong(LauncherWindow::openedAtMillis).reversed()));
        return List.copyOf(result);
    }
    static List<LauncherWindow> newSince(List<LauncherWindow> before,List<LauncherWindow> after){
        return after.stream().filter(w->before.stream().noneMatch(w::sameInstance)).toList();
    }
}
