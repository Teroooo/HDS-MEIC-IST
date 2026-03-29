package pt.depchain.client;

import pt.depchain.communication.*;
import pt.depchain.crypto.CryptoLibrary;

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

        CryptoLibrary crypto = new CryptoLibrary(privateKeyPath, publicKeyPath);
        Link link = new Link(clientId, Link.Type.CLIENT, "../config/membership.json", privateKeyPath, publicKeyPath, crypto);

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
            System.out.println("2 - Transfer Funds");
            System.out.println("1 - Append String");
            System.out.println("0 - Exit");
            System.out.print("> ");

            String choice = scanner.nextLine();

            switch (choice) {
                case "2":
                    System.out.print("Enter account to transfer to: ");
                    String destAccount = scanner.nextLine();

                    System.out.print("Enter amount to transfer: ");
                    String amountStr = scanner.nextLine();

                    System.out.print("Enter gas_limit: ");
                    String gasLimitStr = scanner.nextLine();

                    System.out.print("Enter gas_price: ");
                    String gasPriceStr = scanner.nextLine();

                    messageId++;
                    synchronized (responseCounts) {
                        responseCounts.clear();
                    }
                    completed = false;

                    JsonObject payloadJsonTransfer = new JsonObject();
                    payloadJsonTransfer.addProperty("clientId", clientId);
                    payloadJsonTransfer.addProperty("messageId", messageId);
                    payloadJsonTransfer.addProperty("destAccount", destAccount);
                    payloadJsonTransfer.addProperty("amount", amountStr);
                    payloadJsonTransfer.addProperty("gasLimit", gasLimitStr);
                    payloadJsonTransfer.addProperty("gasPrice", gasPriceStr);

                    String payloadTransfer = payloadJsonTransfer.toString();
                    String[] replicas = new String[crypto.l];
                    for (int i = 1; i <= crypto.l; i++) {
                        replicas[i - 1] = String.valueOf(i);
                    }
                    
                    link.broadcastWithId(replicas, Message.Type.TRANSACTION, payloadTransfer, messageId);
                    
                    System.out.println("\nTransfer request of " + amountStr + " to " + destAccount + " sent.");                    
                    
                    // Wait for (n-f) = 3 responses
                    while (!completed) {
                        Thread.sleep(100);
                    }
                    break;

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
                    String[] replicasAppend = new String[crypto.l];
                    for (int i = 1; i <= crypto.l; i++) {
                        replicasAppend[i - 1] = String.valueOf(i);
                    }
                    
                    
                    
                    link.broadcastWithId(replicasAppend, Message.Type.APPEND_STRING, payload, messageId);
                    
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