package org.lotro.conductor;

/** User-configurable LOTRO slash-chat command, never passed to a system shell. */
public final class CommandValidator {
    private CommandValidator() { }
    public static String validOrEmpty(String text) {
        if(text==null || text.isBlank())return "";
        String value=text.strip();
        if(!value.startsWith("/") || value.length()>200 || value.length()<2)
            throw new IllegalArgumentException("Enter one LOTRO slash command (for example /bow), up to 200 characters");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isISOControl(c))throw new IllegalArgumentException("Command cannot contain newlines or control characters");
        }
        return value;
    }
    public static String requireCommand(String value) {
        String validated=validOrEmpty(value);
        if(validated.isEmpty())throw new IllegalArgumentException("Custom command is not configured");
        return validated;
    }
}
