package org.lotro.conductor;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;

/** Persists only field points and the small non-secret page-label reference
 * patches. No window IDs, credentials or full desktop screenshots. */
final class LauncherLayoutStore {
    private static final int MAGIC=0x4c434c01;
    private final Path path;
    LauncherLayoutStore(Path path){this.path=path;}
    LauncherLogin.Layout load()throws IOException {
        if(!Files.isRegularFile(path))return null;
        if(Files.size(path)>2_000_000)throw new IOException("Invalid launcher calibration size");
        try(DataInputStream in=new DataInputStream(Files.newInputStream(path))){
            if(in.readInt()!=MAGIC)throw new IOException("Unknown launcher calibration format");
            int w=in.readInt(),h=in.readInt();
            if(w<100||w>10000||h<100||h>10000)throw new IOException("Invalid launcher dimensions");
            Point u=new Point(in.readInt(),in.readInt()),p=new Point(in.readInt(),in.readInt());
            Rectangle bounds=new Rectangle(0,0,w,h);
            if(!bounds.contains(u)||!bounds.contains(p)||u.equals(p))throw new IOException("Invalid login field positions");
            var a=marker(in,bounds);var b=marker(in,bounds);
            LauncherLogin.Marker world=in.readBoolean()?marker(in,bounds):null;
            if(in.read()!=-1)throw new IOException("Invalid calibration data");
            return new LauncherLogin.Layout(w,h,u,p,List.of(a,b),world);
        }
    }
    private static LauncherLogin.Marker marker(DataInputStream in,Rectangle bounds)throws IOException{
        Rectangle r=new Rectangle(in.readInt(),in.readInt(),in.readInt(),in.readInt());
        if(r.width<12||r.height<10||r.width>512||r.height>256||!bounds.contains(r))throw new IOException("Invalid page marker");
        BufferedImage image=new BufferedImage(r.width,r.height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<r.height;y++)for(int x=0;x<r.width;x++)image.setRGB(x,y,in.readInt());
        return new LauncherLogin.Marker(r,image);
    }
    void save(LauncherLogin.Layout layout)throws IOException{
        Files.createDirectories(path.getParent());
        Path tmp=Files.createTempFile(path.getParent(),"launcher-layout-",".tmp");
        try{
            try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(tmp))){
                out.writeInt(MAGIC);out.writeInt(layout.width());out.writeInt(layout.height());
                out.writeInt(layout.username().x);out.writeInt(layout.username().y);
                out.writeInt(layout.password().x);out.writeInt(layout.password().y);
                for(var m:layout.login())m.write(out);
                out.writeBoolean(layout.world()!=null);if(layout.world()!=null)layout.world().write(out);
            }
            try{Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
}
