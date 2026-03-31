package pt.depchain.service;

import java.security.KeyFactory;
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

import javax.crypto.SecretKey;

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

public class Node {
    
    private static HotStuffConsensus consensus;
    private static Blockchain blockchain;
    private static Map<String, RequestState> pendingClientRequests = new HashMap<>();
    
    private static final ScheduledExecutorService pacemaker = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> timeoutTask; // <--- Add this line
    private static final long VIEW_TIMEOUT_MS = 20000;
    private static boolean isTimerRunning = false;

    private static Map<String, Message> activeRequestsBuffer = new LinkedHashMap<>();

    private static Map<String, Message> bufferedPrepare = new HashMap<>();
    private static final Gson gson = new Gson();

    private static CryptoLibrary crypto;

    public static long transactionFeeLimit = 700;


    private static List<Transaction> pendingTransactions = new ArrayList<>();

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

        // Set up callback for commands
        /*
                consensus.setDecideCallback((decidedNode, view) -> {
            stopPacemaker();
            System.out.println("[NODE] Decision reached at view " + view);

            blockchain.executeCommittedBranch(decidedNode);
            System.out.println(blockchain.getBlockchainState());

            String requestKey = decidedNode.getRequestKey();

            link.send(Link.Type.CLIENT, "client1", Message.Type.REPLY,
                    "message " + requestKey + " SUCCESS in view " + view);

            Message completedMsg = activeRequestsBuffer.remove(requestKey);
            if (completedMsg != null) {
                pendingClientRequests.put(requestKey, RequestState.COMPLETED);
            }

            try {
                boolean hasPending = pendingClientRequests.values()
                        .stream()
                        .anyMatch(s -> s == RequestState.PENDING);

                if (hasPending) {
                    if (consensus.isLeader()) {
                        proposePendingCommandsIfLeader(link, nodeId);
                    } else {
                        startPacemaker(link, nodeId);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        */

