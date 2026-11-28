package org.lotro.conductor;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

/** Per-computer encrypted login vault. No decryption secret is saved alongside it.
 * The master passphrase unlocks an AES-GCM key once per Conductor session. */
final class LauncherVault implements AutoCloseable {
    private static final byte[] MAGIC={'L','C','V',1};
    private static final int ITERATIONS=600_000,SALT_BYTES=16,NONCE_BYTES=12,MAX_BYTES=128*1024;
    private static final SecureRandom RANDOM=new SecureRandom();
    private final Path file;
    private byte[] salt,key;
    private final Map<String,char[]> passwords=new LinkedHashMap<>();
    LauncherVault(Path file){this.file=file.toAbsolutePath().normalize();}
    boolean exists(){return Files.isRegularFile(file);}
    boolean unlocked(){return key!=null;}
    private static byte[] random(int length){byte[] b=new byte[length];RANDOM.nextBytes(b);return b;}
    private static byte[] derive(char[] master,byte[] salt)throws GeneralSecurityException{
        PBEKeySpec p=new PBEKeySpec(master,salt,ITERATIONS,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(p).getEncoded();}
        finally{p.clearPassword();}
    }
    private static Cipher cipher(int mode,byte[] key,byte[] nonce)throws GeneralSecurityException{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        c.updateAAD(MAGIC);
        return c;
    }
    void create(char[] master)throws Exception{
        if(exists())throw new IOException("Vault already exists; unlock it");
        if(master.length<6||master.length>1024)throw new IllegalArgumentException("Master password must be at least 6 characters");
        close();
        salt=random(SALT_BYTES);
        key=derive(master,salt);
        try{save();}catch(Exception e){close();throw e;}
    }
    void unlock(char[] master)throws Exception{
        if(unlocked())return;
        if(master.length<1||master.length>1024)throw new IllegalArgumentException("Enter your master password");
        if(!exists())throw new IOException("No login vault exists yet");
        if(Files.size(file)>MAX_BYTES)throw new IOException("Invalid vault size");
        byte[] bytes=Files.readAllBytes(file),candidate=null,clear=null;
        try{
            if(bytes.length<MAGIC.length+SALT_BYTES+NONCE_BYTES+16||
                    !Arrays.equals(MAGIC,Arrays.copyOf(bytes,MAGIC.length)))
                throw new IOException("Unsupported or damaged login vault");
            int offset=MAGIC.length;
            byte[] readSalt=Arrays.copyOfRange(bytes,offset,offset+=SALT_BYTES);
            byte[] nonce=Arrays.copyOfRange(bytes,offset,offset+=NONCE_BYTES);
            candidate=derive(master,readSalt);
            try{clear=cipher(Cipher.DECRYPT_MODE,candidate,nonce).doFinal(bytes,offset,bytes.length-offset);}
            catch(AEADBadTagException e){throw new IOException("Incorrect master password or damaged vault");}
            Map<String,char[]> decoded=decode(clear);
            close();
            salt=readSalt;key=candidate;candidate=null;passwords.putAll(decoded);
        }finally{
            if(candidate!=null)Arrays.fill(candidate,(byte)0);
            if(clear!=null)Arrays.fill(clear,(byte)0);
            Arrays.fill(bytes,(byte)0);
        }
    }
    private static Map<String,char[]> decode(byte[] raw)throws IOException {
        Map<String,char[]> result=new LinkedHashMap<>();
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(raw))){
            int count=in.readInt();
            if(count<0||count>100)throw new IOException("Invalid vault entries");
            for(int i=0;i<count;i++){
                String name=in.readUTF();
                int n=in.readInt();
                if(n<1||n>256)throw new IOException("Invalid vault entry");
                char[] pwd=new char[n];
                try{
                    for(int j=0;j<n;j++)pwd[j]=in.readChar();
                    LauncherLogin.Account.validate(name,pwd);
                    if(result.putIfAbsent(name,pwd)!=null)throw new IOException("Duplicate vault entry");
                }catch(Exception e){Arrays.fill(pwd,'\0');throw new IOException("Invalid vault entry",e);}
            }
            if(in.read()!=-1)throw new IOException("Invalid vault data");
            return result;
        }catch(IOException e){result.values().forEach(p->Arrays.fill(p,'\0'));throw e;}
    }
    private void requireUnlocked(){if(!unlocked())throw new IllegalStateException("Unlock the local login vault first");}
    Set<String> names(){requireUnlocked();return Set.copyOf(passwords.keySet());}
    boolean has(String name){return unlocked()&&passwords.containsKey(name);}
    char[] copyPassword(String name){
        requireUnlocked();
        char[] p=passwords.get(name);
        if(p==null)throw new IllegalStateException("A selected account has no saved password");
        return p.clone();
    }
    void put(String name,char[] pwd)throws Exception{
        requireUnlocked();LauncherLogin.Account.validate(name,pwd);
        if(!passwords.containsKey(name)&&passwords.size()>=100)throw new IOException("Maximum 100 saved accounts");
        char[] next=pwd.clone(),old=passwords.put(name,next);
        try{save();}catch(Exception e){
            if(old==null)passwords.remove(name);else passwords.put(name,old);
            Arrays.fill(next,'\0');throw e;
        }
        if(old!=null)Arrays.fill(old,'\0');
    }
    void remove(String name)throws Exception{
        requireUnlocked();
        char[] old=passwords.remove(name);
        if(old==null)return;
        try{save();}catch(Exception e){passwords.put(name,old);throw e;}
        Arrays.fill(old,'\0');
    }
    /** Allows wiping ByteArrayOutputStream's backing buffer as well as its copy. */
    private static final class WipingBytes extends ByteArrayOutputStream {
        void wipe(){Arrays.fill(buf,(byte)0);reset();}
    }
    private void save()throws Exception {
        requireUnlocked();
        WipingBytes buffer=new WipingBytes();
        byte[] plain=null,encrypted=null;
        try{
            try(DataOutputStream out=new DataOutputStream(buffer)){
                out.writeInt(passwords.size());
                for(var entry:passwords.entrySet()){
                    out.writeUTF(entry.getKey());
                    char[] pwd=entry.getValue();out.writeInt(pwd.length);
                    for(char c:pwd)out.writeChar(c);
                }
            }
            plain=buffer.toByteArray();
            byte[] nonce=random(NONCE_BYTES);
            encrypted=cipher(Cipher.ENCRYPT_MODE,key,nonce).doFinal(plain);
            Files.createDirectories(file.getParent());
            Path tmp=Files.createTempFile(file.getParent(),"login-vault-",".tmp");
            try{
                if(Files.getFileStore(tmp).supportsFileAttributeView("posix"))
                    Files.setPosixFilePermissions(tmp,PosixFilePermissions.fromString("rw-------"));
                try(OutputStream out=Files.newOutputStream(tmp)){
                    out.write(MAGIC);out.write(salt);out.write(nonce);out.write(encrypted);
                }
                try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
                catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
            }finally{Files.deleteIfExists(tmp);}
        }finally{
            buffer.wipe();if(plain!=null)Arrays.fill(plain,(byte)0);if(encrypted!=null)Arrays.fill(encrypted,(byte)0);
        }
    }
    @Override public void close(){
        passwords.values().forEach(p->Arrays.fill(p,'\0'));passwords.clear();
        if(key!=null)Arrays.fill(key,(byte)0);
        if(salt!=null)Arrays.fill(salt,(byte)0);
        key=null;salt=null;
    }
}
