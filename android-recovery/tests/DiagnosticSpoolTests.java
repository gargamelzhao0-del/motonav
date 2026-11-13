import com.motonav.companion.DiagnosticSpool;
import com.motonav.companion.DiagnosticRecord;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DiagnosticSpoolTests {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError(checks); }
    static String line(String session,long sequence) { return DiagnosticRecord.line(session,sequence,sequence,"user_mark","reason","reported"); }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("motonav-spool-test-");
        String first="a".repeat(32),second="b".repeat(32);
        try {
            DiagnosticSpool spool = new DiagnosticSpool(root.toFile());
            spool.append(line(first,1),false); spool.append(line(first,3),true); spool.append(line(first,2),false);
            spool.append(line(second,1),false);
            List<DiagnosticSpool.Entry> batch=spool.batch(24,32768);
            check(batch.size()==3); check(batch.get(0).sequence()==1); check(batch.get(2).sequence()==3);
            check(spool.batch(second,24,32768).get(0).session().equals(second));
            check(spool.size()==4); // Prioritize live diagnostics without discarding older sessions.
            check(spool.batch("c".repeat(32),24,32768).get(0).session().equals(first));
            spool = new DiagnosticSpool(root.toFile());
            check(spool.size()==4); // A process exit never erased unacknowledged data.
            spool.acknowledge(first,2);
            spool=new DiagnosticSpool(root.toFile());check(spool.size()==2);check(spool.batch(24,32768).get(0).sequence()==3);
            spool.acknowledge(first,3); check(spool.batch(24,32768).get(0).session().equals(second));
            spool.append(line(second,1),true); check(spool.size()==1);
            Files.writeString(root.resolve("pending-events.journal"),"torn write without newline",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
            spool=new DiagnosticSpool(root.toFile());check(spool.size()==1);
            spool.append(line(second,2),true);spool=new DiagnosticSpool(root.toFile());check(spool.size()==2);
            check(spool.batch(1,32768).size()==1); check(spool.batch(24,1).isEmpty());
            spool.acknowledge(second,2);check(new DiagnosticSpool(root.toFile()).size()==0);
            for(int i=1;i<=4200;i++)spool.append(line(first,i),false);
            check(spool.size()==4096);check(spool.dropped()==104);
            check(new DiagnosticSpool(root.toFile()).batch(1,32768).get(0).sequence()==105);
            System.out.println("DiagnosticSpool: "+checks+" durable replay, ACK, corruption and capacity checks passed");
        } finally {
            try(var files=Files.list(root)){for(Path path:files.collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(path);}
            Files.deleteIfExists(root);
        }
    }
}
