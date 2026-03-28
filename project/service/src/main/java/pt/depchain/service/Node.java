package pt.depchain.service;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pt.depchain.communication.Link;
import pt.depchain.communication.Message;
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
    private static final long VIEW_TIMEOUT_MS = 10000;
    private static boolean isTimerRunning = false;

    private static Map<String, Message> activeRequestsBuffer = new LinkedHashMap<>();

    private static Map<String, Message> bufferedPrepare = new HashMap<>();
    private static final Gson gson = new Gson();

    private static CryptoLibrary crypto;

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
                consensus.advanceView(); 
                if (consensus.isLeader()) {
                    proposePendingCommandsIfLeader(link, nodeId); 
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

        // ✅ 1. Start receiver thread FIRST
        startReceiverThread(link, nodeId, crypto);

        // ✅ 2. Wait for nodes to boot
        System.out.println("[NODE] Waiting for nodes to start...");
        Thread.sleep(10000);

        // ✅ 3. Start key exchange
        System.out.println("[NODE] Starting key exchange...");
        initiateKeyExchange(link, nodeId);

        // ✅ 4. Wait until all symmetric keys are established
        while (crypto.getSymmetricKeys().size() < crypto.l) {
            System.out.println("[NODE] Waiting for key exchange to complete. Current keys: " 
                + crypto.getSymmetricKeys().keySet());
            Thread.sleep(2000);
        }

        System.out.println("[NODE] Key exchange completed.");

        // Initialize blockchain
        blockchain = new Blockchain();

        // Initialize consensus
        consensus = new HotStuffConsensus(nodeIdInt, crypto.l, (int) Math.floor((crypto.l-1)/3), link, crypto, blockchain);

        // Set up callback
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

            case APPEND_STRING:
                handleAppendRequest(link, nodeId, msg);
                break;
            
            case NEW_VIEW:
                handleNewView(link, nodeId, msg);
                break;
                
            case PREPARE:
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
                int leaderId = ((consensus.getViewNumber() - 1) % 4) + 1;
                link.send(Link.Type.NODE, String.valueOf(leaderId), Message.Type.APPEND_STRING, command);
                startPacemaker(link, nodeId);
                System.out.println("[NODE] Request forwarded. Pacemaker started.");
            }
            // store command in consensus queue for ALL replicas
        }
    }

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

}
