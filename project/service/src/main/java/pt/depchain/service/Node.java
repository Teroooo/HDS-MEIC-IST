package pt.depchain.service;

import java.io.IOException;
import java.nio.file.Files; 
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.apache.tuweni.crypto.Hash;
import org.checkerframework.checker.units.qual.A;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pt.depchain.communication.Link;
import pt.depchain.communication.Message;
import pt.depchain.communication.Transaction;
import pt.depchain.communication.Block;
import pt.depchain.crypto.CryptoLibrary;
import pt.depchain.hotstuff.Blockchain;
import pt.depchain.hotstuff.HotStuffConsensus;
import pt.depchain.hotstuff.HotStuffMessage;
import org.hyperledger.besu.datatypes.Address;

public class Node {
    
    private static HotStuffConsensus consensus;
    private static Blockchain blockchain;
    private static Map<String, RequestState> pendingClientRequests = new HashMap<>();
    
    private static final ScheduledExecutorService pacemaker = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> timeoutTask; // <--- Add this line
    private static final long VIEW_TIMEOUT_MS = 20000;
    private static boolean isTimerRunning = false;

    private static Map<String, Message> activeRequestsBuffer = new LinkedHashMap<>();

    private static final Gson gson = new Gson();

    private static CryptoLibrary crypto;

    public static long blockGasLimit = 700;


    private static List<Transaction> pendingTransactions = new ArrayList<>();
    private static HashMap<String, Address> addressBook = new HashMap<>();

    enum RequestState {
        PENDING,
        COMPLETED
    }

