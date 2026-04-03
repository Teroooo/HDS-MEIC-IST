package pt.depchain.client;

import pt.depchain.communication.*;
import pt.depchain.crypto.CryptoLibrary;

import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Scanner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import java.util.Map;
import java.util.HashMap;
import java.util.HexFormat;
import java.math.BigInteger;

import org.hyperledger.besu.datatypes.Address;

public class ClientMain {
    private static volatile int receivedMessages = 0;
    //private static final Map<Integer, Map<String, Integer>> returnDataCounts = new HashMap<>();
    private static final Map<Integer, Boolean> completed = new HashMap<>();
    private static final Map<Integer, String> messageOperations = new HashMap<>();
    private static final Map<Integer, Map<String, Integer>> returnDataCounts = new HashMap<>();
    private static final Map<Integer, String> returnDataMap = new HashMap<>();

    public static final String IST_CONTRACT_ADDRESS = "0x1234567891234567891234567891234567891234"; // TEMPORARY: for now we just use a placeholder address for the IST contract, but this should be changed to a proper address derived from the contract's public key or something similar
    
    private static String bytecode;
    private static String allowance;
    private static String balanceOf;
    private static String transfer;
    private static String transferFrom;
    private static String increaseAllowance;
    private static String decreaseAllowance;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java ClientMain <clientId> <privateKey> <publicKey>");
            System.exit(1);
        }  

        init_keccak_256();
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

                    // Transaction <txKey> ...
                    String requestKey = parts[1];

                    // Extract messageId
                    String[] keyParts = requestKey.split("-");
                    if (keyParts.length < 2) continue;

                    int messageIdFromReply = Integer.parseInt(keyParts[1]);

                    // STATUS is ALWAYS the third token
                    String status = parts[2];

                    // OPTIONAL return data (everything after status)
                    String returnData = "";
                    if (parts.length > 3) {
                        returnData = payload.substring(payload.indexOf(status) + status.length()).trim();
                    }
                    
                    String compositeKey = status + "|" + returnData;

                    synchronized (returnDataCounts) {
                        Map<String, Integer> counts = returnDataCounts.computeIfAbsent(messageIdFromReply, k -> new HashMap<>());

                        counts.put(compositeKey, counts.getOrDefault(compositeKey, 0) + 1);

                        int count = counts.get(compositeKey);

                        //System.out.println("Received for msgId " + messageIdFromReply + ": " + status + " (" + count + ")");

                        if (count == (f + 1)) {
                            System.out.println("\nQUORUM REACHED for msgId " + messageIdFromReply);
                            returnDataMap.put(messageIdFromReply, compositeKey);
                            completed.put(messageIdFromReply, true);
                            returnDataCounts.notifyAll();
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

                    Address addFrom = Address.fromHexString(normalizeAddressHex(clientId));
                    Address addTo = Address.fromHexString(normalizeAddressHex(to));
                    //System.out.println("Recipient address: " + add);
                    
                    messageId++;
                    String dataStr = "TRANSFER_DEP" + "|" + amount;
                    //TODO TRATAMENTO DE FROM E TO
                    Transaction tx = new Transaction(
                        "DEP",
                        addFrom.toHexString(),
                        addTo.toHexString(),
                        dataStr.getBytes(),
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
                    
                    messageOperations.put(messageId, "TRANSFER_DEP");
                    System.out.println("\nTransfer request  of " + amount + " DEPCOINS sent to " + to + " .");                    
                    
                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
                            }
                    }

                    String response = returnDataMap.get(messageId);
                    String operation = messageOperations.get(messageId);

                    decodeDepResponse(operation, response);
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
                    Address addFrom = Address.fromHexString(normalizeAddressHex(clientId));
                    Address add = Address.fromHexString(normalizeAddressHex(to));
                    String dataStr = transfer + padAddress(add) + convertIntegerToHex256Bit(BigInteger.valueOf(amount).intValue());
                    Transaction tx = new Transaction(
                        "IST",
                        addFrom.toHexString(),
                        IST_CONTRACT_ADDRESS, // n sei se depois temos de mudar o address do contract para alguma hash em vez de ser só "IST_CONTRACT", mas para já fica assim
                        dataStr.getBytes(), //devemos ter o keccak das functions + hash dos args
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
                    
                    messageOperations.put(messageId, "TRANSFER_IST");
                    System.out.println("\nTransfer request  of " + amount + " ISTCOINS sent to " + to + " .");      

                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
                            }
                    }
                    String response = returnDataMap.get(messageId);
                    String operation = messageOperations.get(messageId);

                    decodeISTResponse(operation, response);
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

                    Address addFrom = Address.fromHexString(normalizeAddressHex(clientId));

                    messageId++;
                    Address addFromArg = Address.fromHexString(normalizeAddressHex(from));
                    Address addTo = Address.fromHexString(normalizeAddressHex(to));

                    String dataStr = transferFrom + padAddress(addFromArg) + padAddress(addTo) + convertIntegerToHex256Bit(BigInteger.valueOf(amount).intValue());
                    Transaction tx = new Transaction(
                        "IST",
                        addFrom.toHexString(),
                        IST_CONTRACT_ADDRESS,
                        dataStr.getBytes(),
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
                    messageOperations.put(messageId, "TRANSFER_FROM");

                    System.out.println("\nTransferFrom request: " + amount + " ISTCOINS from " + from + " to " + to + ".");

                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
                            }
                    }
                    String response = returnDataMap.get(messageId);
                    String operation = messageOperations.get(messageId);

                    decodeISTResponse(operation, response);
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

                    Address addFrom = Address.fromHexString(normalizeAddressHex(clientId));

                    messageId++;
                    Address addSpender = Address.fromHexString(normalizeAddressHex(spender));
                    String dataStr = increaseAllowance + padAddress(addSpender) + convertIntegerToHex256Bit(BigInteger.valueOf(amount).intValue());
                    Transaction tx = new Transaction(
                        "IST",
                        addFrom.toHexString(),
                        IST_CONTRACT_ADDRESS,
                        dataStr.getBytes(),
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
                    System.out.println("\nIncreaseAllowance request: " + clientId + " increases allowance for " + spender + " by " + amount + " ISTCOINS.");

                    messageOperations.put(messageId, "INCREASE_ALLOWANCE");
                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
                            }
                    }
                    String response = returnDataMap.get(messageId);
                    String operation = messageOperations.get(messageId);

                    decodeISTResponse(operation, response);
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

                    
                    Address addFrom = Address.fromHexString(normalizeAddressHex(clientId));
                    messageId++;
                    Address addSpender = Address.fromHexString(normalizeAddressHex(spender));
                    String dataStr = decreaseAllowance + padAddress(addSpender) + convertIntegerToHex256Bit(BigInteger.valueOf(amount).intValue());
                    Transaction tx = new Transaction(
                        "IST",
                        addFrom.toHexString(),
                        IST_CONTRACT_ADDRESS,
                        dataStr.getBytes(),
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

                    System.out.println("\nDecreaseAllowance request: " + clientId + " increases allowance for " + spender + " by " + amount + " ISTCOINS.");
                    messageOperations.put(messageId, "DECREASE_ALLOWANCE");

                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
                            }
                    }
                    String response = returnDataMap.get(messageId);
                    String operation = messageOperations.get(messageId);

                    decodeISTResponse(operation, response);
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


                    Address addFrom = Address.fromHexString(normalizeAddressHex(clientId));
                    messageId++;
                    Address addOwner = Address.fromHexString(normalizeAddressHex(owner));
                    Address addSpender = Address.fromHexString(normalizeAddressHex(spender));

                    String dataStr = allowance + padAddress(addOwner) + padAddress(addSpender);
                    Transaction tx = new Transaction(
                        "IST",
                        addFrom.toHexString(),
                        IST_CONTRACT_ADDRESS,
                        dataStr.getBytes(),
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
                    System.out.println("\nAllowance query: owner=" + owner + ", spender=" + spender + ".");

                    messageOperations.put(messageId, "ALLOWANCE");

                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
                            }
                    }
                    String response = returnDataMap.get(messageId);
                    String operation = messageOperations.get(messageId);

                    decodeISTResponse(operation, response);
                    break;
                }

                case "7": { // BALANCE_DEP
                    
                    System.out.print("Account: ");
                    String account = scanner.nextLine();

                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());

                    Address addFrom = Address.fromHexString(normalizeAddressHex(clientId));
                    messageId++;
                    Address add = Address.fromHexString(normalizeAddressHex(account));
                    String dataStr = "BALANCE_DEP";
                    

                    //TODO TRATAMENTO DE FROM E TO
                    Transaction tx = new Transaction(
                        "DEP",
                        addFrom.toHexString(),
                        add.toHexString(),
                        dataStr.getBytes(),
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
                    System.out.println("\nBalance_DEP request for " + account + ".");

                    messageOperations.put(messageId, "BALANCE_DEP");
                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
                            }
                    }

                    String response = returnDataMap.get(messageId);
                    String operation = messageOperations.get(messageId);

                    decodeDepResponse(operation, response);
                    break;
                }

                case "8": { // BALANCE_IST
                 
                    System.out.print("Gas price: ");
                    long gasPrice = Long.parseLong(scanner.nextLine());

                    System.out.print("Gas limit: ");
                    long gasLimit = Long.parseLong(scanner.nextLine());

                    Address addFrom = Address.fromHexString(normalizeAddressHex(clientId));
                    messageId++;
                    Address add = Address.fromHexString(normalizeAddressHex(clientId));
                    String dataStr = balanceOf + padAddress(add);
                    Transaction tx = new Transaction(
                        "IST",
                        addFrom.toHexString(),
                        IST_CONTRACT_ADDRESS,
                        dataStr.getBytes(),
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
                    System.out.println("\nBalanceOf request for " + clientId + ".");
                    messageOperations.put(messageId, "BALANCE_IST");
                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
                            }
                    }
                    String response = returnDataMap.get(messageId);
                    String operation = messageOperations.get(messageId);

                    decodeISTResponse(operation, response);
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
                    
                    synchronized (returnDataCounts) {
                        while (!completed.getOrDefault(messageId, false)) {
                                returnDataCounts.wait();
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

    public static void init_keccak_256() {
        try {
            // Option 1: Move up one level to the sibling ERC20 folder (../ERC20/...)
            Path parentErc20 = Path.of("..", "ERC20", "keccak_256.json");
            // Option 2: Look in a local ERC20 folder (ERC20/...)
            Path localErc20 = Path.of("ERC20", "keccak_256.json");
            // Option 3: Current directory
            Path currentDir = Path.of("keccak_256.json");

            Path jsonPath;
            if (Files.exists(parentErc20)) {
                jsonPath = parentErc20;
            } else if (Files.exists(localErc20)) {
                jsonPath = localErc20;
            } else {
                jsonPath = currentDir;
            }

            // Read and parse
            String content = Files.readString(jsonPath);
            JsonObject jsonObject = JsonParser.parseString(content).getAsJsonObject();

            // Assigning values from JSON
            bytecode = jsonObject.get("EVM Runtime Bytecode").getAsString();
            allowance = jsonObject.get("allowance(address,address)").getAsString();
            balanceOf = jsonObject.get("balanceOf(address)").getAsString();
            transfer = jsonObject.get("transfer(address,uint256)").getAsString();
            transferFrom = jsonObject.get("transferFrom(address,address,uint256)").getAsString();
            increaseAllowance = jsonObject.get("increaseAllowance(address,uint256)").getAsString();
            decreaseAllowance = jsonObject.get("decreaseAllowance(address,uint256)").getAsString();

        } catch (Exception e) {
            throw new RuntimeException("Failed to load ERC20 selectors from keccak_256.json at specified paths", e);
        }
    }

    private static String padAddress(Address addr) {
        String hex = addr.toHexString().replace("0x", "");
        return "000000000000000000000000" + hex;
    }

    public static String convertIntegerToHex256Bit(int number) {
        BigInteger bigInt = BigInteger.valueOf(number);

        return String.format("%064x", bigInt);
    }

    public static String normalizeAddressHex(String clientName) {
        Path path = Paths.get("..", "config", clientName + ".pub");
        
        String publicKeyContent;
        try {
            publicKeyContent = Files.readString(path);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read public key file: " + path, e);
        }

        // 1. Clean the PEM string: remove headers, footers, and all whitespace
        String cleanBase64 = publicKeyContent
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", ""); // Removes newlines and spaces

        // 2. Decode the Base64 to get the raw DER bytes
        byte[] derBytes = Base64.getDecoder().decode(cleanBase64);

        // 3. Hash the bytes (usually SHA-256 or Keccak-256) to derive an address
        String hex;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(derBytes);
            
            // Convert the hash to a Hex String
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            hex = sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }

        // 4. Apply your existing length logic
        if (hex.length() == 64) {
            return hex.substring(0, 40); // Standard approach for many chains
        }

        if (hex.length() == 40) {
            return hex;
        }

        throw new IllegalArgumentException("Invalid address key length derived from file: " + hex);
    }

    private static void decodeDepResponse(String operation, String response) {
        if (response == null) {
            System.out.println("No response received.");
            return;
        }

        String[] parts = response.split("\\|", 2);
        String status = parts[0];
        String data = parts.length > 1 ? parts[1] : "";

        if (status.equals("FAILURE")) {
            if (data.contains("VALIDATION_ERROR")) {
                System.out.println("Transaction rejected: VALIDATION ERROR");
                return;
            }

            if (data.contains("before execution")) {
                if (data.contains("INSUFFICIENT_BALANCE")) {
                    String[] tokens = data.split(" ");
                    String hexBalance = tokens[tokens.length - 1];

                    try {
                        BigInteger balance = new BigInteger(hexBalance, 16);
                        System.out.println("Transaction failed: INSUFFICIENT BALANCE");
                        System.out.println("Current balance: " + balance);
                    } catch (Exception e) {
                        System.out.println("Transaction failed before execution: " + data);
                    }

                    return;
                }

                System.out.println("Transaction failed before execution: " + data);
                return;
            }
        }

        switch (operation) {

            case "BALANCE_DEP":
                handleBalanceDep(status, data);
                break;

            case "TRANSFER_DEP":
                handleTransferDep(status);
                break;

            default:
                System.out.println("Unknown DEP operation: " + operation);
        }
    }

    private static void handleBalanceDep(String status, String data) {
        if (status.equals("SUCCESS")) {
            try {
                BigInteger balance = new BigInteger(data);
                System.out.println("Balance: " + balance + " DEPCOINS");
            } catch (Exception e) {
                System.out.println("Error decoding balance: " + data);
            }
        } else {
            System.out.println("Did not find balance " + status);
        }
    }

    private static void handleTransferDep(String status) {
        if (status.equals("SUCCESS")) {
            System.out.println("Transfer completed successfully.");
        } else {
            System.out.println("Transfer Unsucessful: " + status);
        }
    }

    private static void decodeISTResponse(String operation, String response) {
        if (response == null) {
            System.out.println("No response received.");
            return;
        }
        //System.out.println("RAW RESPONSE: [" + response + "]");
        String[] parts = response.split("\\|", 2);

        String statusRaw = parts[0].trim();   // "SUCCESS:"
        String data = parts.length > 1 ? parts[1].trim() : "";

        // remove the trailing ":" from status
        String status = statusRaw.replace(":", "");

        if (status.equals("FAILURE")) {

            if (data.contains("VALIDATION_ERROR")) {
                System.out.println("Transaction rejected: VALIDATION ERROR");
                return;
            }

            if (data.contains("before execution")) {
                System.out.println("Transaction failed before execution: " + data);
                return;
            }
        }
        switch (operation) {

            case "BALANCE_IST":
                handleBalanceIST(status, data);
                break;

            case "TRANSFER_IST":
                handleBoolResult(status, data, "Transfer");
                break;

            case "TRANSFER_FROM":
                handleBoolResult(status, data, "TransferFrom");
                break;

            case "INCREASE_ALLOWANCE":
                handleBoolResult(status, data, "Increase Allowance");
                break;

            case "DECREASE_ALLOWANCE":
                handleBoolResult(status, data, "Decrease Allowance");
                break;

            case "ALLOWANCE":
                handleAllowance(status, data);
                break;

            default:
                System.out.println("Unknown IST operation: " + operation);
        }    
    }

    private static boolean decodeBool(String hex) {
        if (hex == null || hex.isEmpty()) return false;

        hex = hex.replace("0x", "");

        return hex.endsWith("1");
    }

    private static void handleBalanceIST(String status, String data) {
        if (status.equals("SUCCESS")) {
            BigInteger balance = decodeUint256(data);
            System.out.println("IST Balance: " + balance);
        } else {
            System.out.println("Failed to get IST balance.");
        }
    }

    private static void handleAllowance(String status, String data) {
        if (status.equals("SUCCESS")) {
            BigInteger allowance = decodeUint256(data);
            System.out.println("Allowance: " + allowance);
        } else {
            System.out.println("Failed to get allowance.");
        }
    }

    private static void handleBoolResult(String status, String data, String opName) {
        if (status.equals("SUCCESS")) {
            boolean result = decodeBool(data);

            if (result) {
                System.out.println(opName + " successful.");
            } else {
                System.out.println(opName + " returned false.");
            }
        } else {
            System.out.println(opName + " failed.");
        }
    }

    private static BigInteger decodeUint256(String hex) {
        if (hex == null || hex.isEmpty()) return BigInteger.ZERO;

        hex = hex.replace("0x", "").replace("|", "").trim();

        if (hex.isEmpty()) return BigInteger.ZERO;

        return new BigInteger(hex, 16);
    }
}