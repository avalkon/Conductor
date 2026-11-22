package org.lotro.conductor;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One TCP coordinator, running inside the master process. No extra daemon. */
public final class BandServer implements AutoCloseable {
    private final String secret;
    private final ServerSocket listener;
    private final Set<Peer> peers = ConcurrentHashMap.newKeySet();
    private final Map<String, CharacterInfo> roster = new LinkedHashMap<>();
    private final Map<String,CommandStates> commandStates = new HashMap<>();
    // Only instrument names are announced. Actual shortcuts remain local to agents.
    private final Map<String,Set<String>> capabilities = new HashMap<>();
    private volatile boolean running;
    private String controllerId;
    private long rosterRevision;
    public BandServer(int port, String secret) throws IOException {
        this(port, secret, false);
    }
    /** Loopback-only mode for development on one computer; not reachable over the LAN. */
    public BandServer(int port, String secret, boolean loopbackOnly) throws IOException {
        this.secret = secret;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(loopbackOnly
                ? new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
                : new InetSocketAddress(port));
    }
    public void start() {
        running = true;
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = listener.accept();
                    socket.setSoTimeout(8000);
                    Thread worker = new Thread(() -> serve(socket), "conductor-peer");
                    worker.setDaemon(true); worker.start();
                } catch (IOException e) { if (running) System.err.println("Conductor accept error: " + e); }
            }
        }, "conductor-accept");
        accept.setDaemon(true); accept.start();
    }
    public int port() { return listener.getLocalPort(); }
    private void serve(Socket socket) {
        Peer peer = null;
        try (socket;
             Reader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             Writer writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String nonce = Auth.nonce();
            Wire.write(writer, "CHALLENGE", nonce);
            Wire.Message hello = Wire.decode(Wire.read(reader));
            hello.size(4);
            if (!hello.type().equals("AUTH")) throw new IOException("Expected AUTH");
            String id = hello.at(0), name = hello.at(1), role = hello.at(2), mac = hello.at(3);
            if (!id.matches("[a-zA-Z0-9-]{4,70}") || name.isBlank() || name.length() > 60 ||
                    (!role.equals("controller") && !role.equals("agent")) ||
                    !Auth.equalsSecure(Auth.response(secret, nonce, id, role), mac))
                throw new IOException("Authentication failed");
            synchronized (this) {
                if (peers.stream().anyMatch(p -> p.id.equals(id))) throw new IOException("Node ID already connected");
                if (role.equals("controller")) {
                    if (controllerId != null) throw new IOException("Another conductor already controls this band");
                    controllerId = id;
                }
                peer = new Peer(id, name, role.equals("controller"), writer);
                peers.add(peer);
            }
            socket.setSoTimeout(0);
            peer.send("WELCOME", "Authenticated as " + role);
            broadcastRoster();
            String line;
            while ((line = Wire.read(reader)) != null) {
                Wire.Message msg = Wire.decode(line);
                handle(peer, msg);
            }
        } catch (Exception e) {
            System.err.println("Conductor connection ended: " + e.getMessage());
        } finally {
            if (peer != null) {
                synchronized (this) {
                    peers.remove(peer);
                    if (peer.id.equals(controllerId)) controllerId = null;
                    String departingId = peer.id;
                    roster.entrySet().removeIf(entry -> entry.getValue().nodeId().equals(departingId));
                    commandStates.keySet().removeIf(key -> key.startsWith(departingId+"::"));
                    capabilities.keySet().removeIf(key -> key.startsWith(departingId+"::"));
                }
                broadcastRoster();
            }
        }
    }
    private void handle(Peer peer, Wire.Message m) throws IOException {
        switch (m.type()) {
            case "LIST" -> {
                m.size(0); broadcastRoster();
            }
            case "REGISTER" -> {
                m.size(3);
                String name = m.at(0), instrument = m.at(1), window = m.at(2);
                if (name.isBlank() || name.length() > 60 || instrument.length() > 150 || window.length() > 300)
                    throw new IOException("Invalid character registration");
                synchronized (this) {
                    String key = key(peer.id, name);
                    boolean duplicate = roster.values().stream().anyMatch(c -> c.name().equalsIgnoreCase(name) && !c.key().equals(key));
                    if (duplicate) { peer.send("ERROR", "Duplicate character name: " + name); return; }
                    CommandStates state=commandStates.computeIfAbsent(key,k->new CommandStates());
                    roster.put(key, new CharacterInfo(peer.id, peer.name, name, instrument, window,state.display()));
                    capabilities.remove(key); // stale keybindings must not survive re-registration
                }
                broadcastRoster();
            }
            case "REMOVE" -> {
                m.size(1);
                synchronized (this) {
                    roster.remove(key(peer.id, m.at(0)));
                    commandStates.remove(key(peer.id,m.at(0)));
                    capabilities.remove(key(peer.id,m.at(0)));
                }
                broadcastRoster();
            }
            // Stream a complete capability snapshot for ONE character before publishing it.
            // A partial or interrupted snapshot never becomes visible to Assign All.
            case "CAPS_BEGIN" -> {
                m.size(1);
                synchronized (this) {
                    if (!roster.containsKey(key(peer.id,m.at(0)))) {
                        peer.send("ERROR","Cannot publish capabilities for an unregistered character"); return;
                    }
                    peer.pendingCapsCharacter=m.at(0);
                    peer.pendingCaps=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                }
            }
            case "CAPS_ITEM" -> {
                m.size(2);
                synchronized (this) {
                    if(peer.pendingCaps==null || !m.at(0).equalsIgnoreCase(peer.pendingCapsCharacter)) {
                        peer.send("ERROR","Capability item outside character snapshot"); return;
                    }
                    String instrument=m.at(1).strip();
                    if(instrument.isEmpty() || instrument.length()>150 ||
                            instrument.contains("\n") || instrument.contains("\r") || peer.pendingCaps.size()>=100) {
                        peer.pendingCaps=null;
                        peer.send("ERROR","Invalid capability entry"); return;
                    }
                    peer.pendingCaps.add(instrument);
                }
            }
            case "CAPS_END" -> {
                m.size(1);
                synchronized (this) {
                    if(peer.pendingCaps==null || !m.at(0).equalsIgnoreCase(peer.pendingCapsCharacter)
                            || !roster.containsKey(key(peer.id,m.at(0)))) {
                        peer.send("ERROR","Incomplete capability snapshot"); return;
                    }
                    capabilities.put(key(peer.id,m.at(0)), Set.copyOf(peer.pendingCaps));
                    peer.pendingCaps=null;
                    peer.pendingCapsCharacter=null;
                }
                broadcastRoster();
            }
            case "STATUS" -> {
                m.size(3);
                synchronized (this) {
                    String key = key(peer.id, m.at(0));
                    CharacterInfo old = roster.get(key);
                    if (old != null) {
                        CommandStates state=commandStates.computeIfAbsent(key,k->new CommandStates());
                        state.bridgeReady(m.at(2).contains("sync ready"));
                        roster.put(key,new CharacterInfo(old.nodeId(),old.computer(),old.name(),
                                m.at(1).substring(0,Math.min(150,m.at(1).length())),old.window(),state.display()));
                    }
                }
                broadcastRoster();
            }
            case "ACTION" -> {
                if (m.fields().size() != 6 && m.fields().size() != 7)
                    throw new IOException("Expected six action fields, optionally plus a correlation ID");
                if (!peer.controller) { peer.send("ERROR", "Only the host may conduct"); return; }
                String owner = m.at(0), character = m.at(1), kind = m.at(2);
                String correlation = m.fields().size() == 7 ? m.at(6) : "";
                if (!correlation.isEmpty() && !CommandFocusTracker.validToken(correlation))
                    throw new IOException("Invalid action correlation ID");
                if (!Set.of("REPORT", "MUSIC", "SELECT", "PREPARE", "START", "FOLLOW", "CUSTOM", "EQUIP", "EQUIP_CHANGED", "EQUIP_RESET", "SPACE", "FOCUS").contains(kind)) { peer.send("ERROR", "Action not allowed"); return; }
                Peer target;
                synchronized (this) {
                    if (!roster.containsKey(key(owner, character))) {
                        peer.send("ERROR", "Target character not registered");
                        if (!correlation.isEmpty()) peer.send("EVENT", "coordinator", character,
                                "FAILED: character not registered", kind, correlation);
                        return;
                    }
                    target = peers.stream().filter(p -> p.id.equals(owner)).findFirst().orElse(null);
                }
                if (target != null) {
                    if (correlation.isEmpty()) target.send("DO", character, kind, m.at(3), m.at(4), m.at(5));
                    else target.send("DO", character, kind, m.at(3), m.at(4), m.at(5), correlation);
                } else {
                    peer.send("ERROR", "Owning computer disconnected for " + character);
                    // A missing agent cannot produce an acknowledgment: mark the tracked action failed.
                    if (!correlation.isEmpty()) peer.send("EVENT", "coordinator", character,
                            "FAILED: owning computer disconnected", kind, correlation);
                }
            }
            case "RESULT" -> {
                if (m.fields().size() != 3 && m.fields().size() != 4)
                    throw new IOException("Expected three result fields, optionally plus a correlation ID");
                String correlation = m.fields().size() == 4 ? m.at(3) : "";
                if (!correlation.isEmpty() && !CommandFocusTracker.validToken(correlation))
                    throw new IOException("Invalid result correlation ID");
                synchronized (this) {
                    String key=key(peer.id,m.at(0));
                    CharacterInfo old=roster.get(key);
                    if(old!=null && m.at(1).startsWith("INPUT SENT:")) {
                        if(m.at(2).equals("START")) {
                            // /playstart begins a new cycle for all musicians, not just the leader.
                            for(var entry:commandStates.entrySet())entry.getValue().resetPlayback();
                            for(var entry:roster.entrySet()) {
                                CharacterInfo member=entry.getValue();
                                CommandStates state=commandStates.get(entry.getKey());
                                if(state!=null)entry.setValue(new CharacterInfo(member.nodeId(),member.computer(),
                                        member.name(),member.instrument(),member.window(),state.display()));
                            }
                        } else {
                            CommandStates state=commandStates.computeIfAbsent(key,k->new CommandStates());
                            state.sent(m.at(2));
                            roster.put(key,new CharacterInfo(old.nodeId(),old.computer(),old.name(),
                                    old.instrument(),old.window(),state.display()));
                        }
                    }
                }
                broadcastRoster();
                for(Peer p:peers)if(p.controller) {
                    if (correlation.isEmpty()) p.send("EVENT",peer.name,m.at(0),m.at(1),m.at(2));
                    else p.send("EVENT",peer.name,m.at(0),m.at(1),m.at(2),correlation);
                }
            }
            default -> peer.send("ERROR", "Unknown message type: " + m.type());
        }
    }
    private static String key(String id, String name) { return id + "::" + name.toLowerCase(Locale.ROOT); }
    /** Stream a roster snapshot as bounded individual messages; avoids a hard roster-size cutoff. */
    private void broadcastRoster() {
        List<CharacterInfo> snapshot;
        Map<String,Set<String>> capSnapshot;
        long revision;
        synchronized (this) {
            snapshot = List.copyOf(roster.values());
            capSnapshot = new HashMap<>(capabilities);
            revision = ++rosterRevision;
        }
        for (Peer p : peers) {
            try { p.sendRoster(snapshot, capSnapshot, revision); }
            catch (IOException e) { System.err.println("Could not send roster to " + p.name + ": " + e); }
        }
    }
    @Override public void close() throws IOException { running = false; listener.close(); for (Peer p : peers) p.close(); }
    private static final class Peer {
        final String id, name; final boolean controller; final Writer writer;
        String pendingCapsCharacter;
        Set<String> pendingCaps;
        Peer(String id, String name, boolean controller, Writer writer) {
            this.id = id; this.name = name; this.controller = controller; this.writer = writer;
        }
        synchronized void send(String type, String... fields) throws IOException { Wire.write(writer, type, fields); }
        synchronized void sendRoster(List<CharacterInfo> snapshot, Map<String,Set<String>> caps, long revision) throws IOException {
            Wire.write(writer, "ROSTER_BEGIN", Integer.toString(snapshot.size()), Long.toString(revision));
            for (CharacterInfo c : snapshot) {
                Wire.write(writer, "ROSTER_ITEM", c.nodeId(), c.computer(), c.name(),
                        c.instrument(), c.window(), c.state());
            }
            for(CharacterInfo c: snapshot) {
                // Distinguish "no bindings configured" from "not yet reported".
                if(caps.containsKey(c.key())) Wire.write(writer,"ROSTER_CAPREADY",c.nodeId(),c.name());
                List<String> instruments = new ArrayList<>(caps.getOrDefault(c.key(), Set.of()));
                instruments.sort(String.CASE_INSENSITIVE_ORDER);
                for(String instrument: instruments)
                    Wire.write(writer, "ROSTER_CAP", c.nodeId(), c.name(), instrument);
            }
            Wire.write(writer, "ROSTER_END");
        }
        synchronized void close() { try { writer.close(); } catch (IOException ignored) { } }
    }
}
