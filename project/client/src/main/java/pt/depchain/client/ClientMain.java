package pt.depchain.client;

import pt.depchain.communication.*;
import pt.depchain.crypto.CryptoLibrary;

import java.net.*;
import java.util.Scanner;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import java.util.Map;
import java.util.HashMap;

public class ClientMain {
    private static volatile int receivedMessages = 0;
    private static final Map<Integer, Map<String, Integer>> responseCounts = new HashMap<>();
    private static final Map<Integer, Boolean> completed = new HashMap<>();

    public static final String IST_CONTRACT_ADDRESS = "IST_CONTRACT"; // TEMPORARY: for now we just use a placeholder address for the IST contract, but this should be changed to a proper address derived from the contract's public key or something similar
    
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
        Gson gson = new Gson();
        int f = (crypto.l - 1) / 3;

        new Thread(() -> {
            try {
                while (true) {
                    Message msg = link.receive();
                    String payload = msg.getPayload();
                    String[] parts = payload.split(" ");
                    if (parts.length < 3) continue;
                    String requestKey = parts[1];
                    String status = parts[2];
                    String[] keyParts = requestKey.split("-");
                    if (keyParts.length < 2) continue;

                    int messageIdFromReply = Integer.parseInt(keyParts[1]);


                    synchronized (responseCounts) {
                        Map<String, Integer> counts = responseCounts.computeIfAbsent(messageIdFromReply, k -> new HashMap<>());

                        counts.put(status, counts.getOrDefault(status, 0) + 1);

                        int count = counts.get(status);

                        System.out.println("Received for msgId " + messageIdFromReply + ": " + status + " (" + count + ")");

                        if (count == (f + 1)) {
                            completed.put(messageIdFromReply, true);
                            responseCounts.notifyAll(); // wake up sender
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
            System.out.println("1 - Transfer DepCoin");
            System.out.println("2 - Transfer IST Coin");
            System.out.println("3 - TransferFrom IST Coin");
            System.out.println("4 - Increase Allowance (IST)");
            System.out.println("5 - Decrease Allowance (IST)");
            System.out.println("6 - Allowance (IST)");
            System.out.println("7 - Balance DepCoin");
            System.out.println("8 - Balance IST Coin");
            System.out.println("9 - Append String");
            System.out.println("0 - Exit");
            System.out.print("> ");

            String choice = scanner.nextLine();

            switch (choice) {

                case "1": { // TRANSFER DEPCOIN
                    System.out.print("To: ");
                    String to = scanner.nextLine();

                    System.out.print("Amount: ");
                    long amount = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());


                    messageId++;
                    
                    String dataStr = "TRANSFER_DEP|" + amount;
                    byte[] data = dataStr.getBytes();

                    Transaction tx = new Transaction(
                        "DEP",
                        clientId,
                        to,
                        data,
                        gasPrice,
                        gasLimit,
                        messageId,
                        null     
                    );

                    String txString = gson.toJson(tx);
                    byte[] txBytes = txString.getBytes();
                    
                    byte[] signature = crypto.sign(txBytes);

                    Transaction signedTx = new Transaction(
                        tx.getType(),
                        tx.getFrom(),
                        tx.getTo(),
                        tx.getData(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getNonce(),
                        signature
                    );

                    send(signedTx, messageId, gson, link, crypto, clientId);
                                        
                    System.out.println("\nTransfer request  of " + amount + " DEPCOINS sent to " + to + " .");                    
                    
                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
                    }
                    break;
                    
                }

                case "2": { // TRANSFER IST COIN
                    System.out.print("To: ");
                    String to = scanner.nextLine();

                    System.out.print("Amount: ");
                    long amount = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());


                    messageId++;

                    Transaction tx = new Transaction(
                        "IST",
                        clientId,
                        IST_CONTRACT_ADDRESS, // n sei se depois temos de mudar o address do contract para alguma hash em vez de ser só "IST_CONTRACT", mas para já fica assim
                        null, //devemos ter o keccak das functions + hash dos args
                        gasPrice,
                        gasLimit,
                        messageId,
                        null //devemos ter a assinatura da transaction, mas para já deixamos null
                    );
                    
                    String txString = gson.toJson(tx);
                    byte[] txBytes = txString.getBytes();

                    byte[] signature = crypto.sign(txBytes);

                    Transaction signedTx = new Transaction(
                        tx.getType(),
                        tx.getFrom(),
                        tx.getTo(),
                        tx.getData(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getNonce(),
                        signature
                    );

                    send(signedTx, messageId, gson, link, crypto, clientId);
                    
                    System.out.println("\nTransfer request  of " + amount + " ISTCOINS sent to " + to + " .");      

                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
                    }
                    
                    break;
                }
                
                case "3": { // TRANSFERFROM
                    System.out.print("From: ");
                    String from = scanner.nextLine();

                    System.out.print("To: ");
                    String to = scanner.nextLine();

                    System.out.print("Amount: ");
                    long amount = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());


                    messageId++;

                    Transaction tx = new Transaction(
                        "IST",
                        clientId,
                        IST_CONTRACT_ADDRESS,
                        null,
                        gasPrice,
                        gasLimit,
                        messageId,
                        null
                    );

                    String txString = gson.toJson(tx);
                    byte[] txBytes = txString.getBytes();

                    byte[] signature = crypto.sign(txBytes);

                    Transaction signedTx = new Transaction(
                        tx.getType(),
                        tx.getFrom(),
                        tx.getTo(),
                        tx.getData(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getNonce(),
                        signature
                    );

                    send(signedTx, messageId, gson, link, crypto, clientId);

                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
                    }
                    break;
                }

                case "4": { // INCREASE_ALLOWANCE
                    System.out.print("Spender: ");
                    String spender = scanner.nextLine();

                    System.out.print("Amount: ");
                    long amount = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());


                    messageId++;

                    Transaction tx = new Transaction(
                        "IST",
                        clientId,
                        IST_CONTRACT_ADDRESS,
                        null,
                        gasPrice,
                        gasLimit,
                        messageId,
                        null
                    );

                    String txString = gson.toJson(tx);
                    byte[] txBytes = txString.getBytes();

                    byte[] signature = crypto.sign(txBytes);

                    Transaction signedTx = new Transaction(
                        tx.getType(),
                        tx.getFrom(),
                        tx.getTo(),
                        tx.getData(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getNonce(),
                        signature
                    );

                    send(signedTx, messageId, gson, link, crypto, clientId);

                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
                    }
                    break;
                }

                case "5": { // DECREASE_ALLOWANCE
                    System.out.print("Spender: ");
                    String spender = scanner.nextLine();

                    System.out.print("Amount: ");
                    long amount = Long.parseLong(scanner.nextLine());
                    
                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());


                    messageId++;

                    Transaction tx = new Transaction(
                        "IST",
                        clientId,
                        IST_CONTRACT_ADDRESS,
                        null,
                        gasPrice,
                        gasLimit,
                        messageId,
                        null
                    );

                    String txString = gson.toJson(tx);
                    byte[] txBytes = txString.getBytes();

                    byte[] signature = crypto.sign(txBytes);

                    Transaction signedTx = new Transaction(
                        tx.getType(),
                        tx.getFrom(),
                        tx.getTo(),
                        tx.getData(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getNonce(),
                        signature
                    );

                    send(signedTx, messageId, gson, link, crypto, clientId);

                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
                    }
                    break;
                }

                case "6": { // ALLOWANCE
                    System.out.print("Owner: ");
                    String owner = scanner.nextLine();

                    System.out.print("Spender: ");
                    String spender = scanner.nextLine();

                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());


                    messageId++;

                    Transaction tx = new Transaction(
                        "IST",
                        clientId,
                        IST_CONTRACT_ADDRESS,
                        null,
                        gasPrice,
                        gasLimit,
                        messageId,
                        null
                    );

                    String txString = gson.toJson(tx);
                    byte[] txBytes = txString.getBytes();

                    byte[] signature = crypto.sign(txBytes);

                    Transaction signedTx = new Transaction(
                        tx.getType(),
                        tx.getFrom(),
                        tx.getTo(),
                        tx.getData(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getNonce(),
                        signature
                    );

                    send(signedTx, messageId, gson, link, crypto, clientId);

                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
                    }

                    break;
                }

                case "7": { // BALANCE_DEP
                    
                    System.out.print("Account: ");
                    String account = scanner.nextLine();

                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());


                    messageId++;

                    String dataStr = "BALANCE_DEP";
                    byte[] data = dataStr.getBytes();

                    Transaction tx = new Transaction(
                        "DEP",
                        clientId,
                        account,
                        data,
                        gasPrice,
                        gasLimit,
                        messageId,
                        null
                    );

                    String txString = gson.toJson(tx);
                    byte[] txBytes = txString.getBytes();

                    byte[] signature = crypto.sign(txBytes);

                    Transaction signedTx = new Transaction(
                        tx.getType(),
                        tx.getFrom(),
                        tx.getTo(),
                        tx.getData(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getNonce(),
                        signature
                    );

                    send(signedTx, messageId, gson, link, crypto, clientId);

                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
                    }
                    break;
                }