    private static void startPacemaker(Link link, String nodeId) {
        if (isTimerRunning) return; // Don't restart if already waiting for a proposal
        
        isTimerRunning = true;
        if (timeoutTask != null) timeoutTask.cancel(false);

        timeoutTask = pacemaker.schedule(() -> {
            try {
                System.out.println("[PACEMAKER] View " + consensus.getViewNumber() + 
                                   " timed out after request. Leader is likely dead.");
                isTimerRunning = false;
                // Move to next view
                Thread.sleep(100); 
                consensus.advanceView(); 
                if (consensus.isLeader()) {
                    rebuildMempool();
                    if(!pendingTransactions.isEmpty())
                        startBlockCreationTimer(link, nodeId); 
                }

                // Note: We don't start the timer again yet. 
                // We wait for the next request or retry to trigger it in the new view.
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, VIEW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static void stopPacemaker() {
        if (timeoutTask != null) timeoutTask.cancel(false);
        isTimerRunning = false;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java Node <nodeId> <privateKey> <publicKey>");
            System.exit(1);
        } 

        int nodeIdInt = Integer.parseInt(args[0]);
        String nodeId = args[0];
        String privateKeyPath = args[1];
        String publicKeyPath = args[2];

        // Initialize crypto and link
        crypto = new CryptoLibrary(privateKeyPath, publicKeyPath, nodeId);

        Link link = new Link(nodeId, Link.Type.NODE, "../config/membership.json", privateKeyPath, publicKeyPath, crypto);
        
        // Initialize blockchain
        blockchain = new Blockchain();

        // Initialize consensus
        consensus = new HotStuffConsensus(nodeIdInt, crypto.l, (int) Math.floor((crypto.l-1)/3), link, crypto, blockchain);
        loadAddressBook();
        //addressBook.forEach((clientName, address) -> {
        //    System.out.println("Client: " + clientName + " | Address: " + address.toHexString());
        //});

        // 1. Start receiver thread FIRST
        startReceiverThread(link, nodeId, crypto);

        // 2. Wait for nodes to boot
        System.out.println("[NODE] Waiting for nodes to start...");
        Thread.sleep(5000);

        // 3. Start key exchange
        System.out.println("[NODE] Starting key exchange...");
        initiateKeyExchange(link, nodeId);

        // 4. Wait until all symmetric keys are established
        while (crypto.getSymmetricKeys().size() < crypto.l) {
            System.out.println("[NODE] Waiting for key exchange to complete. Current keys: " 
                + crypto.getSymmetricKeys().keySet());
            Thread.sleep(2000);
        }

        System.out.println("[NODE] Key exchange completed.");

        //PHASE 2: DECIDECALLBACK
        consensus.setDecideCallback((decidedNode, view) -> {
            stopPacemaker();
            System.out.println("[NODE] Decision reached at view " + view);

            blockchain.executeCommittedBranch(decidedNode);
            System.out.println(blockchain.getBlockchainStateWithBlocks());

            List<Transaction> committedTxs = decidedNode.getBlock().getTransactions();
            for (Transaction txReq : committedTxs) {
                Address senderAddress = Address.fromHexString(txReq.getFrom());
                String clientId = getClientIdFromAddress(senderAddress);
                int nonce = txReq.getNonce();

                String txKey = clientId + "-" + nonce;

                

                // 3. Send specialized reply to the SPECIFIC client who sent this TX
                link.send(Link.Type.CLIENT, clientId, Message.Type.REPLY,
                        "Transaction " + txKey + " SUCCESS in block at view " + view);

                // 4. Cleanup buffers for this specific transaction
                Message completedMsg = activeRequestsBuffer.remove(txKey);
                if (completedMsg != null) {
                    pendingClientRequests.put(txKey, RequestState.COMPLETED);
                }
            }

            try {
                boolean hasPending = pendingClientRequests.values()
                        .stream()
                        .anyMatch(s -> s == RequestState.PENDING);

                if (hasPending) {
                    if (consensus.isLeader()) {
                        rebuildMempool();
                        if(!pendingTransactions.isEmpty())
                            startBlockCreationTimer(link, nodeId);
                    } else {
                        startPacemaker(link, nodeId);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        System.out.println("Node " + nodeId + " initialized.");
        consensus.startView();

    }

    private static void startReceiverThread(Link link, String nodeId, CryptoLibrary crypto) {
        new Thread(() -> {
            while (true) {
                try {
                    Message msg = link.receive();
                    handleMessage(link, nodeId, msg, crypto);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

     private static void handleMessage(Link link, String nodeId, Message msg, CryptoLibrary crypto) throws Exception {

        switch (msg.getType()) {
            case TRANSACTION:
                handleTransactionRequest(link, nodeId, msg);
                break;
                
            case APPEND_STRING:
                handleAppendRequest(link, nodeId, msg);
                break;
            
            case NEW_VIEW:
                handleNewView(link, nodeId, msg);
                break;
                
            case PREPARE:
               
                //PHASE 2: TODO PREPARE
                // 1. Parse the HotStuff message and extract the proposed Block
                HotStuffMessage hsmsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
                Block proposedBlock = hsmsg.getProposal().getBlock();
                System.out.println("Pending Transactions: " + pendingTransactions.size());
                System.out.println("proposedBlock: " + proposedBlock);
                System.out.println("activeRequestsBuffer: " + activeRequestsBuffer.toString());
                if (proposedBlock == null || proposedBlock.getTransactions() == null) {
                    System.out.println("[NODE] Received empty or invalid block in PREPARE.");
                    return;
                }
              
                // 2. Iterate through every transaction in the block to verify it
                for (Transaction tx : proposedBlock.getTransactions()) {
                    Address senderAddress = Address.fromHexString(tx.getFrom());
                    String clientId = getClientIdFromAddress(senderAddress);
                    int nonce = tx.getNonce();

                    String txKey = clientId + "-" + nonce;
                    
                    // Check if we have already completed this specific transaction
                    if (pendingClientRequests.get(txKey) == RequestState.COMPLETED) {
                        System.out.println("[NODE] Transaction " + txKey + " already committed, skipping check.");
                        continue;
                    }

                
                    if (!basicValidation(tx)) {
                        System.out.println("[NODE] Invalid transaction detected in PREPARE for " + txKey);
                        return; 
                    }
                                    
                }

                // 4. If all transactions in the block are valid and recognized
                System.out.println("[NODE] PREPARE verified for block with " + proposedBlock.getTransactions().size() + " txs.");
                consensus.handlePrepare(msg);
                break;
                
            case PREPARE_VOTE:
                consensus.handlePrepareVote(msg);
                break;
                
            case PRE_COMMIT:
                consensus.handlePreCommit(msg);
                break;
                
            case PRE_COMMIT_VOTE:
                consensus.handlePreCommitVote(msg);
                break;
                
            case COMMIT:
                consensus.handleCommit(msg);
                break;
                
            case COMMIT_VOTE:
                consensus.handleCommitVote(msg);
                break;
                
            case DECIDE:
                consensus.handleDecide(msg);
                break;
            case KEY_EXCHANGE:
                handleKeyExchange(link, crypto, msg);
                break;
            case KEY_EXCHANGE_REPLY:
                handleKeyExchangeReply(msg, crypto);
                break;
            default:
                System.out.println("[NODE] Unknown message type from " + msg.getSenderId());
        }
    }

    private static void handleNewView(Link link, String nodeId, Message msg) throws Exception {
        consensus.handleNewView(msg);
        // if (consensus.isLeader()) {
        //     proposePendingCommandsIfLeader(link, nodeId);
        // }     
    }

    private static void handleAppendRequest(Link link, String nodeId, Message msg) throws Exception {
        String command = msg.getPayload();
        JsonObject payloadJson = JsonParser.parseString(msg.getPayload()).getAsJsonObject();
        String clientId = payloadJson.get("clientId").getAsString();
        int messageId = payloadJson.get("messageId").getAsInt();        
        String stringToAppend = payloadJson.get("text").getAsString();

        System.out.println("[NODE] Node " + nodeId + " received APPEND request from client " + clientId + ": \"" + stringToAppend + "\"");

        // If this node is the leader, queue the command
        String key = clientId + "-" + messageId;

        RequestState state = pendingClientRequests.get(key);
        //System.out.println("[NODE] Checking for duplicate command with key: " + key);
        if (state == RequestState.COMPLETED) {
            System.out.println("[NODE] Request already completed, ignoring.");
            return;
        }
        if (state == null) {
            pendingClientRequests.put(key, RequestState.PENDING);
            activeRequestsBuffer.put(key, msg);
            System.out.println("[NODE] New request added to pending buffer with key: " + key);
            if (consensus.isLeader()) {
                consensus.addCommand(stringToAppend, key);
                startPacemaker(link, nodeId);
            } else {
                int leaderId = ((consensus.getViewNumber() - 1) % crypto.l) + 1;
                link.send(Link.Type.NODE, String.valueOf(leaderId), Message.Type.APPEND_STRING, command);
                startPacemaker(link, nodeId);
                System.out.println("[NODE] Request forwarded. Pacemaker started.");
            }
            // store command in consensus queue for ALL replicas
        }
    }

    
    //Phase 2: new proposePendingCommands
    private static void proposePendingCommandsIfLeader(Link link, String nodeId) throws Exception {
        if (!consensus.isLeader()) {
            return;
        }

        if (pendingTransactions.isEmpty()) {
            System.out.println("[NODE] Node " + nodeId + " is leader, but mempool is empty. Skipping proposal.");
            return;
        }

        Block newBlock = createBlock();

        System.out.println("[NODE] Node " + nodeId + " (Leader) proposing NEW BLOCK with " 
                            + newBlock.getTransactions().size() + " transactions.");

        // 4. Send the Block to the consensus engine
        // Make sure your HotStuffConsensus.addCommand now accepts a Block object
        // or you might need to rename this to consensus.addBlock(newBlock) 
        // depending on your implementation.
        consensus.addBlock(newBlock);

        // 5. Start the Pacemaker to handle the timeout for this specific proposal
        startPacemaker(link, nodeId);
    }

    public static PublicKey stringToPublicKey(String keyString) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(keyString);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    private static void handleKeyExchange(Link link, CryptoLibrary crypto, Message msg) throws Exception {
        String senderId = msg.getSenderId();
        String senderPublicKeyString = msg.getPayload();

        // Generate symmetric key for this node pair
        SecretKey aesKey = crypto.generateAESKey();

        // Encrypt AES key with sender's public key
        String encryptedKey = crypto.encryptAESKey(aesKey, stringToPublicKey(senderPublicKeyString));

        // Send it back
        link.send(Link.Type.NODE, senderId, Message.Type.KEY_EXCHANGE_REPLY, encryptedKey);

        crypto.addSymmetricKey(senderId, aesKey);
    }

    private static void handleKeyExchangeReply(Message msg, CryptoLibrary crypto) throws Exception {
        String senderId = msg.getSenderId();
        String encryptedKey = msg.getPayload();

        SecretKey aesKey = crypto.decryptAESKey(encryptedKey);
        crypto.addSymmetricKey(senderId, aesKey);
    }

    private static void initiateKeyExchange(Link link, String nodeId) {
        System.out.println(crypto);
        String publicKey = Base64.getEncoder().encodeToString(crypto.getMyPublicKey().getEncoded());

        String[] replicaIds = new String[crypto.l];
        for (int i = 1; i <= crypto.l; i++) {
            replicaIds[i - 1] = String.valueOf(i);
        }
        System.out.println("\n\n\nInitiating key exchange with replicas: " + Arrays.toString(replicaIds) + "\n\n\n");
        try {
            for (String replicaId : replicaIds) {
                if (Integer.parseInt(nodeId) <= Integer.parseInt(replicaId)) {
                    link.send(Link.Type.NODE, replicaId, Message.Type.KEY_EXCHANGE, publicKey);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //phase 2: handle transaction requests from clients
    public static void handleTransactionRequest(Link link, String nodeId, Message msg) throws Exception {
       
        JsonObject payloadJson = JsonParser.parseString(msg.getPayload()).getAsJsonObject();        
        
        String clientId = payloadJson.get("clientId").getAsString();
        int messageId = payloadJson.get("messageId").getAsInt();  

        Transaction tx = gson.fromJson(payloadJson.get("transaction"), Transaction.class);      

        if (!basicValidation(tx) || !checkNonce(tx, clientId)) {
            System.out.println("[NODE] Invalid transaction rejected at ingress");

            String txKey = clientId + "-" + messageId;

            // Reply to client with FAILURE
            link.send(Link.Type.CLIENT, clientId, Message.Type.REPLY, "Transaction " + txKey + " FAILURE (basic validation)");
            return;
        }

        // If this node is the leader, queue the command
        String key = clientId + "-" + messageId;

        RequestState state = pendingClientRequests.get(key);
        //System.out.println("[NODE] Checking for duplicate command with key: " + key);
        if (state == RequestState.COMPLETED) {
            System.out.println("[NODE] Request already completed, ignoring.");
            return;
        }

        if (state == null) {
            pendingClientRequests.put(key, RequestState.PENDING);
            activeRequestsBuffer.put(key, msg);

            if (consensus.isLeader()) {
                //consensus.addCommand(stringToAppend, key);
                pendingTransactions.add(tx);
                startBlockCreationTimer(link, nodeId);            
            } else {
                int leaderId = ((consensus.getViewNumber() - 1) % crypto.l) + 1;
                link.send(Link.Type.NODE, String.valueOf(leaderId), Message.Type.TRANSACTION, msg.getPayload());
                startPacemaker(link, nodeId);
                System.out.println("[NODE] Request forwarded. Pacemaker started.");
            }
            // store command in consensus queue for ALL replicas
        }

        System.out.println("[NODE] Node " + nodeId + " received a Transaction from " + clientId + "\"");

    }

    //Phase 2: create new block based on transaction Fee limit?
    private static Block createBlock(){
        sortMempool();
        //create a new block with transactions from the mempool that fit within the fee limit
        List<Transaction> blockTransactions = new ArrayList<>();
        long totalGas = 0;

        // Track senders who have a transaction that failed to fit
        Set<String> skippedSenders = new HashSet<>();

        Iterator<Transaction> it = pendingTransactions.iterator();
        while (it.hasNext()) {
            Transaction tx = it.next();

            if (skippedSenders.contains(tx.getFrom())) continue;

            long gasUsed = estimateGasUsed(tx);

            if (totalGas + gasUsed <= blockGasLimit) {
                blockTransactions.add(tx);
                totalGas += gasUsed;
                it.remove(); // Removes safely from pendingTransactions
            } else {
                skippedSenders.add(tx.getFrom());
            }
        }
        return new Block(blockchain.getLastCommittedNode().getHash().toString(), blockTransactions);
    }


    //Phase 2: sort transactions before creating a block
    public static void sortMempool() {
        pendingTransactions.sort((a, b) -> {
        // 1. If same sender, strictly follow Nonce order
        if (a.getFrom().equals(b.getFrom())) {
            return Integer.compare(a.getNonce(), b.getNonce());
        }
        
        // 2. If different senders, prioritize the higher fee
        // We use b.fee - a.fee for descending order (highest first)
        return Double.compare(b.getGasPrice(), a.getGasPrice());
        });
    }

    private static ScheduledFuture<?> blockCreationTask;

    private static void startBlockCreationTimer(Link link, String nodeId) {
        // If a timer is already running, don't start another one
        if (blockCreationTask != null && !blockCreationTask.isDone()) return;

        blockCreationTask = pacemaker.schedule(() -> {
            try {
                if (consensus.isLeader() && !pendingTransactions.isEmpty()) {
                    System.out.println("[LEADER] 12 seconds elapsed. Creating block with " 
                                        + pendingTransactions.size() + " txs.");
                    proposePendingCommandsIfLeader(link, nodeId);
                } else {
                    System.out.println("Consesus is leader: " + consensus.isLeader() + 
                                        + pendingTransactions.size() + " txs.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 12, TimeUnit.SECONDS); // The 12-second window your professor mentioned
    }
    
    private static void rebuildMempool() {
        pendingTransactions.clear();
    
        for (Map.Entry<String, Message> entry : activeRequestsBuffer.entrySet()) {
            String key = entry.getKey();
    
            if (pendingClientRequests.get(key) == RequestState.PENDING) {
                JsonObject payload = JsonParser.parseString(entry.getValue().getPayload()).getAsJsonObject();
                Transaction tx = gson.fromJson(payload.get("transaction"), Transaction.class);
    
                pendingTransactions.add(tx);
            }
        }
    }


    //To do: nonces check, altering sig check based on the new addresses that will be hash(publicKey) instead of clientId
    private static boolean basicValidation(Transaction tx) {
        if (tx == null) return false;

        if (tx.getFrom() == null || tx.getTo() == null) return false;


        if (tx.getData() == null) return false;

        if (tx.getGasPrice() <= 0) return false;
        if (tx.getGasLimit() <= 0) return false;

        if (tx.getData() == null) return false;
        
        if (tx.getSignature() == null) return false;
        try {
            Transaction txCopy = new Transaction(
                tx.getType(),
                tx.getFrom(),
                tx.getTo(),
                tx.getData(),
                tx.getGasPrice(),
                tx.getGasLimit(),
                tx.getNonce(),
                null
            );
            String txString = gson.toJson(txCopy);
            byte[] data = txString.getBytes();
            Address senderAddress = Address.fromHexString(tx.getFrom());
            String clientId = getClientIdFromAddress(senderAddress);
            boolean valid = crypto.verify(data, tx.getSignature(), "CLIENT-" + clientId);
            if (!valid) {
                System.out.println("[NODE] Signature verification failed for transaction from " + clientId);
                return false;
            }
            
        } catch (Exception e) {
            System.out.println("[NODE] Exception during signature verification: " + e.getMessage());
            return false;
        }
        
        if (tx.getType().equals("DEP")) {
            System.out.println("[NODE] Validating 1");

            String dataString;
            try {
                dataString = new String(tx.getData());
            } catch (Exception e) {
                return false;
            }
            System.out.println("[NODE] Validating 2");
            String[] parts = dataString.split("\\|");
            if (parts.length < 1) return false;

            String operation = parts[0];
            switch (operation) {

                case "TRANSFER_DEP": {
                    if (parts.length != 2) return false;
                    try {
                        long amount = Long.parseLong(parts[1]);
                        if (amount <= 0) return false;
                    } catch (Exception e) {
                        return false;
                    }

                    return true;
                }

                case "BALANCE_DEP": {
                    if (parts.length != 1) return false;
                    return true;
                }

                default:
                    System.out.println("[NODE] Validating DEFAULT");
                    return false;
            }
           
        } 
        return true;
    }

    private static boolean checkNonce(Transaction tx, String clientId) {
        Address sender = Address.fromHexString(tx.getFrom());
        long currentNonce = blockchain.getNonce(sender);
        System.out.println("[NODE] Checking nonce for transaction from " + clientId + ": expected " + (currentNonce + 1) + ", got " + tx.getNonce());

        return tx.getNonce() == currentNonce + 1;
    }

    private static long estimateGasUsed(Transaction tx) {
        if (tx.getType().equals("IST")) {
            return 75; 
        }

        String dataString = new String(tx.getData());

        String[] parts = dataString.split("\\|");
        String operation = parts[0];

        switch (operation) {
            case "TRANSFER_DEP":
                return 75;

            default:
                return 30;
        }
    }

    public static void loadAddressBook() {
        Path configDir = Paths.get("..", "config");

        // Ensure the directory exists to avoid crashes
        if (!Files.exists(configDir)) {
            System.err.println("Config directory not found at: " + configDir.toAbsolutePath());
            return;
        }

        try (Stream<Path> stream = Files.list(configDir)) {
            stream
                .filter(file -> !Files.isDirectory(file))
                .filter(file -> file.toString().endsWith(".pub"))
                .forEach(file -> {
                    String fileName = file.getFileName().toString();
                    String clientName = fileName.substring(0, fileName.lastIndexOf('.'));

                    try {
                        // 1. READ the file content here
                        String content = Files.readString(file);
                        
                        // 2. PASS the content (not the name) to normalize
                        String hexAddress = normalizeAddressHex(content);
                        
                        // 3. STORE in the address book
                        addressBook.put(clientName, Address.fromHexString(hexAddress));
                        
                        System.out.println("Loaded: " + clientName + " -> " + hexAddress);
                        System.out.println("Address book entry: " + clientName + " -> " + addressBook.get(clientName).toHexString());
                    } catch (Exception e) {
                        System.err.println("Skipping " + fileName + " due to error: " + e.getMessage());
                    }
                });
        } catch (IOException e) {
            System.err.println("Could not read config directory: " + e.getMessage());
        }
    }

    public static String normalizeAddressHex(String publicKeyContent) {
        // 1. Clean the PEM string: remove headers, footers, and ALL whitespace
        String cleanBase64 = publicKeyContent
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", ""); 

        // 2. Decode the Base64 to get the raw DER bytes
        byte[] derBytes = Base64.getDecoder().decode(cleanBase64);

        // 3. Hash the bytes
        String hex;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(derBytes);
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            hex = sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }

        // 4. Existing length logic
        if (hex.length() == 64) {
            return hex.substring(0, 40); 
        }

        if (hex.length() == 40) {
            return hex;
        }

        throw new IllegalArgumentException("Invalid address key length derived: " + hex.length());
    }

    private static String getClientIdFromAddress(Address address) {
        for (Map.Entry<String, Address> entry : addressBook.entrySet()) {
            if (entry.getValue().equals(address)) {
                return entry.getKey();
            }
        }
        return null;
    }

}

