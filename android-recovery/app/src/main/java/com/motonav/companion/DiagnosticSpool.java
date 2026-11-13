package com.motonav.companion;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Bounded durable outbox. Acknowledgements are persisted before records leave memory. */
public final class DiagnosticSpool {
    public record Entry(String session, long sequence, String line) {}
    private static final int MAX_ENTRIES = 4096, MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern HEADER = Pattern.compile("^\\{\"schema\":1,\"session\":\"([0-9a-f]{32})\",\"seq\":([1-9][0-9]*),.*}$");
    private final File file;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> identities = new HashSet<>();
    private int bytes, dropped;

    public DiagnosticSpool(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("diagnostic_storage");
        file = new File(directory, "pending-events.journal");
        if (file.isFile()) {
            if (file.length() > 2L * MAX_BYTES) throw new IOException("diagnostic_storage_limit");
            try (BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String value;
                while ((value = input.readLine()) != null) {
                    if (value.length() < 66 || value.charAt(64) != ' ') continue;
                    String line = value.substring(65);
                    if (!digest(line).equals(value.substring(0,64))) continue;
                    Entry entry = parse(line);
                    if (entry != null && identities.add(identity(entry))) {
                        entries.add(entry); bytes += encodedSize(entry);
                    }
                }
            }
        }
        trim();
        // Repair a torn final append before another record can be appended to it.
        persist(entries);
    }
    public synchronized void append(String line, boolean sync) throws IOException {
        Entry entry = parse(line);
        if (entry == null) throw new IOException("diagnostic_record");
        if (identities.contains(identity(entry))) return;
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(encoded(entry));
            if (sync) output.getFD().sync();
        }
        entries.add(entry); identities.add(identity(entry)); bytes += encodedSize(entry);
        if (entries.size() > MAX_ENTRIES || bytes > MAX_BYTES) { trim(); persist(entries); }
    }
    public synchronized List<Entry> batch(int maximum, int maxBytes) {
        return batch(null, maximum, maxBytes);
    }
    public synchronized List<Entry> batch(String preferredSession, int maximum, int maxBytes) {
        List<Entry> batch = new ArrayList<>();
        if (entries.isEmpty()) return batch;
        String first = preferredSession != null && entries.stream().anyMatch(e -> e.session.equals(preferredSession))
                ? preferredSession : entries.get(0).session;
        List<Entry> session = entries.stream().filter(e -> e.session.equals(first))
                .sorted(Comparator.comparingLong(Entry::sequence)).collect(java.util.stream.Collectors.toList());
        int size = 0;
        for (Entry entry : session) {
            int next = entry.line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (batch.size() >= maximum || size + next > maxBytes) break;
            batch.add(entry); size += next;
        }
        return batch;
    }
    public synchronized void acknowledge(String session, long sequence) throws IOException {
        List<Entry> remaining = new ArrayList<>(entries);
        remaining.removeIf(e -> e.session.equals(session) && e.sequence <= sequence);
        persist(remaining);
        entries.clear(); entries.addAll(remaining);
        identities.clear(); for (Entry entry : entries) identities.add(identity(entry));
        bytes = entries.stream().mapToInt(DiagnosticSpool::encodedSize).sum();
    }
    public synchronized int size() { return entries.size(); }
    public synchronized int dropped() { return dropped; }
    private void trim() {
        while (!entries.isEmpty() && (entries.size() > MAX_ENTRIES || bytes > MAX_BYTES)) {
            Entry removed = entries.remove(0); identities.remove(identity(removed));
            bytes -= encodedSize(removed); dropped++;
        }
    }
    private void persist(List<Entry> snapshot) throws IOException {
        File temporary = new File(file.getParentFile(), "pending-events.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            for (Entry entry : snapshot) output.write(encoded(entry));
            output.getFD().sync();
        }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
    private static Entry parse(String line) {
        if (line == null || line.length() > 4096 || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) return null;
        Matcher match = HEADER.matcher(line);
        if (!match.matches()) return null;
        try { return new Entry(match.group(1), Long.parseLong(match.group(2)), line); }
        catch (NumberFormatException e) { return null; }
    }
    private static int encodedSize(Entry entry) { return entry.line.getBytes(StandardCharsets.UTF_8).length + 66; }
    private static String identity(Entry entry) { return entry.session + ":" + entry.sequence; }
    private static byte[] encoded(Entry entry) { return (digest(entry.line) + " " + entry.line + "\n").getBytes(StandardCharsets.UTF_8); }
    private static String digest(String line) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (byte value : bytes) text.append(Character.forDigit((value >>> 4) & 15,16)).append(Character.forDigit(value & 15,16));
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
