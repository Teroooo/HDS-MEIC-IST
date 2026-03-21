package pt.depchain.tests;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Classe base com helper partilhado para testes E2E bizantinos.
 * Cada teste concreto estende esta classe num ficheiro separado,
 * para que o Surefire os corra em forks isolados (sem conflitos de portas).
 */
public abstract class ByzantineTestBase {

    protected static final long CLIENT_STARTUP_MS = 4000;
    protected static final long CONSENSUS_TIMEOUT_MS = 30000;
    protected static final long NODE_READY_TIMEOUT_MS = 30000;

    protected boolean waitForNodesReady(List<StringBuilder> nodeOutputs, int count) throws Exception {
        long deadline = System.currentTimeMillis() + NODE_READY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int ready = 0;
            for (int i = 0; i < count; i++) {
                String output;
                synchronized (nodeOutputs.get(i)) { output = nodeOutputs.get(i).toString(); }
                if (output.contains("Starting consensus") || output.contains("initialized")) {
                    ready++;
                }
            }
            if (ready >= count) return true;
            Thread.sleep(500);
        }
        return false;
    }

    /** Kill any leftover processes occupying the node/client UDP ports. */
    private void freeAllPorts() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            // PowerShell one-liner: find all PIDs listening on our ports and kill them
            try {
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-NetUDPEndpoint -LocalPort 9001,9002,9003,9004,4001,4002 -ErrorAction SilentlyContinue" +
                    " | Select-Object -ExpandProperty OwningProcess -Unique" +
                    " | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }")
                    .redirectErrorStream(true).start();
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        } else {
            int[] ports = {9001, 9002, 9003, 9004, 4001, 4002};
            for (int port : ports) {
                try {
                    new ProcessBuilder("fuser", "-k", port + "/udp")
                        .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            }
        }
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
    }

    protected void runByzantineScenario(Map<Integer, String> byzantineNodes,
                                        String testString,
                                        boolean expectConsensus) throws Exception {
        // Kill any leftover processes from a previous test
        freeAllPorts();

        List<Process> nodes = new ArrayList<>();
        List<StringBuilder> nodeOutputs = new ArrayList<>();
        Process client = null;

        try {
            for (int i = 1; i <= 4; i++) {
                ProcessBuilder pb;
                if (byzantineNodes.containsKey(i)) {
                    String attack = byzantineNodes.get(i);
                    pb = new ProcessBuilder(
                        "mvn", "exec:java",
                        "-Dexec.mainClass=pt.depchain.service.ByzantineNode",
                        "-Dexec.args=" + i +
                        " ../config/node" + i + ".priv" +
                        " ../config/node" + i + ".pub" +
                        " " + attack
                    );
                } else {
                    pb = new ProcessBuilder(
                        "mvn", "exec:java",
                        "-Dexec.mainClass=pt.depchain.service.Node",
                        "-Dexec.args=" + i +
                        " ../config/node" + i + ".priv" +
                        " ../config/node" + i + ".pub"
                    );
                }
                pb.redirectErrorStream(true);
                Process node = pb.start();
                nodes.add(node);

                StringBuilder sb = new StringBuilder();
                nodeOutputs.add(sb);
                final int nodeId = i;
                final Process p = node;
                new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (sb) { sb.append(line).append("\n"); }
                            System.out.println("[NODE-" + nodeId + "] " + line);
                        }
                    } catch (IOException ignored) {}
                }, "reader-node-" + i).start();
            }

            boolean nodesReady = waitForNodesReady(nodeOutputs, 4);
            // Extra stabilization: give sockets time to fully bind after log appears
            Thread.sleep(2000);
            if (!nodesReady) {
                System.out.println("[TEST] WARNING: Not all nodes started in time.");
                for (int i = 0; i < 4; i++) {
                    synchronized (nodeOutputs.get(i)) {
                        System.out.println("[TEST] Node " + (i+1) + " output:\n" + nodeOutputs.get(i));
                    }
                }
                if (expectConsensus) {
                    fail("Nodes failed to start - possible port conflict");
                }
                return;
            }

            ProcessBuilder pbClient = new ProcessBuilder(
                "mvn", "exec:java",
                "-Dexec.mainClass=pt.depchain.client.ClientMain",
                "-Dexec.args=client1" +
                " ../config/client1.priv" +
                " ../config/client1.pub"
            );
            pbClient.redirectErrorStream(true);
            client = pbClient.start();

            final Process cp = client;
            StringBuilder clientOutput = new StringBuilder();
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(cp.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (clientOutput) { clientOutput.append(line).append("\n"); }
                        System.out.println("[CLIENT] " + line);
                    }
                } catch (IOException ignored) {}
            }, "reader-client").start();

            Thread.sleep(CLIENT_STARTUP_MS);

            BufferedWriter clientWriter = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
            clientWriter.write("1\n");
            clientWriter.flush();
            Thread.sleep(300);
            clientWriter.write(testString + "\n");
            clientWriter.flush();

            long deadline = System.currentTimeMillis() + CONSENSUS_TIMEOUT_MS;
            boolean found = false;

            while (System.currentTimeMillis() < deadline) {
                for (int i = 0; i < 4; i++) {
                    if (byzantineNodes.containsKey(i + 1)) continue;
                    String output;
                    synchronized (nodeOutputs.get(i)) { output = nodeOutputs.get(i).toString(); }
                    if (output.contains(testString) && output.contains("Decision reached")) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
                Thread.sleep(500);
            }

            if (expectConsensus) {
                assertTrue(found,
                    "Nós honestos deviam chegar a consenso com a string '" + testString + "'");
                for (int i = 0; i < 4; i++) {
                    if (byzantineNodes.containsKey(i + 1)) continue;
                    String output;
                    synchronized (nodeOutputs.get(i)) { output = nodeOutputs.get(i).toString(); }
                    assertTrue(output.contains("Decision reached"),
                        "Nó honesto " + (i + 1) + " devia ter chegado a DECIDE");
                }
            } else {
                assertFalse(found,
                    "Consenso NÃO devia ser alcançado (demasiados nós bizantinos)");
            }

        } finally {
            for (Process node : nodes) {
                killProcessTree(node);
            }
            if (client != null) {
                killProcessTree(client);
            }
            // Also kill by port as safety net
            freeAllPorts();
        }
    }

    /**
     * Kill a process and its entire child tree.
     * On Windows, destroyForcibly() only kills the top-level process (mvn),
     * leaving the child Java processes (Node/Client) alive and holding UDP ports.
     * taskkill /F /T kills the whole tree.
     */
    private void killProcessTree(Process p) {
        try {
            long pid = p.pid();
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                    .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
            } else {
                // Kill process group on Unix
                new ProcessBuilder("kill", "-9", "-" + pid)
                    .redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            p.destroyForcibly();
        }
        try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }
}