        //PHASE 2: DECIDECALLBACK
        consensus.setDecideCallback((decidedNode, view) -> {
            stopPacemaker();
            System.out.println("[NODE] Decision reached at view " + view);

            blockchain.executeCommittedBranch(decidedNode);
            System.out.println(blockchain.getBlockchainStateWithBlocks());

            List<Transaction> committedTxs = decidedNode.getBlock().getTransactions();
            for (Transaction txReq : committedTxs) {
                String sender = txReq.getFrom();
                int nonce = txReq.getNonce();

                String txKey = sender + "-" + nonce;

                String clientId = txReq.getFrom(); // Extract sender ID from the Transaction object

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
                /*
                HotStuffMessage hsmsg =  gson.fromJson(msg.getPayload(), HotStuffMessage.class);
                String requestKey = hsmsg.getProposal().getRequestKey();
                String command = hsmsg.getProposal().getCommand();

                Message clientMsg = activeRequestsBuffer.get(requestKey);

                RequestState state = pendingClientRequests.get(requestKey);

                if (clientMsg == null) {

                    if (state == RequestState.COMPLETED) {
                        System.out.println("[NODE] Ignoring stale PREPARE for " + requestKey);
                        return;
                    }
                    
                    System.out.println("[NODE] Missing request " + requestKey + ", buffering PREPARE");
                    bufferedPrepare.put(requestKey, msg);
                    return;
                }

                JsonObject payloadJson = JsonParser.parseString(clientMsg.getPayload()).getAsJsonObject();

                String clientCommand = payloadJson.get("text").getAsString();

                if (!clientCommand.equals(command)) {
                    System.out.println("[NODE] Byzantine leader detected: command mismatch for " + requestKey);
                    return; // do not vote
                }
                consensus.handlePrepare(msg);
                break;
                */
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
                    String sender = tx.getFrom();
                    int nonce = tx.getNonce();

                    String txKey = sender + "-" + nonce;
                    
                    // Check if we have already completed this specific transaction
                    if (pendingClientRequests.get(txKey) == RequestState.COMPLETED) {
                        System.out.println("[NODE] Transaction " + txKey + " already committed, skipping check.");
                        continue;
                    }

                    // Check if we even have the original client message for this transaction
                    Message clientMsg = activeRequestsBuffer.get(txKey);
                    
                    if (clientMsg == null) {
                        // OPTIONAL: In a robust BFT system, if you are missing a TX, 
                        // you might buffer the PREPARE or request the missing TX from the leader.
                        System.out.println("[NODE] Missing client request for " + txKey + ". Buffering PREPARE.");
                        bufferedPrepare.put(txKey, msg); 
                        return; // Exit: we cannot vote on a block if we don't know the contents
                    }

                    // 3. Byzantine Check: Verify the transaction in the block matches our buffer
                    // Parse the original client request to compare
                    JsonObject clientPayload = JsonParser.parseString(clientMsg.getPayload()).getAsJsonObject();
                    Transaction originalTx = gson.fromJson(clientPayload.get("transaction"), Transaction.class);

                    // Compare relevant fields (e.g., amount/input, dest, and nonce)
                    if (originalTx.getNonce() != tx.getNonce() || !originalTx.getOperation().equals(tx.getOperation()) || !Arrays.equals(originalTx.getArgs(), tx.getArgs())) {
                        System.out.println("[NODE] Byzantine leader detected: Data mismatch for " + txKey);
                        return; // Refuse to vote
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
            case SINC_VIEW_REQUEST:
                consensus.handleSincView(link, msg);
                break;
            case SINC_VIEW_REPLY:
                consensus.handleSincViewReply(link, msg); 
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
            Message buffered = bufferedPrepare.remove(key);
            if (buffered != null) {
                System.out.println("[NODE] Processing buffered PREPARE for " + key);
                HotStuffMessage hsmsg = gson.fromJson(buffered.getPayload(), HotStuffMessage.class);
                String proposedCommand = hsmsg.getProposal().getCommand();
                if (!proposedCommand.equals(stringToAppend)) {
                    System.out.println("[NODE] Byzantine leader detected: command mismatch for " + key);
                } else {
                    consensus.handlePrepare(buffered);
                }
            }
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

    /*
        private static void proposePendingCommandsIfLeader(Link link, String nodeId) throws Exception {

        // Look for the first pending command
        for (Map.Entry<String, Message> entry : activeRequestsBuffer.entrySet()) {
            String key = entry.getKey();
            RequestState state = pendingClientRequests.get(key);

            if (state == RequestState.PENDING) {
                JsonObject payloadJson = JsonParser.parseString(entry.getValue().getPayload()).getAsJsonObject();
                String clientId = payloadJson.get("clientId").getAsString();
                int messageId = payloadJson.get("messageId").getAsInt();
                String text = payloadJson.get("text").getAsString();

                // Add it to consensus to propose
                System.out.println("[NODE] Node " + nodeId + " (new leader) proposing pending command " + key);
                consensus.addCommand(text, clientId + "-" + messageId);
                startPacemaker(link, nodeId);
                break; // propose one command at a time per view
            }
        }
    }
    */

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

        if (!basicValidation(tx)) {
            System.out.println("[NODE] Invalid transaction rejected at ingress");

            String txKey = clientId + "-" + messageId;

            // Reply to client with FAILURE
            link.send(Link.Type.CLIENT, clientId, Message.Type.REPLY, "Transaction " + txKey + " FAILURE (basic validation)");
            return;
        }

        String operation = tx.getOperation();

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

            Message delayedPrepare = bufferedPrepare.remove(key);
            if (delayedPrepare != null) {
                System.out.println("[NODE] Transaction " + key + " arrived! Resuming buffered PREPARE.");
                // Re-trigger the PREPARE handling now that we have the data
                handleMessage(link, nodeId, delayedPrepare, crypto);
            }
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

        System.out.println("[NODE] Node " + nodeId + " received a Transaction from client " + clientId + " with Operation " + tx.getOperation() + "\"");

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

            long fee = estimateFee(tx);
            if (totalGas + fee <= transactionFeeLimit) {
                blockTransactions.add(tx);
                totalGas += fee;
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

    private static boolean basicValidation(Transaction tx) {
        if (tx == null) return false;

        if (tx.getOperation() == null) return false;
        if (tx.getArgs() == null) return false;

        if (tx.getGasPrice() <= 0) return false;
        if (tx.getGasLimit() <= 0) return false;

        switch (tx.getOperation()) {

            case "TRANSFER_DEP":
            case "TRANSFER_IST": {
                String[] args = tx.getArgs();
                if (args.length != 2) return false;

                String to = args[0];
                String amountStr = args[1];

                if (!isValidAddress(to)) return false;

                try {
                    long amount = Long.parseLong(amountStr);
                    if (amount <= 0) return false;
                } catch (Exception e) {
                    return false;
                }

                return true;
            }

            case "TRANSFERFROM": {
                String[] args = tx.getArgs();
                if (args.length != 3) return false;

                String from = args[0];
                String to = args[1];
                String amountStr = args[2];

                if (!isValidAddress(from) || !isValidAddress(to)) return false;

                try {
                    long amount = Long.parseLong(amountStr);
                    if (amount <= 0) return false;
                } catch (Exception e) {
                    return false;
                }

                return true;
            }

            case "INCREASE_ALLOWANCE": 
            case "DECREASE_ALLOWANCE": {
                String[] args = tx.getArgs();
            if (args.length != 2) return false;

            String spender = args[0];
            String amountStr = args[1];

            if (!isValidAddress(spender)) return false;

            try {
                long amount = Long.parseLong(amountStr);
                if (amount <= 0) return false;
            } catch (Exception e) {
                return false;
            }

            return true;
            }

            case "ALLOWANCE": {
                String[] args = tx.getArgs();
                if (args.length != 2) return false;

                String owner = args[0];
                String spender = args[1];

                if (!isValidAddress(owner) || !isValidAddress(spender)) return false;

                return true;
            }
            case "BALANCE_DEP":
            case "BALANCE_IST": {
                String[] args = tx.getArgs();
                if (args.length != 0) return false;

                return true;
            }

            default:
                return false;
        }

    }

    private static boolean isValidAddress(String addr) {
        if (addr == null) return false;
        if (addr.isEmpty()) return false;

        return addr.matches("[a-zA-Z0-9_-]+");
    }

    private static long estimateFee(Transaction tx) {
        return tx.getGasPrice() * estimateGasUsed(tx);
    }

    private static long estimateGasUsed(Transaction tx) {
        switch (tx.getOperation()) {

            case "TRANSFER_DEP":
            case "TRANSFER_IST":
                return 50;

            case "TRANSFERFROM":
                return 70;

            case "INCREASE_ALLOWANCE":
            case "DECREASE_ALLOWANCE":
                return 40;

            case "ALLOWANCE":
            case "BALANCE_DEP":
            case "BALANCE_IST":
                return 20;

            default:
                return 0;
        }
    }

}

