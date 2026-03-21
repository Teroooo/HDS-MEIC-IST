package pt.depchain.client;

import pt.depchain.communication.*;
import java.net.*;
import java.util.Scanner;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.HashMap;

public class ClientMain {
    private static volatile int receivedMessages = 0;
    private static final Map<String, Integer> responseCounts = new HashMap<>();
    private static volatile boolean completed = false;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java ClientMain <clientId> <privateKey> <publicKey>");
            System.exit(1);
        }  

        String clientId = args[0];
        String privateKeyPath = args[1];
        String publicKeyPath = args[2];

        int messageId = 0; 
        Link link = new Link(clientId, Link.Type.CLIENT, "../config/membership.json", privateKeyPath, publicKeyPath);

        new Thread(() -> {
            try {
                while (true) {
                    Message msg = link.receive();
                    String payload = msg.getPayload();
                    String[] parts = payload.split(" ");
                    if (parts.length < 3) continue;
                    String requestKey = parts[1];
                    String status = parts[2];
                    String combined = requestKey + "-" + status;

                    synchronized (responseCounts) {
                        responseCounts.put(combined, responseCounts.getOrDefault(combined, 0) + 1);

                        int count = responseCounts.get(combined);

                        System.out.println("Received: " + combined + " (" + count + ")");

                        if (count >= 2 && !completed) { 
                            completed = true;

                            if (status.equals("SUCCESS")) {
                                System.out.println("String committed");
                            } else {
                                System.out.println("String not committed");
                            }
                        }
                    } 
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\nSelect operation:");
            System.out.println("1 - Append String");
            System.out.println("0 - Exit");
            System.out.print("> ");

            String choice = scanner.nextLine();

            switch (choice) {

                case "1":
                           
                    System.out.print("Enter string to append: ");
                    String text = scanner.nextLine();
                    
                    messageId++;
                    synchronized (responseCounts) {
                        responseCounts.clear();
                    }
                    completed = false;
                    
                    JsonObject payloadJson = new JsonObject();
                    payloadJson.addProperty("text", text);
                    payloadJson.addProperty("clientId", clientId);
                    payloadJson.addProperty("messageId", messageId);
                    
                    String payload = payloadJson.toString();
                    String[] replicas = {"1","2","3","4"};
                    
                    
                    link.broadcastWithId(replicas, Message.Type.APPEND_STRING, payload, messageId);
                    
                    System.out.println("\nAppend request sent. Waiting for responses...");
                    
                    // Wait for (n-f) = 3 responses
                    while (!completed) {
                        Thread.sleep(100);
                    }
                    
                    break;

                case "0":
                    System.exit(0);
                    break;

                default:
                    System.out.println("Invalid option.");
            }
        }
    }
}