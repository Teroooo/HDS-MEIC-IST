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
    private void freeAllPorts(int numClients) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        StringBuilder nodeClientPorts = new StringBuilder();
        nodeClientPorts.append("9001,9002,9003,9004");
        for (int i = 1; i <= numClients; i++) {
            nodeClientPorts.append(",").append(4000 + i);
        }
        
        if (isWindows) {
            // PowerShell one-liner: find all PIDs listening on our ports and kill them
            try {
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-NetUDPEndpoint -LocalPort " + nodeClientPorts + " -ErrorAction SilentlyContinue" +
                    " | Select-Object -ExpandProperty OwningProcess -Unique" +
                    " | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }")
                    .redirectErrorStream(true).start();
                p.waitFor(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        } else {
            List<Integer> ports = new ArrayList<>();
            ports.addAll(Arrays.asList(9001, 9002, 9003, 9004));
            for (int i = 1; i <= numClients; i++) {
                ports.add(4000 + i);
            }
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
                                        boolean expectConsensus,
                                        int numClients) throws Exception {
        // Kill any leftover processes from a previous test
        freeAllPorts(numClients);

        List<Process> nodes = new ArrayList<>();
        List<StringBuilder> nodeOutputs = new ArrayList<>();
        List<Process> clients = new ArrayList<>();
        List<BufferedWriter> clientWriters = new ArrayList<>();

        try {
            for (int i = 1; i <= 4; i++) {
                ProcessBuilder pb;
                if (byzantineNodes.containsKey(i)) {
                    String attack = byzantineNodes.get(i);
                    pb = new ProcessBuilder(
                        "cmd", "/c", "mvn", "exec:java",
                        "-Dexec.mainClass=pt.depchain.service.ByzantineNode",
                        "-Dexec.args=" + i +
                        " ../config/node" + i + ".priv" +
                        " ../config/node" + i + ".pub" +
                        " " + attack
                    );
                } else {
                    pb = new ProcessBuilder(
                        "cmd", "/c", "mvn", "exec:java",
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

            // Spawn multiple clients
            for (int i = 1; i <= numClients; i++) {
                ProcessBuilder pbClient = new ProcessBuilder(
                    "cmd", "/c", "mvn", "exec:java",
                    "-Dexec.mainClass=pt.depchain.client.ClientMain",
                    "-Dexec.args=client" + i +
                    " ../config/client" + i + ".priv" +
                    " ../config/client" + i + ".pub"
                );
                pbClient.redirectErrorStream(true);
                Process clientProcess = pbClient.start();
                clients.add(clientProcess);

                final int clientId = i;
                final Process cp = clientProcess;
                new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(cp.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            System.out.println("[CLIENT-" + clientId + "] " + line);
                        }
                    } catch (IOException ignored) {}
                }, "reader-client-" + i).start();
            }

            Thread.sleep(CLIENT_STARTUP_MS);

            // Only client1 performs the transfer IST operation
            BufferedWriter client1Writer = new BufferedWriter(new OutputStreamWriter(clients.get(0).getOutputStream()));
            clientWriters.add(client1Writer);

            //client 2: Transfer From 
            client1Writer.write("2\n"); // select transfer DEP
            client1Writer.flush();
            Thread.sleep(200); // small delay
            client1Writer.write("client2\n"); // to
            client1Writer.flush();
            Thread.sleep(200); // small delay
            client1Writer.write("100\n"); // amount
            client1Writer.flush();
            Thread.sleep(200); // small delay
            client1Writer.write("1\n"); // gas price
            client1Writer.flush();
            Thread.sleep(200); // small delay
            client1Writer.write("100000\n"); // gas limit
            client1Writer.flush();

            long deadline = System.currentTimeMillis() + CONSENSUS_TIMEOUT_MS;
            boolean found = false;

            while (System.currentTimeMillis() < deadline) {
                for (int i = 0; i < 4; i++) {
                    if (byzantineNodes.containsKey(i + 1)) continue;
                    String output;
                    synchronized (nodeOutputs.get(i)) { output = nodeOutputs.get(i).toString(); }
                    if (output.contains("Generic call result: SUCCESS") && output.contains("Decision reached")) {
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
            for (Process client : clients) {
                killProcessTree(client);
            }
            // Also kill by port as safety net
            freeAllPorts(numClients);
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
