package org.lotro.conductor;

import java.util.List;
import java.util.Objects;

/** Excludes the designated leader by node+character identity, never just by window title. */
public final class BandTargets {
    private BandTargets() { }
    public static List<CharacterInfo> exceptLeader(List<CharacterInfo> roster, CharacterInfo leader) {
        Objects.requireNonNull(roster,"roster");
        if (leader == null || roster.stream().noneMatch(c -> c.key().equals(leader.key())))
            throw new IllegalArgumentException("Select a currently registered leader first");
        return roster.stream().filter(c -> !c.key().equals(leader.key())).toList();
    }
}
