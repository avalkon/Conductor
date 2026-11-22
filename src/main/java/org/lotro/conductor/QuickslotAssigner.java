package org.lotro.conductor;

import java.util.*;

/** Maximum-cardinality part matching, then previous-instrument continuity.
 * Only musicians with an explicitly configured shortcut for the required
 * instrument are eligible. Existing manual assignments are always preserved.
 */
public final class QuickslotAssigner {
    private QuickslotAssigner() { }
    public static Map<String,Integer> assign(List<Music.Part> parts, List<CharacterInfo> roster,
            Collection<ProfileStore.Profile> profiles, Map<String,Integer> existing,
            Map<String,String> previousInstruments) {
        Map<String,ProfileStore.Profile> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(ProfileStore.Profile profile : profiles) byName.put(profile.name(), profile);
        Map<String,Set<String>> capabilities = new HashMap<>();
        for(CharacterInfo musician : roster) {
            ProfileStore.Profile profile = byName.get(musician.name());
            if(profile != null) capabilities.put(musician.key(), profile.quickslots().keySet());
        }
        return assignWithCapabilities(parts, roster, capabilities, existing, previousInstruments);
    }

    /** The coordinator receives instrument NAMES only, never remote keyboard bindings.
     * Capabilities are keyed by node ID + character name to prevent owner confusion. */
    public static Map<String,Integer> assignWithCapabilities(List<Music.Part> parts,
            List<CharacterInfo> roster, Map<String,? extends Collection<String>> capabilities,
            Map<String,Integer> existing, Map<String,String> previousInstruments) {
        Set<String> taken = new HashSet<>(existing.keySet());
        Set<Integer> occupied = new HashSet<>(existing.values());
        List<Music.Part> freeParts=parts.stream().filter(p-> !occupied.contains(p.id()))
                .sorted(Comparator.comparingInt(Music.Part::id)).toList();
        List<CharacterInfo> freePeople=roster.stream().filter(c->!taken.contains(c.key()))
                .sorted(Comparator.comparing(CharacterInfo::key)).toList();
        int rows=freeParts.size(), cols=freePeople.size();
        if(rows==0||cols==0)return Map.of();
        int n=Math.max(rows,cols);
        long[][] weight=new long[n][n];
        for(int i=0;i<rows;i++) {
            String instrument=InstrumentMatcher.requiredInstrument(freeParts.get(i).title());
            if(instrument==null)continue;
            for(int j=0;j<cols;j++) {
                CharacterInfo character=freePeople.get(j);
                Collection<String> available = capabilities.get(character.key());
                if(available == null || available.stream().noneMatch(instrument::equalsIgnoreCase)) continue;
                String prior=previousInstruments.get(character.key());
                long continuity = prior!=null && prior.equalsIgnoreCase(instrument) ? 1000 : 0;
                long equipped = character.instrument().equalsIgnoreCase(instrument) ? 100 : 0;
                weight[i][j] = 10000+continuity+equipped;
            }
        }
        // Hungarian algorithm, 1-based indexing. Ineligible edges are 0,
        // so they cannot be returned as assignments. Dummy rows/columns allow gaps.
        long[] u=new long[n+1],v=new long[n+1];
        int[] p=new int[n+1],way=new int[n+1];
        for(int i=1;i<=n;i++) {
            p[0]=i;
            int j0=0;
            long[] minv=new long[n+1];Arrays.fill(minv,Long.MAX_VALUE/4);
            boolean[] used=new boolean[n+1];
            do {
                used[j0]=true;
                int i0=p[j0],j1=0;
                long delta=Long.MAX_VALUE/4;
                for(int j=1;j<=n;j++)if(!used[j]) {
                    long cur = -weight[i0-1][j-1]-u[i0]-v[j];
                    if(cur<minv[j]) {minv[j]=cur;way[j]=j0;}
                    if(minv[j]<delta) {delta=minv[j];j1=j;}
                }
                for(int j=0;j<=n;j++) {
                    if(used[j]) {u[p[j]]+=delta;v[j]-=delta;}
                    else if(j>0) minv[j]-=delta;
                }
                j0=j1;
            }while(p[j0]!=0);
            do {int j1=way[j0];p[j0]=p[j1];j0=j1;}while(j0!=0);
        }
        Map<String,Integer> selected=new LinkedHashMap<>();
        for(int j=1;j<=n;j++) {
            int i=p[j]-1, k=j-1;
            if(i>=0&&i<rows&&k<cols&&weight[i][k]>0)
                selected.put(freePeople.get(k).key(),freeParts.get(i).id());
        }
        return selected;
    }
}
