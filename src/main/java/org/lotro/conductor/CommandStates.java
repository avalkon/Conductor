package org.lotro.conductor;

/** Command dispatch evidence is distinct from actual game readiness.
 * No unreliable inferred music mode or target selection is exposed. */
public final class CommandStates {
    private String sync="none";
    private String music="none";
    private String follow="none";
    public void sent(String kind) {
        switch(kind) {
            case "PREPARE" -> {if(!sync.equals("ready"))sync="sent";}
            case "MUSIC" -> music="sent";
            case "FOLLOW" -> follow="sent";
            case "START" -> resetPlayback();
            default -> { }
        }
    }
    public void bridgeReady(boolean confirmed) {if(confirmed)sync="ready";}
    public void resetPlayback() {sync="none";}
    public String sync() {return sync;}
    public String music() {return music;}
    public String follow() {return follow;}
    public String display() {return "sync="+sync+"  music="+music+"  follow="+follow;}
}
