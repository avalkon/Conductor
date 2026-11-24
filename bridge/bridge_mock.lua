-- Developer-only Lua test. It does NOT substitute for testing inside LOTRO.
local source = (arg and arg[1]) or "bridge/ConductorBridge.lua";
local lines, saved, count = {}, nil, 0;
import = function(name) assert(name == "Turbine.Gameplay"); end;
local instrument = "Basic Lute";
local player = {
    GetName = function() return "Sinduriel"; end,
    GetEquipment = function() return {
        GetItem = function(self, slot) return {GetName = function() return instrument; end}; end,
    }; end,
};
Turbine = {
    Gameplay={Equipment={Instrument=2},LocalPlayer={GetInstance=function() return player; end}},
    DataScope={Character=1},
    Shell={WriteLine=function(s) lines[#lines+1]=s; end,AddCommand=function(s,c) end},
    ShellCommand=function() return {}; end,
    PluginData={Save=function(scope,key,blob,done)
        assert(scope==1 and key=="ConductorStatus"); count=count+1; saved=blob; done(true,"ok");
    end},
};
local m = {
    selectedSongIndex=1, selectedTrack=1, PlayerSynced=0,
    syncedSongIndex=-1,syncedTrack=-1,
    SongBook3SongPath=function(song) return string.lower(song.Filepath..song.Filename); end,
    SongDB={Songs={{Filepath="/",Filename="Demo",Tracks={{Id="1",Name="Basic Lute"},{Id="2",Name="Basic Harp"}}}}},
};
Sinduriel={SongBook3={Windows={Main=m}}};
-- Window is intentionally a global from SongBook3's Main.lua, unlike SongDB.
songBook3Window={
    aFilteredIndices={1}, bShowReadyChars=false, timerInMusicMode=false,
    songlistBox={index=0, SetSelectedIndex=function(self,row)self.index=row;end},
    SetListboxColours=function(self,list)self.highlight=list.index;end,
    SelectedTrackIndex=function(self,i) return i; end,
    SelectSong=function(self,row) m.selectedSongIndex=self.aFilteredIndices[row]; end,
    SelectTrack=function(self,row) m.selectedTrack=row; conductorBridgeOnSelectionChanged(); end,
    SelectSetup=function() end,
    SelectDir=function() end,
};
assert(loadfile(source))();
local cmd=conductorBridgeCommand;
assert(cmd ~= nil and saved:find("CG2%%Sinduriel%%Basic Lute%%/demo%%1%%unknown%%unconfirmed%%"));
cmd:Execute("conductor","diagnose");
assert(lines[#lines]:find("window=ready, library=1 songs",1,true));
cmd:Execute("conductor","select /demo | 2");
assert(m.selectedTrack==2 and saved:find("%%2%%unknown%%unconfirmed%%"));
assert(songBook3Window.songlistBox.index==1 and songBook3Window.highlight==1,
    "Selecting a SongBook song also highlights its visible list row");
-- A filtered or reordered song list must use its *visible* row, not SongDB index.
songBook3Window.aFilteredIndices={2,1};
cmd:Execute("conductor","select /demo | 2");
assert(songBook3Window.songlistBox.index==2 and songBook3Window.highlight==2,
    "Highlight follows the filtered SongBook row");
songBook3Window.aFilteredIndices={1};
cmd:Execute("conductor","identify deadbeef0123456789abcdef00000001");
assert(saved:find("%deadbeef0123456789abcdef00000001%",1,true));
local before=count;
conductorBridgeOnEquipmentChanged();
assert(count==before);
instrument="Basic Harp";conductorBridgeOnEquipmentChanged();
assert(saved:find("CG2%%Sinduriel%%Basic Harp"));
songBook3Window.timerInMusicMode=true;
conductorBridgeOnMusicModeChanged();
assert(saved:find("%%observed%-on%%"));
m.PlayerSynced=1;
conductorBridgeOnLocalReady("Basic Harp");
assert(saved:find("%%ready%%"));
conductorBridgeOnPlaybackStarted();
assert(saved:find("%%unconfirmed%%"));
m.SongDB=nil;
cmd:Execute("conductor","diagnose");
assert(lines[#lines]:find("library=missing",1,true));
print("PASS -- namespaced library/selection, identity token, automatic instrument, music observation, local ready and reset");
