/* Optional minimal Lua 5.4 launcher for environments with liblua but no lua binary.
   Not part of the Java application or Maven build. */
#include <stdio.h>
typedef struct lua_State lua_State;
extern lua_State* luaL_newstate(void);
extern void luaL_openlibs(lua_State*);
extern int luaL_loadfilex(lua_State*,const char*,const char*);
extern int lua_pcallk(lua_State*,int,int,int,long,void*);
extern const char* lua_tolstring(lua_State*,int,size_t*);
extern void lua_close(lua_State*);
int main(int argc,char** argv) {
    lua_State* s=luaL_newstate();luaL_openlibs(s);
    if(argc<2){fprintf(stderr,"usage: runner mock.lua\n");return 2;}
    if(luaL_loadfilex(s,argv[1],NULL)||lua_pcallk(s,0,-1,0,0,NULL)){
        fprintf(stderr,"Lua: %s\n",lua_tolstring(s,-1,NULL));lua_close(s);return 1;
    }
    lua_close(s);return 0;
}
