package pt.depchain.service;

import pt.depchain.communication.*;
import pt.depchain.crypto.CryptoLibrary;
import pt.depchain.hotstuff.*;
import java.net.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class Node {
    
    private static HotStuffConsensus consensus;
    private static Blockchain blockchain;
    private static Map<String, Integer> pendingClientRequests = new HashMap<>();
    
    private static final ScheduledExecutorService pacemaker = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> timeoutTask; // <--- Add this line
    private static final long VIEW_TIMEOUT_MS = 10000;
    private static boolean isTimerRunning = false;

    private static Map<String, Message> activeRequestsBuffer = new LinkedHashMap<>();

    private static void startPacemaker(Link link, int nodeId) {
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
                    for (Message bufferedMsg : activeRequestsBuffer.values()) {
                        handleAppendRequest(link, nodeId, bufferedMsg);
                    }
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
        if (args.length < 1) {
            System.err.println("Usage: java Node <nodeId>");
            System.exit(1);
        }  

        int nodeId = Integer.parseInt(args[0]);
        
        // Initialize crypto and link
        CryptoLibrary crypto = new CryptoLibrary(
            "../config/node" + nodeId + ".priv",
            "../config/node" + nodeId + ".pub"
        );
        Link link = new Link(nodeId, Link.Type.NODE, "../config/membership.json", 
                            "../config/node" + nodeId + ".priv", 
                            "../config/node" + nodeId + ".pub");
        
        // Initialize blockchain
        blockchain = new Blockchain();
        
        // Initialize consensus (n=4, f=1 for 4 nodes)
        consensus = new HotStuffConsensus(nodeId, 4, 1, link, crypto, blockchain);
        
        // Set up callback for when consensus decides
        consensus.setDecideCallback((decidedNode, view) -> {
            stopPacemaker(); // Stop the timer
            System.out.println("[NODE] Decision reached at view " + view);
            System.out.println(blockchain.getBlockchainState());
            
            String committedCommand = decidedNode.getCommand();
            activeRequestsBuffer.entrySet().removeIf(entry -> 
            entry.getValue().getPayload().contains(committedCommand));
            // Notify clients (in future implementation)
            // For now, just log the decision
        });

        System.out.println("Node " + nodeId + " initialized.");
        System.out.println("Blockchain initialized with genesis block.");
        System.out.println("Starting consensus protocol...\n");
        
        // Start first view
        consensus.startView();

        // Message handling loop
        while (true) {
            Message msg = link.receive();
            handleMessage(link, nodeId, msg);          
        }
    }

     private static void handleMessage(Link link, int nodeId, Message msg) throws Exception {

        switch (msg.getType()) {

            case APPEND_STRING:
                handleAppendRequest(link, nodeId, msg);
                break;
            
            // HotStuff protocol messages
            case NEW_VIEW:
                consensus.handleNewView(msg);
                break;
                
            case PREPARE:
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

            default:
                System.out.println("[NODE] Unknown message type from " + msg.getSenderId());
        }
    }

    private static void handleAppendRequest(Link link, int nodeId, Message msg) throws Exception {
        // client manda mensagem
        // 1-1-1
        // 1-1-2
        // 1-1-3
        // 1-1-4

        // Réplicas enviam new view
        // 2-1
        // 3-1
        // 4-1

        // Réplicas recebem
        
        // Depois Réplicas mandam para o lider
        // 2-2
        // 3-2
        // 4-2



        String command = msg.getPayload();
        JsonObject payloadJson = JsonParser.parseString(msg.getPayload()).getAsJsonObject();
        int clientId = payloadJson.get("clientId").getAsInt();
        int messageId = payloadJson.get("messageId").getAsInt();        
        String stringToAppend = payloadJson.get("text").getAsString();

        System.out.println("[NODE] Node " + nodeId + " received APPEND request from client " + clientId + ": \"" + stringToAppend + "\"");

        // If this node is the leader, queue the command
        String key = clientId + "-" + messageId;
        //System.out.println("[NODE] Checking for duplicate command with key: " + key);
        if (pendingClientRequests.containsKey(key)) {
            System.out.println("[NODE] Duplicate command from client " + clientId + ", ignoring.");
            return;
        }
        else{
            activeRequestsBuffer.put(key, msg);
        }

        if (consensus.isLeader()) {
            consensus.addCommand(stringToAppend, clientId);
    
            // Track which client sent this command (for future response)
            pendingClientRequests.put(key, clientId);
            startPacemaker(link, nodeId);
        } else {
            // Forward to current leader
            int leaderId = ((consensus.getViewNumber() - 1) % 4) + 1;
            //System.out.println("[NODE] Node " + nodeId + " forwarding request to leader " + leaderId);
            link.send(Link.Type.NODE, leaderId, Message.Type.APPEND_STRING, command);
            startPacemaker(link, nodeId); 
            System.out.println("[NODE] Request forwarded. Pacemaker started.");
        }
    }
}
