package org.lotro.conductor;

import java.util.*;

/** Translate visible ABC part assignments into instrument quickslot requests.
 * No instrument / music-mode verification; unknown instrument titles are skipped.
 */
public final class SongEquipPlanner {
    private SongEquipPlanner() {}
    public record Result(List<LocalAgent.EquipRequirement> local, List<String> warnings, int remote) {}
    public record Target(CharacterInfo musician, String instrument) { }
    public record NetworkPlan(List<Target> targets, List<String> warnings) { }
    public static NetworkPlan planNetwork(Music.Song song, Map<String,Integer> assignments,
            List<CharacterInfo> roster, Map<Integer,String> setlistInstruments) {
        if(song==null)return new NetworkPlan(List.of(),List.of("Load an ABC song first"));
        Map<Integer,Music.Part> byTrack=new HashMap<>();
        for(Music.Part part:song.parts())byTrack.put(part.id(),part);
        List<Target> targets=new ArrayList<>();
        List<String> warnings=new ArrayList<>();
        for(CharacterInfo musician:roster) {
            Integer id=assignments.get(musician.key());
            if(id==null)continue;
            Music.Part part=byTrack.get(id);
            if(part==null){warnings.add("Missing ABC part X:"+id+" for "+musician.name());continue;}
            String instrument=setlistInstruments.get(id);
            if(instrument==null || instrument.isBlank())instrument=InstrumentMatcher.requiredInstrument(part.title());
            if(instrument==null || instrument.isBlank()){
                warnings.add("Instrument not identified for X:"+id+" ("+musician.name()+")");continue;
            }
            targets.add(new Target(musician,instrument));
        }
        return new NetworkPlan(List.copyOf(targets),List.copyOf(warnings));
    }
    public static Result plan(Music.Song song, Map<String,Integer> assignments,
            List<CharacterInfo> roster, String localNode, Map<Integer,String> setlistInstruments) {
        if(song==null)return new Result(List.of(),List.of("Load an ABC song first"),0);
        Map<Integer,Music.Part> byTrack=new HashMap<>();
        for(Music.Part p:song.parts())byTrack.put(p.id(),p);
        List<LocalAgent.EquipRequirement> local=new ArrayList<>();
        List<String> warnings=new ArrayList<>();
        int remote=0;
        for(CharacterInfo musician:roster) {
            Integer trackId=assignments.get(musician.key());
            if(trackId==null)continue;
            Music.Part part=byTrack.get(trackId);
            if(part==null){warnings.add("Missing ABC part X:"+trackId+" for "+musician.name());continue;}
            String instrument=setlistInstruments.get(trackId);
            if(instrument==null || instrument.isBlank())instrument=InstrumentMatcher.requiredInstrument(part.title());
            if(instrument==null || instrument.isBlank()) {
                warnings.add("Instrument not identified for X:"+trackId+" ("+musician.name()+")");continue;
            }
            if(!musician.nodeId().equals(localNode)){remote++;continue;}
            local.add(new LocalAgent.EquipRequirement(musician.name(),instrument));
        }
        return new Result(List.copyOf(local),List.copyOf(warnings),remote);
    }
}
