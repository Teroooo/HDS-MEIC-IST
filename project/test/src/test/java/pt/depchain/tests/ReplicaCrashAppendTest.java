package pt.depchain.tests;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReplicaCrashAppendTest {

    @Test
    public void testAppendStringConsensus() throws Exception {
        List<Process> nodes = new ArrayList<>();
        Process client = null;

        try {
            // Start 4 nodes
            for (int i = 1; i <= 4; i++) {
                final int nodeId = i; // copy for lambda
                ProcessBuilder pb = new ProcessBuilder(
                        "mvn", "exec:java",
                        "-Dexec.mainClass=pt.depchain.service.Node",
                        "-Dexec.args=" + i
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
            ProcessBuilder pbClient = new ProcessBuilder(
                    "mvn", "exec:java",
                    "-Dexec.mainClass=pt.depchain.client.ClientMain",
                    "-Dexec.args=1"
            );
            pbClient.redirectErrorStream(true);
            client = pbClient.start();
            final Process clientProcess = client;
            BufferedWriter clientWriter = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
            BufferedReader clientReader = new BufferedReader(new InputStreamReader(client.getInputStream()));

            // Print client logs asynchronously
            //new Thread(() -> printProcessOutput(clientProcess, "CLIENT-1")).start();

            // Give client a second to start
            Thread.sleep(1000);

            // Send "Append String" command: choose option 1 and then type "test"
            clientWriter.write("1\n"); // select append
            clientWriter.flush();
            Thread.sleep(200); // small delay
            clientWriter.write("correto\n"); // string to append
            clientWriter.flush();

            // Wait for consensus to happen
            Thread.sleep(4000);

            // Check nodes’ outputs for the appended string
            boolean found = false;
            long start = System.currentTimeMillis();
            long timeout = 10000; // 10 seconds max

            while (System.currentTimeMillis() - start < timeout) {
                for (Process node : nodes) {
                    String nodeOutput = readProcessOutputNonBlocking(node);
                    if (nodeOutput.contains("correto")) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
                Thread.sleep(200); // small delay
            }

            assertTrue(found, "Blockchain should contain the appended string 'correto'");

            Thread.sleep(200); // small delay
            nodes.get(0).destroyForcibly();
            Thread.sleep(200); // small delay


            // Send "Append String" command: choose option 1 and then type "test"
            clientWriter.write("1\n"); // select append
            clientWriter.flush();
            Thread.sleep(200); // small delay
            clientWriter.write("replicacrash\n"); // string to append
            clientWriter.flush();

            // Wait for consensus to happen
            Thread.sleep(4000);

            // Check nodes’ outputs for the appended string
            boolean[] foundList = new boolean[4];
            start = System.currentTimeMillis();
            timeout = 30000; // 30 seconds max
            while (System.currentTimeMillis() - start < timeout) {
                int count = 0;
                for (Process node : nodes) {
                    if (count == 0) { // skip the crashed node
                        count++;
                        continue;
                    }
                    String nodeOutput = readProcessOutputNonBlocking(node);
                    if (nodeOutput.contains("replicacrash")) {
                        foundList[count] = true;
                        break;
                    }
                    count++;
                }
                Thread.sleep(200); // small delay
            }
            assertTrue(foundList[1] && foundList[2] && foundList[3], "Blockchain should contain the appended string 'replicacrash'");


        } finally {
            // Kill all nodes
            for (Process node : nodes) {
                node.destroyForcibly();
            }
            if (client != null) client.destroyForcibly();
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