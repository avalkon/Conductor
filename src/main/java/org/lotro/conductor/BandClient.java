package org.lotro.conductor;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Persistent agent/coordinator connection; messages received in a daemon reader thread. */
public final class BandClient implements AutoCloseable {
    public interface Listener { void received(Wire.Message message); void disconnected(String explanation); }
    private final Socket socket = new Socket();
    private final String id = UUID.randomUUID().toString();
    private final Reader reader;
    private final Writer writer;
    private final Listener listener;
    private volatile boolean closed;
    public BandClient(String hostname, int port, String name, String key, boolean controller, Listener listener) throws Exception {
        this.listener = listener;
        socket.connect(new InetSocketAddress(hostname, port), 5000);
        socket.setSoTimeout(8000);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        Wire.Message challenge = Wire.decode(Wire.read(reader));
        challenge.size(1);
        if (!challenge.type().equals("CHALLENGE")) throw new IOException("Server did not send authentication challenge");
        String role = controller ? "controller" : "agent";
        Wire.write(writer, "AUTH", id, name, role, Auth.response(key, challenge.at(0), id, role));
        Wire.Message welcome = Wire.decode(Wire.read(reader));
        if (!welcome.type().equals("WELCOME")) throw new IOException("Connection refused by host");
        socket.setSoTimeout(0);
        Thread receiver = new Thread(this::readLoop, "conductor-receiver");
        receiver.setDaemon(true); receiver.start();
    }
    public String id() { return id; }
    public synchronized void send(String type, String... fields) throws IOException {
        if (closed) throw new IOException("Not connected");
        Wire.write(writer, type, fields);
    }
    private void readLoop() {
        String reason = "Connection closed";
        try {
            String line;
            while ((line = Wire.read(reader)) != null) {
                Wire.Message message = Wire.decode(line);
                listener.received(message);
            }
        } catch (Exception e) { reason = e.getMessage(); }
        finally {
            closed = true;
            listener.disconnected(reason);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
    @Override public void close() throws IOException { closed = true; socket.close(); }
}
