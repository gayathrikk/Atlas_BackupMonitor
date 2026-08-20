package anchor.sanity;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import com.jcraft.jsch.*;
import com.jcraft.jsch.Session;

import javax.mail.*;
import javax.mail.internet.*;
import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class AtlasFlatTree_monitor {

    private static final String BACKUP_BASE = "/home/projects/developers/store/repos1/iitlab/humanbrain/analytics/backup_atlas";
    private static final String NISL_SUBPATH = "294/appData/atlasEditor/189/NISL";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd_MM_yyyy");

    private static final String SSH_HOST = "qd1.humanbrain.in";
    private static final int SSH_PORT = 22;
    private static final String SSH_USER = "hemanand";
    private static final String SSH_PASSWORD = "Hem@123";

    private static final int DISK_THRESHOLD = 80;

    private Session sshSession;
    private LocalDate today;

    // Results
    private boolean machineUp = false;
    private boolean todayBackupFound = false;
    private boolean diskAlert = false;
    private int diskUsedPct = 0;
    private String latestBackupName = "";
    private String prevBackupName = "";
    private int latestTotal = 0, latestEmpty = 0, latestGood = 0;
    private int prevTotal = 0, prevEmpty = 0, prevGood = 0;
    private List<String> corruptedSections = new ArrayList<>();
    private List<String> missingInLatest = new ArrayList<>();
    private List<String> failedTests = new ArrayList<>();

    @BeforeClass
    public void setup() throws Exception {
        today = LocalDate.now();

        JSch jsch = new JSch();
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("MaxSessions", "10");

        sshSession = jsch.getSession(SSH_USER, SSH_HOST, SSH_PORT);
        sshSession.setPassword(SSH_PASSWORD);
        sshSession.setConfig(config);
        sshSession.setTimeout(30000);
        sshSession.connect();
        machineUp = true;
        System.out.println("SSH connected to " + SSH_HOST);
    }

    @AfterClass
    public void teardown() {
        System.out.println("\n==================== ATLAS BACKUP MONITOR REPORT (" + today + ") ====================");
        System.out.println("  1. Machine       : " + (machineUp ? "UP" : "DOWN !!"));
        System.out.println("  2. Disk Usage     : " + diskUsedPct + "%" + (diskAlert ? " !! CRITICAL (>" + DISK_THRESHOLD + "%)" : " (OK)"));
        System.out.println("  3. Today Backup   : " + (todayBackupFound ? "YES" : "NOT FOUND !!"));
        System.out.println("  4. Latest Backup  : " + (latestBackupName.isEmpty() ? "NONE" : latestBackupName));

        if (!latestBackupName.isEmpty() && !prevBackupName.isEmpty()) {
            System.out.println("  5. Data Integrity :");
            System.out.println("     Current  (" + latestBackupName + "): Total=" + latestTotal + " Empty=" + latestEmpty + " Good=" + latestGood);
            System.out.println("     Previous (" + prevBackupName + "): Total=" + prevTotal + " Empty=" + prevEmpty + " Good=" + prevGood);
            if (corruptedSections.isEmpty() && missingInLatest.isEmpty()) {
                System.out.println("     Status: ALL OK");
            } else {
                if (!corruptedSections.isEmpty())
                    System.out.println("     CORRUPTED (" + corruptedSections.size() + "): " + String.join(", ", corruptedSections));
                if (!missingInLatest.isEmpty())
                    System.out.println("     MISSING  (" + missingInLatest.size() + "): " + String.join(", ", missingInLatest));
            }
        }

        System.out.println("  -------------------------------------------------------------------------");

        // Send email ONLY if there are failures
        if (!failedTests.isEmpty()) {
            System.out.println("  FAILED TESTS: " + String.join(", ", failedTests));
            System.out.println("  Email: Sending alert...");
            sendAlertEmail();
        } else {
            System.out.println("  ALL TESTS PASSED");
            System.out.println("  Email: Not sent (all OK)");
        }
        System.out.println("=================================================================================\n");

        if (sshSession != null && sshSession.isConnected()) {
            sshSession.disconnect();
            System.out.println("SSH disconnected");
        }
    }

    // ==================== SSH ====================

    private String runCmd(String command) throws Exception {
        int retries = 3;
        for (int i = 0; i < retries; i++) {
            try {
                if (sshSession == null || !sshSession.isConnected()) {
                    JSch jsch = new JSch();
                    Properties config = new Properties();
                    config.put("StrictHostKeyChecking", "no");
                    sshSession = jsch.getSession(SSH_USER, SSH_HOST, SSH_PORT);
                    sshSession.setPassword(SSH_PASSWORD);
                    sshSession.setConfig(config);
                    sshSession.setTimeout(30000);
                    sshSession.connect();
                }

                ChannelExec ch = (ChannelExec) sshSession.openChannel("exec");
                ch.setCommand(command);
                ch.setInputStream(null);
                ch.setErrStream(System.err);
                InputStream in = ch.getInputStream();
                ch.connect(10000);

                StringBuilder out = new StringBuilder();
                byte[] buf = new byte[4096];
                while (true) {
                    while (in.available() > 0) {
                        int len = in.read(buf, 0, 4096);
                        if (len < 0) break;
                        out.append(new String(buf, 0, len));
                    }
                    if (ch.isClosed()) {
                        if (in.available() > 0) continue;
                        break;
                    }
                    Thread.sleep(500);
                }
                ch.disconnect();
                Thread.sleep(300);
                return out.toString().trim();

            } catch (Exception e) {
                System.out.println("  SSH retry " + (i + 1) + "/" + retries + ": " + e.getMessage());
                if (sshSession != null && sshSession.isConnected()) {
                    sshSession.disconnect();
                }
                sshSession = null;
                Thread.sleep(2000);
                if (i == retries - 1) throw e;
            }
        }
        return "";
    }

    // ==================== HELPERS ====================

    private String getBackupDir(int index) throws Exception {
        String cmd = "for d in " + BACKUP_BASE + "/backup_[0-9]*; do "
                + "name=$(basename $d); "
                + "dt=$(echo $name | sed 's/backup_//' | awk -F_ '{print $3$2$1}'); "
                + "echo \"$dt|$name\"; "
                + "done 2>/dev/null | sort -t'|' -k1 | cut -d'|' -f2";
        String result = runCmd(cmd);
        if (result.isEmpty()) return null;

        String[] dirs = result.split("\n");
        if (dirs.length <= index) return null;
        return dirs[dirs.length - 1 - index].trim();
    }

    private Map<String, Long> parseFlatTree(String output) {
        Map<String, Long> map = new LinkedHashMap<>();
        if (output == null || output.isEmpty()) return map;

        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("|")) continue;
            try {
                String[] parts = line.split("\\|", 2);
                long size = Long.parseLong(parts[0].trim());
                String path = parts[1].trim();
                int idx = path.indexOf("/NISL/");
                if (idx < 0) continue;
                String section = path.substring(idx + 6).split("/")[0];
                map.put(section, size);
            } catch (Exception e) {
                // skip
            }
        }
        return map;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    // ==================== TEST 1: MACHINE UP + DISK CHECK ====================

    @Test(priority = 1, description = "Check if qd1 is reachable and disk usage below 80%")
    public void testMachineUp() throws Exception {
        System.out.println("\n--- Test 1: Machine Health Check ---\n");

        String uptime = runCmd("uptime");
        System.out.println("  Host   : " + SSH_HOST);
        System.out.println("  Uptime : " + uptime);

        String diskFree = runCmd("df -h " + BACKUP_BASE + " | tail -1 | awk '{print $4\" free (\"$5\" used)\"}'");
        System.out.println("  Disk   : " + diskFree);

        String diskPct = runCmd("df " + BACKUP_BASE + " | tail -1 | awk '{print $5}' | tr -d '%'");
        diskUsedPct = Integer.parseInt(diskPct.trim());
        System.out.println("  Usage  : " + diskUsedPct + "%");

        machineUp = true;

        if (diskUsedPct >= DISK_THRESHOLD) {
            diskAlert = true;
            failedTests.add("Disk Usage " + diskUsedPct + "% (>=" + DISK_THRESHOLD + "%)");
            System.out.println("  !! CRITICAL: Disk usage " + diskUsedPct + "% exceeds " + DISK_THRESHOLD + "% threshold!");
            Assert.fail("Disk usage critical: " + diskUsedPct + "% (threshold: " + DISK_THRESHOLD + "%)");
        } else {
            System.out.println("  Result : OK (below " + DISK_THRESHOLD + "% threshold)");
        }
    }

    // ==================== TEST 2: BACKUP EXISTS ====================

    @Test(priority = 2, description = "Check if today's backup was taken")
    public void testBackupExistsToday() throws Exception {
        System.out.println("\n--- Test 2: Daily Backup Check ---\n");

        String todayDir = "backup_" + today.format(FMT);
        String todayPath = BACKUP_BASE + "/" + todayDir;
        String exists = runCmd("test -d " + todayPath + " && echo YES || echo NO");

        if ("YES".equals(exists)) {
            todayBackupFound = true;
            latestBackupName = todayDir;
            System.out.println("  Today's backup : " + todayDir + " -> EXISTS");

            String nislExists = runCmd("test -d " + todayPath + "/" + NISL_SUBPATH + " && echo YES || echo NO");
            System.out.println("  NISL folder    : " + nislExists);
            System.out.println("  Result         : OK");
        } else {
            System.out.println("  Today's backup : " + todayDir + " -> NOT FOUND!");
            System.out.println("\n  Recent backups:");

            String recent = runCmd("for d in " + BACKUP_BASE + "/backup_[0-9]*; do "
                    + "name=$(basename $d); "
                    + "dt=$(echo $name | sed 's/backup_//' | awk -F_ '{print $3$2$1}'); "
                    + "echo \"$dt|$name\"; "
                    + "done 2>/dev/null | sort -t'|' -k1 | tail -5 | cut -d'|' -f2");
            if (!recent.isEmpty()) {
                for (String name : recent.split("\n")) {
                    System.out.println("    " + name.trim());
                }
            }

            // Use latest available for Test 3
            latestBackupName = getBackupDir(0);
            if (latestBackupName != null) {
                System.out.println("\n  Latest available: " + latestBackupName);
            }

            failedTests.add("Backup not found for " + todayDir);
            Assert.fail("Backup not taken for today (" + todayDir + ")! Latest: " + latestBackupName);
        }
    }

    // ==================== TEST 3: DATA INTEGRITY ====================

    @Test(priority = 3, alwaysRun = true,
            description = "Compare latest 2 backups - files that had data but now 2 bytes (empty)")
    public void testBackupDataIntegrity() throws Exception {
        System.out.println("\n--- Test 3: Backup Data Integrity Check ---\n");

        // Always get latest 2 backups (don't depend on Test 2)
        if (latestBackupName == null || latestBackupName.isEmpty()) {
            latestBackupName = getBackupDir(0);
        }
        prevBackupName = getBackupDir(1);

        if (latestBackupName == null || prevBackupName == null) {
            System.out.println("  SKIPPED: Need at least 2 backups to compare");
            return;
        }

        System.out.println("  Current  : " + latestBackupName);
        System.out.println("  Previous : " + prevBackupName);
        System.out.println();

        String latestPath = BACKUP_BASE + "/" + latestBackupName + "/" + NISL_SUBPATH;
        String prevPath = BACKUP_BASE + "/" + prevBackupName + "/" + NISL_SUBPATH;

        Map<String, Long> latestMap = parseFlatTree(
                runCmd("find " + latestPath + " -maxdepth 2 -name '*FlatTree*' -printf '%s|%p\\n' 2>/dev/null"));
        Map<String, Long> prevMap = parseFlatTree(
                runCmd("find " + prevPath + " -maxdepth 2 -name '*FlatTree*' -printf '%s|%p\\n' 2>/dev/null"));

        latestTotal = latestMap.size();
        latestEmpty = (int) latestMap.values().stream().filter(s -> s <= 2).count();
        latestGood = latestTotal - latestEmpty;

        prevTotal = prevMap.size();
        prevEmpty = (int) prevMap.values().stream().filter(s -> s <= 2).count();
        prevGood = prevTotal - prevEmpty;

        System.out.println("  FlatTree Stats:");
        System.out.println("    Current  : Total=" + latestTotal + " | Empty(2B)=" + latestEmpty + " | Good=" + latestGood);
        System.out.println("    Previous : Total=" + prevTotal + " | Empty(2B)=" + prevEmpty + " | Good=" + prevGood);
        System.out.println();

        // CHECK 1: Had data -> now 2B empty = CORRUPTED
        System.out.println("  [Check 1] Had data -> Now 2B empty");
        for (Map.Entry<String, Long> entry : prevMap.entrySet()) {
            String sec = entry.getKey();
            long prevSize = entry.getValue();
            if (prevSize > 2 && latestMap.containsKey(sec) && latestMap.get(sec) <= 2) {
                corruptedSections.add(sec);
                System.out.println("    !! Section " + sec + " : " + formatSize(prevSize) + " -> 2B (EMPTY)");
            }
        }
        System.out.println("    Result: " + (corruptedSections.isEmpty() ? "OK - No corruption" : corruptedSections.size() + " CORRUPTED!"));

        // CHECK 2: Section existed but now missing
        System.out.println("\n  [Check 2] Missing sections");
        for (String sec : prevMap.keySet()) {
            if (prevMap.get(sec) > 2 && !latestMap.containsKey(sec)) {
                missingInLatest.add(sec);
                System.out.println("    !! Section " + sec + " : " + formatSize(prevMap.get(sec)) + " -> GONE");
            }
        }
        System.out.println("    Result: " + (missingInLatest.isEmpty() ? "OK - No missing" : missingInLatest.size() + " MISSING!"));

        // CHECK 3: Good count trend
        System.out.println("\n  [Check 3] Good count trend");
        int diff = latestGood - prevGood;
        System.out.println("    " + prevGood + " -> " + latestGood + " (" + (diff >= 0 ? "+" : "") + diff + ")");

        // ASSERT
        boolean allOk = corruptedSections.isEmpty() && missingInLatest.isEmpty();
        if (!allOk) {
            StringBuilder msg = new StringBuilder();
            if (!corruptedSections.isEmpty())
                msg.append(corruptedSections.size()).append(" corrupted [").append(String.join(",", corruptedSections)).append("] ");
            if (!missingInLatest.isEmpty())
                msg.append(missingInLatest.size()).append(" missing [").append(String.join(",", missingInLatest)).append("]");
            failedTests.add("Data integrity: " + msg.toString());
            Assert.fail(msg.toString());
        }
    }

    // ==================== EMAIL ====================

    private void sendAlertEmail() {
        String[] to = {"venip@htic.iitm.ac.in"};
        String[] cc = {"divya.d@htic.iitm.ac.in"};
        String from = "automationsoftware25@gmail.com";

        Properties props = System.getProperties();
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "465");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.auth", "true");

        javax.mail.Session mailSession = javax.mail.Session.getInstance(props, new javax.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
            	return new PasswordAuthentication("automationsoftware25@gmail.com", "cbsi opyo vcrw yblp");            }
        });

        try {
            MimeMessage msg = new MimeMessage(mailSession);
            msg.setFrom(new InternetAddress(from));
            for (String r : to) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(r));
            for (String r : cc) msg.addRecipient(Message.RecipientType.CC, new InternetAddress(r));

            msg.setSubject("ALERT: Atlas Backup Issue - " + today);

            StringBuilder body = new StringBuilder();
            body.append("<div style='font-family:Arial; max-width:700px;'>");

            // Header
            body.append("<div style='background:#d32f2f; color:white; padding:15px; border-radius:8px 8px 0 0;'>");
            body.append("<h2 style='margin:0;'>Atlas Backup Monitor Alert</h2>");
            body.append("<p style='margin:5px 0 0;'>").append(today).append(" | ").append(SSH_HOST).append("</p>");
            body.append("</div>");

            body.append("<div style='border:1px solid #ddd; padding:20px; border-radius:0 0 8px 8px;'>");

            // Failed tests
            body.append("<h3 style='color:red;'>Failed Checks</h3>");
            body.append("<ul>");
            for (String f : failedTests) {
                body.append("<li style='color:red;'>").append(f).append("</li>");
            }
            body.append("</ul>");

            // Status table
            body.append("<h3>Full Status</h3>");
            body.append("<table border='1' cellpadding='10' cellspacing='0' style='border-collapse:collapse; width:100%;'>");
            body.append("<tr style='background:#f5f5f5;'><th>Check</th><th>Status</th><th>Details</th></tr>");

            addRow(body, "Machine Up", machineUp, machineUp ? "Reachable" : "DOWN!");
            addRow(body, "Disk Usage", !diskAlert, diskUsedPct + "%" + (diskAlert ? " (CRITICAL!)" : ""));
            addRow(body, "Today Backup", todayBackupFound, todayBackupFound ? "backup_" + today.format(FMT) : "NOT FOUND");
            boolean intOk = corruptedSections.isEmpty() && missingInLatest.isEmpty();
            addRow(body, "Data Integrity", intOk, intOk ? "All OK"
                    : corruptedSections.size() + " corrupted, " + missingInLatest.size() + " missing");
            body.append("</table>");

            // FlatTree stats
            if (!latestBackupName.isEmpty() && !prevBackupName.isEmpty()) {
                body.append("<h3>FlatTree Comparison</h3>");
                body.append("<table border='1' cellpadding='8' cellspacing='0' style='border-collapse:collapse; width:100%;'>");
                body.append("<tr style='background:#f5f5f5;'><th>Backup</th><th>Total</th><th>Empty(2B)</th><th>With Data</th></tr>");
                body.append("<tr><td>").append(latestBackupName).append("</td><td>").append(latestTotal)
                        .append("</td><td>").append(latestEmpty).append("</td><td>").append(latestGood).append("</td></tr>");
                body.append("<tr><td>").append(prevBackupName).append("</td><td>").append(prevTotal)
                        .append("</td><td>").append(prevEmpty).append("</td><td>").append(prevGood).append("</td></tr>");
                body.append("</table>");
            }

            // Corrupted sections detail
            if (!corruptedSections.isEmpty()) {
                body.append("<h3 style='color:red;'>Corrupted Sections (data -> 2B empty)</h3>");
                body.append("<p>Sections: <b>").append(String.join(", ", corruptedSections)).append("</b></p>");
                body.append("<p><i>Restore from ").append(prevBackupName).append("</i></p>");
            }

            if (!missingInLatest.isEmpty()) {
                body.append("<h3 style='color:red;'>Missing Sections</h3>");
                body.append("<p>Sections: <b>").append(String.join(", ", missingInLatest)).append("</b></p>");
            }

            body.append("<hr><p style='color:gray; font-size:11px;'>Atlas Backup Monitor</p>");
            body.append("</div></div>");

            msg.setContent(body.toString(), "text/html");
            Transport.send(msg);
            System.out.println("  Email sent successfully!");

        } catch (MessagingException e) {
            System.out.println("  EMAIL SEND FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void addRow(StringBuilder body, String check, boolean ok, String details) {
        body.append("<tr><td>").append(check).append("</td>")
                .append("<td style='color:").append(ok ? "green" : "red").append("; font-weight:bold;'>")
                .append(ok ? "PASS" : "FAIL").append("</td>")
                .append("<td>").append(details).append("</td></tr>");
    }
}