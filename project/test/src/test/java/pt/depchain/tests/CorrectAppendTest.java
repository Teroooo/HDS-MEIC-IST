package pt.depchain.tests;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CorrectAppendTest {

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

    @Test
    public void testCommandOneConsensus() throws Exception {
        freeAllPorts();
        List<Process> nodes = new ArrayList<>();
        List<Process> clients = new ArrayList<>();

        try {
            // Start 4 nodes
            for (int i = 1; i <= 4; i++) {
                final int nodeId = i; // copy for lambda
                ProcessBuilder pb = new ProcessBuilder(
                        "cmd", "/c", "mvn", "exec:java",
                        "-Dexec.mainClass=pt.depchain.service.Node",
                        "-Dexec.args=" + i +
                        " ../config/node" + i + ".priv" +
                        " ../config/node" + i + ".pub"
                );
                pb.redirectErrorStream(true);
                Process node = pb.start();
                nodes.add(node);

                // Print node logs asynchronously
                //new Thread(() -> printProcessOutput(node, "NODE-" + nodeId)).start(); // use nodeId           
            }

            // Wait a few seconds for nodes to initialize
            Thread.sleep(1000);

            // Start client
            for (int i = 1; i <= 2; i++) {
                ProcessBuilder pbClient = new ProcessBuilder(
                        "cmd", "/c", "mvn", "exec:java",
                        "-Dexec.mainClass=pt.depchain.client.ClientMain",
                        "-Dexec.args=client" + i + " ../config/client" + i + ".priv ../config/client" + i + ".pub"
                );
                pbClient.redirectErrorStream(true);
                Process client = pbClient.start();
                final Process clientProcess = client;

                clients.add(client);
            }

            // Print client logs asynchronously
            //new Thread(() -> printProcessOutput(clientProcess, "CLIENT-1")).start();

            // Give client a second to start
            Thread.sleep(30000);
            BufferedWriter clientOneWriter = new BufferedWriter(new OutputStreamWriter(clients.get(0).getOutputStream()));
            BufferedReader clientOneReader = new BufferedReader(new InputStreamReader(clients.get(0).getInputStream()));
            
            BufferedWriter clientTwoWriter = new BufferedWriter(new OutputStreamWriter(clients.get(1).getOutputStream()));
            BufferedReader clientTwoReader = new BufferedReader(new InputStreamReader(clients.get(1).getInputStream()));

            // Send "Append String" command: choose option 1 and then type "test"
            clientOneWriter.write("1\n"); // select transfer DEP
            clientOneWriter.flush();
            Thread.sleep(200); // small delay
            clientOneWriter.write("client2\n"); // dest
            clientOneWriter.flush();
            Thread.sleep(200); // small delay
            clientOneWriter.write("1\n"); // amount
            clientOneWriter.flush();
            Thread.sleep(200); // small delay
            clientOneWriter.write("1\n"); // gas price
            clientOneWriter.flush();
            Thread.sleep(200); // small delay
            clientOneWriter.write("100000\n"); // gas limit
            clientOneWriter.flush();

            // Wait for consensus to happen
            Thread.sleep(30000);

            // Check nodes’ outputs for the appended string
            boolean[] found = new boolean[nodes.size()];
            long start = System.currentTimeMillis();
            long timeout = 30000; // 30 seconds max
            long foundCount = 0;

            while (System.currentTimeMillis() - start < timeout) {
                for (int i = 0; i < nodes.size(); i++) {
                    // Skip nodes we already found to save processing time
                    if (found[i]) continue;

                    Process node = nodes.get(i);
                    String nodeOutput = readProcessOutputNonBlocking(node);
                    
                    if (nodeOutput.contains("0x5452414e534645525f4445507c31") && nodeOutput.contains("DEP")) {
                        found[i] = true;
                        System.out.println("Node " + i + " confirmed the transaction.");
                    }
                }

                // Count how many nodes have found the transaction
                for (boolean b : found) {
                    if (b) foundCount++;
                }

                // BREAK condition: check if we have reached the threshold of 3
                if (foundCount >= 3) {
                    System.out.println("Threshold met: 3 nodes have confirmed. Breaking loop.");
                    break;
                }

                Thread.sleep(200);
            }

            assertTrue(foundCount >= 3, "Blockchain should contain the transaction on at least 3 nodes");

        } finally {
            // Kill all nodes
            for (Process node : nodes) {
                node.destroyForcibly();
            }
            for (Process client : clients) {
                client.destroyForcibly();
            }
        }
    }

    @Test
    public void testCommandTwoConsensus() throws Exception {
        freeAllPorts();
        List<Process> nodes = new ArrayList<>();
        List<Process> clients = new ArrayList<>();

        try {
            // Start 4 nodes
            for (int i = 1; i <= 4; i++) {
                final int nodeId = i; // copy for lambda
                ProcessBuilder pb = new ProcessBuilder(
                        "cmd", "/c", "mvn", "exec:java",
                        "-Dexec.mainClass=pt.depchain.service.Node",
                        "-Dexec.args=" + i +
                        " ../config/node" + i + ".priv" +
                        " ../config/node" + i + ".pub"
                );
                pb.redirectErrorStream(true);
                Process node = pb.start();
                nodes.add(node);

                // Print node logs asynchronously
                //new Thread(() -> printProcessOutput(node, "NODE-" + nodeId)).start(); // use nodeId           
            }

            // Wait a few seconds for nodes to initialize
            Thread.sleep(1000);

            // Start client
            for (int i = 1; i <= 2; i++) {
                ProcessBuilder pbClient = new ProcessBuilder(
                        "cmd", "/c", "mvn", "exec:java",
                        "-Dexec.mainClass=pt.depchain.client.ClientMain",
                        "-Dexec.args=client" + i + " ../config/client" + i + ".priv ../config/client" + i + ".pub"
                );
                pbClient.redirectErrorStream(true);
                Process client = pbClient.start();
                final Process clientProcess = client;

                clients.add(client);
            }

            // Print client logs asynchronously
            //new Thread(() -> printProcessOutput(clientProcess, "CLIENT-1")).start();

            // Give client a second to start
            Thread.sleep(30000);
            BufferedWriter clientOneWriter = new BufferedWriter(new OutputStreamWriter(clients.get(0).getOutputStream()));
            BufferedReader clientOneReader = new BufferedReader(new InputStreamReader(clients.get(0).getInputStream()));
            
            BufferedWriter clientTwoWriter = new BufferedWriter(new OutputStreamWriter(clients.get(1).getOutputStream()));
            BufferedReader clientTwoReader = new BufferedReader(new InputStreamReader(clients.get(1).getInputStream()));

            // Send "Append String" command: choose option 1 and then type "test"
            clientOneWriter.write("2\n"); // select transfer IST
            clientOneWriter.flush();
            Thread.sleep(200); // small delay
            clientOneWriter.write("client2\n"); // dest
            clientOneWriter.flush();
            Thread.sleep(200); // small delay
            clientOneWriter.write("1\n"); // amount
            clientOneWriter.flush();
            Thread.sleep(200); // small delay
            clientOneWriter.write("1\n"); // gas price
            clientOneWriter.flush();
            Thread.sleep(200); // small delay
            clientOneWriter.write("100000\n"); // gas limit
            clientOneWriter.flush();

            // Wait for consensus to happen
            Thread.sleep(30000);

            // Check nodes’ outputs for the appended string
            boolean[] found = new boolean[nodes.size()];
            long start = System.currentTimeMillis();
            long timeout = 30000; // 30 seconds max
            long foundCount = 0;

            while (System.currentTimeMillis() - start < timeout) {
                for (int i = 0; i < nodes.size(); i++) {
                    // Skip nodes we already found to save processing time
                    if (found[i]) continue;

                    Process node = nodes.get(i);
                    String nodeOutput = readProcessOutputNonBlocking(node);
                    
                    if (nodeOutput.contains("0x9192594bd4e047f4fa427a951fceb8c7c70b2094,") && 
                    nodeOutput.contains("0x1234567891234567891234567891234567891234,") && 
                    nodeOutput.contains("IST")) {
                        found[i] = true;
                        System.out.println("Node " + i + " confirmed the transaction.");
                    }
                }

                // Count how many nodes have found the transaction
                for (boolean b : found) {
                    if (b) foundCount++;
                }

                // BREAK condition: check if we have reached the threshold of 3
                if (foundCount >= 3) {
                    System.out.println("Threshold met: 3 nodes have confirmed. Breaking loop.");
                    break;
                }

                Thread.sleep(200);
            }

            assertTrue(foundCount >= 3, "Blockchain should contain the transaction on at least 3 nodes");

        } finally {
            // Kill all nodes
            for (Process node : nodes) {
                node.destroyForcibly();
            }
            for (Process client : clients) {
                client.destroyForcibly();
            }
        }
    }

    private static void printProcessOutput(Process p, String prefix) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[" + prefix + "] " + line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String readProcessOutputNonBlocking(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        InputStream in = p.getInputStream();
        while (in.available() > 0) { // only read what's available
            sb.append((char) in.read());
        }
        return sb.toString();
    }
}