                case "8": { // BALANCE_IST
                 
                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());


                    messageId++;

                    Transaction tx = new Transaction(
                        "IST",
                        clientId,
                        IST_CONTRACT_ADDRESS,
                        null,
                        gasPrice,
                        gasLimit,
                        messageId,
                        null
                    );

                    String txString = gson.toJson(tx);
                    byte[] txBytes = txString.getBytes();

                    byte[] signature = crypto.sign(txBytes);

                    Transaction signedTx = new Transaction(
                        tx.getType(),
                        tx.getFrom(),
                        tx.getTo(),
                        tx.getData(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getNonce(),
                        signature
                    );

                    send(signedTx, messageId, gson, link, crypto, clientId);

                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
                    }
                    break;
                }

                // just for now, take out later
                case "9": //APPEND STRING
                    
                    System.out.print("Enter string to append: ");
                    String text = scanner.nextLine();
                    
                    messageId++;
                    
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
                    
                    synchronized (responseCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                responseCounts.wait();
                            }
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

    private static void send(Transaction tx, int messageId, Gson gson, Link link, CryptoLibrary crypto, String clientId) throws Exception {
        JsonObject payloadJson = new JsonObject();
        payloadJson.addProperty("clientId", clientId);
        payloadJson.addProperty("messageId", messageId);
        payloadJson.add("transaction", gson.toJsonTree(tx));

        String payload = payloadJson.toString();

        String[] replicas = new String[crypto.l];
        for (int i = 1; i <= crypto.l; i++) {
            replicas[i - 1] = String.valueOf(i);
        }

        link.broadcastWithId(replicas, Message.Type.TRANSACTION, payload, messageId);
    }

    // TEMPORARY: for now we just use the clientId as the address, but this should be changed to a proper address derived from the public key
    private static String getAddress(String clientId) {
        return clientId; // TEMPORARY
    }

}