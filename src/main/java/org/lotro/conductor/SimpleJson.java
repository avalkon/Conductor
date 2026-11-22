package org.lotro.conductor;

import java.io.IOException;
import java.util.*;

/** Small bounded JSON reader for the offline Conductor setlist format (no runtime dependencies). */
final class SimpleJson {
    private final String s;
    private int p;
    private SimpleJson(String text) { s=text; }
    static Object parse(String text) throws IOException {
        if (text.length() > 4_000_000) throw new IOException("Setlist JSON is too large");
        SimpleJson j = new SimpleJson(text);
        Object value = j.value(0);
        j.ws(); if (j.p != j.s.length()) throw j.bad("Unexpected trailing content");
        return value;
    }
    private IOException bad(String message) { return new IOException(message + " at position " + p); }
    private void ws() { while (p<s.length() && Character.isWhitespace(s.charAt(p))) p++; }
    private Object value(int depth) throws IOException {
        if (depth > 35) throw bad("JSON is too deeply nested");
        ws(); if (p>=s.length()) throw bad("Incomplete JSON");
        char c=s.charAt(p);
        if (c=='"') return string();
        if (c=='{') {
            p++; Map<String,Object> map=new LinkedHashMap<>(); ws();
            if (take('}')) return map;
            while (true) {
                ws(); if (p>=s.length() || s.charAt(p)!='"') throw bad("Object key expected");
                String k=string(); ws(); expect(':');
                Object v=value(depth+1);
                if (map.containsKey(k)) throw bad("Duplicate JSON field: "+k);
                map.put(k,v);
                ws(); if(take('}')) return map; expect(',');
            }
        }
        if (c=='[') {
            p++; List<Object> list=new ArrayList<>(); ws(); if(take(']')) return list;
            while(true) {
                if (list.size()>10_000) throw bad("JSON array too large");
                list.add(value(depth+1)); ws(); if(take(']')) return list; expect(',');
            }
        }
        if (s.startsWith("true",p)) {p+=4; return Boolean.TRUE;}
        if (s.startsWith("false",p)) {p+=5; return Boolean.FALSE;}
        if (s.startsWith("null",p)) {p+=4; return null;}
        int start=p;
        if (c=='-') p++;
        while(p<s.length() && Character.isDigit(s.charAt(p))) p++;
        if (start==p || (s.charAt(start)=='-' && p==start+1)) throw bad("Invalid JSON value");
        // The versioned setlist only needs integral track IDs.
        try {return Long.parseLong(s.substring(start,p));}catch(NumberFormatException e){throw bad("Invalid integer");}
    }
    private void expect(char c) throws IOException { ws(); if (!take(c)) throw bad("Expected '"+c+"'"); }
    private boolean take(char c) {if(p<s.length() && s.charAt(p)==c){p++;return true;}return false;}
    private String string() throws IOException {
        if(!take('"')) throw bad("Expected string");
        StringBuilder b=new StringBuilder();
        while(p<s.length()) {
            char c=s.charAt(p++);
            if(c=='"') return b.toString();
            if(c<' ') throw bad("Unescaped control character");
            if(c!='\\') { b.append(c); continue; }
            if(p>=s.length()) throw bad("Incomplete escape");
            switch(c=s.charAt(p++)) {
                case '"','\\','/' -> b.append(c);
                case 'n' -> b.append('\n'); case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                case 'u' -> {
                    if(p+4>s.length()) throw bad("Invalid Unicode escape");
                    try {b.append((char)Integer.parseInt(s.substring(p,p+4),16));}
                    catch(NumberFormatException e){throw bad("Invalid Unicode escape");}
                    p+=4;
                }
                default -> throw bad("Unknown escape");
            }
            if(b.length()>1_000_000) throw bad("JSON string is too large");
        }
        throw bad("Unterminated string");
    }
}
