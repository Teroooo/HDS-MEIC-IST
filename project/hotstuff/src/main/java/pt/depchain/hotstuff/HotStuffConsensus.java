package pt.depchain.hotstuff;

import com.google.gson.Gson;
import pt.depchain.communication.Link;
import pt.depchain.communication.Message;
import pt.depchain.crypto.CryptoLibrary;
import threshsig.SigShare;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.nio.charset.StandardCharsets;
/**
 * Implementation of Basic HotStuff consensus algorithm (Algorithm 2).
 * This is Step 3: without timeout/failure detection, assuming honest nodes.
 */
public class HotStuffConsensus {
    
    private final int myId;
    private final int n; // total number of nodes
    private final int f; // number of Byzantine faults tolerated
    private final Link link;
    private final CryptoLibrary crypto;
    private final Blockchain blockchain;
    private final Gson gson = new Gson();
    
    // Protocol state variables (from Algorithm 2)
    private int viewNumber;
    private QuorumCertificate prepareQC;
    private QuorumCertificate lockedQC;
    private TreeNode currentProposal;
    private boolean prepareStarted = false;
    private boolean precommitStarted = false;
    private boolean commitStarted = false;
    private boolean decideStarted = false;


    // data for each phase


    // Vote collection for current view
    private final Map<Integer, HotStuffMessage> newViewMessages = new ConcurrentHashMap<>();
    private final Map<Integer, HotStuffMessage> prepareVotes = new ConcurrentHashMap<>();
    private final Map<Integer, HotStuffMessage> preCommitVotes = new ConcurrentHashMap<>();
    private final Map<Integer, HotStuffMessage> commitVotes = new ConcurrentHashMap<>();
    
    // Command queue (for leader)
    private final Queue<CommandRequest> pendingCommands = new LinkedBlockingQueue<>();

    // Callback for when consensus decides
    private DecideCallback decideCallback;

    public void advanceView() throws Exception {
        this.viewNumber++;
        // ... clear maps ...
        prepareStarted = false;
        precommitStarted = false;
        commitStarted = false;
        decideStarted = false;
        
        this.startView();
        
        // If I am the NEW leader, check if I have commands to propose immediately
             
    }
    
    public HotStuffConsensus(int myId, int n, int f, Link link, CryptoLibrary crypto, Blockchain blockchain) {
        this.myId = myId;
        this.n = n;
        this.f = f;
        this.link = link;
        this.crypto = crypto;
        this.blockchain = blockchain;
        this.viewNumber = 1;
        this.prepareQC = null;
        this.lockedQC = null;
    }
    
    public void setDecideCallback(DecideCallback callback) {
        this.decideCallback = callback;
    }

    /**
     * Start the consensus for a new view
     */
    public void startView() throws Exception {
        System.out.println("[CONSENSUS] Node " + myId + " starting view " + viewNumber);
        
        // Clear vote collection from previous view
        prepareVotes.clear();
        preCommitVotes.clear();
        commitVotes.clear();
        
        int leader = getLeader(viewNumber);
        
        // Send NEW_VIEW message to leader
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setQc(prepareQC);
        
        String payload = gson.toJson(hsMsg);
        link.send(Link.Type.NODE, leader, Message.Type.NEW_VIEW, payload);
        
        System.out.println("[CONSENSUS] Node " + myId + " sent NEW_VIEW to leader " + leader);
    }
    
    /**
     * Handle incoming NEW_VIEW message (leader only)
     */
    public void handleNewView(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        newViewMessages.put(msg.getSenderId(), hsMsg);
        
        //System.out.println("[CONSENSUS] Leader " + myId + " received NEW_VIEW from node " + msg.getSenderId() 
        //                 + " (collected " + newViewMessages.size() + "/" + (n-f) + ")");
        
        // Start PREPARE phase when we reach exactly (n-f) NEW_VIEW messages
        System.out.println("Current NEW_VIEW messages: " + newViewMessages.size());
        if (isLeader() && newViewMessages.size() == (n - f)) {
            System.out.println("Entrei aqui2");
            runPreparePhase();
        }
    }
    
    /**
     * PREPARE phase (leader proposes, replicas vote)
     */
    private void runPreparePhase() throws Exception {
        if (!isLeader() || prepareStarted) return;

        prepareStarted = true;

        // Find highQC (highest QC among NEW_VIEW messages)
        QuorumCertificate highQC = null;
        for (HotStuffMessage msg : newViewMessages.values()) {
            if (msg.getQc() != null) {
                if (highQC == null || msg.getQc().getViewNumber() > highQC.getViewNumber()) {
                    highQC = msg.getQc();
                }
            }
        }
        
        // Create new proposal extending from highQC
        TreeNode parent;
        if (highQC != null) {
            parent = blockchain.getNode(highQC.getNodeHash());
        } else {
            parent = blockchain.getLastCommittedNode();
        }
        System.out.println("alo3");
        System.out.println("currentProposal: " + currentProposal);
        if (currentProposal != null) {
            // Re-propose the previous proposal (crash recovery)
            pendingCommands.removeIf(cmd -> cmd.requestKey.equals(currentProposal.getRequestKey()));
            System.out.println("[CONSENSUS] Re-proposing previous proposal: " + currentProposal);
        } else {
            // Take a new command from the pending queue
            CommandRequest cmdReq = pendingCommands.poll();
            if (cmdReq == null) {
                prepareStarted = false;
                System.out.println("[CONSENSUS] No commands to propose, waiting...");
                return; // nothing to propose
            }
            currentProposal = new TreeNode(cmdReq.command, cmdReq.requestKey, parent.getHash(), viewNumber);
            blockchain.addNode(currentProposal);
        }
        
        System.out.println("[CONSENSUS] Leader " + myId + " running PREPARE phase for view " + viewNumber);
                
        // Broadcast PREPARE message
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setProposal(currentProposal);
        hsMsg.setQc(highQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            link.send(Link.Type.NODE, nodeId, Message.Type.PREPARE, payload);
        }
    }
    
    /**
     * Handle incoming PREPARE message (all replicas)
     */
    public void handlePrepare(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        //System.out.println("[CONSENSUS] Node " + myId + " received PREPARE from leader " + msg.getSenderId() + ": " + proposal);
        
        blockchain.addNode(proposal);
        currentProposal = proposal;
        
        // Check if safe to accept (safeNode predicate)
        if (safeNode(proposal, justify)) {
            // Vote for this proposal
            SigShare voteSignature = crypto.signShare(createVoteData(viewNumber, Message.Type.PREPARE_VOTE, proposal.getHash()));
            
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(proposal.getHash());
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
            
            //System.out.println("[CONSENSUS] Node " + myId + " voted PREPARE for " + proposal);
        } else {
            //System.out.println("[CONSENSUS] Node " + myId + " rejected PREPARE (safeNode failed)");
        }
    }
    
    /**
     * Handle incoming PREPARE_VOTE (leader only)
     */
    public void handlePrepareVote(Message msg) throws Exception {
        if (!isLeader()) return;
        
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        prepareVotes.put(msg.getSenderId(), hsMsg);
        
        System.out.println("[CONSENSUS] Leader " + myId + " received PREPARE_VOTE from node " + msg.getSenderId()
                        + " (collected " + prepareVotes.size() + "/" + (n-f) + ")");
        if(prepareVotes.size() >= (n - f)) {
            SigShare[] sigSharesArray = prepareVotes.values().stream().map(HotStuffMessage::getVoteSignature).toArray(SigShare[]::new); 
            try {       
                List<HotStuffMessage> msgsList = new ArrayList<>(prepareVotes.values());
                HotStuffMessage firstMsg = msgsList.get(0);
                HotStuffMessage lastMsg = msgsList.get(msgsList.size() - 1);
                /*System.out.println("entrou aqui: " + new String(jsonToVerify.getBytes()));
                
                for(SigShare s : sigSharesArray) {
                    System.out.println("Share from node " + s);
                }*/
                Map<Integer, SigShare> sigSharesMap = new HashMap<>();
                for (Map.Entry<Integer, HotStuffMessage> entry : prepareVotes.entrySet()) {
                    sigSharesMap.put(entry.getKey(), entry.getValue().getVoteSignature());
                }

                byte[] dataFirst = createVoteData(firstMsg.getViewNumber(), Message.Type.PREPARE_VOTE, firstMsg.getNodeHash());
                byte[] dataLast = createVoteData(firstMsg.getViewNumber(), Message.Type.PREPARE_VOTE, lastMsg.getNodeHash());
        
                if (verifyThresholdVote(sigSharesMap, dataFirst) || verifyThresholdVote(sigSharesMap, dataLast)) {
                    //System.out.println("Threshold signature successful");
                    runPreCommitPhase();
                } else {
                    if(prepareVotes.size() == n)
                        System.out.println("Threshold signature verification FAILED");
                    else
                        System.out.println("Threshold signature verification FAILED (still waiting for votes)");
                }
            } catch (Exception ex) {
                System.out.println("Threshold signature verification error: " + ex.getMessage());
                return;
            }
        }
       
    }
    
    /**
     * PRE-COMMIT phase
     */
    private void runPreCommitPhase() throws Exception {
        if (!isLeader() || precommitStarted) return;
        precommitStarted = true;

        System.out.println("[CONSENSUS] Leader " + myId + " running PRE-COMMIT phase");
        
        // Create prepareQC
        prepareQC = new QuorumCertificate(QuorumCertificate.QCType.PREPARE, viewNumber, currentProposal.getHash());
        for (Map.Entry<Integer, HotStuffMessage> entry : prepareVotes.entrySet()) {
            prepareQC.addVote(entry.getKey(), entry.getValue().getVoteSignature());
        }
        
        System.out.println("[CONSENSUS] Leader " + myId + " created " + prepareQC);
        
        // Broadcast PRE_COMMIT message
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setQc(prepareQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            link.send(Link.Type.NODE, nodeId, Message.Type.PRE_COMMIT, payload);
        }
        // System.out.println("[TEST] Sleeping for 2 seconds before Commit. Kill this process now to simulate crash.");
        // Thread.sleep(2000);
        // System.out.println("[TEST] Woke up, now sending Commit. If you killed the process before, this won't happen.");
    }
    
    /**
     * Handle incoming PRE_COMMIT message (all replicas)
     */
    public void handlePreCommit(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        QuorumCertificate qc = hsMsg.getQc();
        
        if (qc != null && qc.matches(QuorumCertificate.QCType.PREPARE, viewNumber)) {
            prepareQC = qc;

            // System.out.println("[CONSENSUS] Node " + myId + " received valid PRE_COMMIT with " + qc);

            // Vote pre-commit
            SigShare voteSignature = crypto.signShare(createVoteData(viewNumber, Message.Type.PRE_COMMIT_VOTE, qc.getNodeHash()));

            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(qc.getNodeHash());
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);

            
            String payload = gson.toJson(voteMsg);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PRE_COMMIT_VOTE, payload);

            // System.out.println("[CONSENSUS] Node " + myId + " voted PRE_COMMIT");
        }
    }
    
    /**
     * Handle incoming PRE_COMMIT_VOTE (leader only)
     */
    public void handlePreCommitVote(Message msg) throws Exception {
        if (!isLeader()) return;
        
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        preCommitVotes.put(msg.getSenderId(), hsMsg);
        
        System.out.println("[CONSENSUS] Leader " + myId + " received PRE_COMMIT_VOTE from node " + msg.getSenderId()
                          + " (collected " + preCommitVotes.size() + "/" + (n-f) + ")");
        if(preCommitVotes.size() >= (n - f)) {
            SigShare[] sigSharesArray = preCommitVotes.values().stream().map(HotStuffMessage::getVoteSignature).toArray(SigShare[]::new); 
            try {       
                List<HotStuffMessage> msgsList = new ArrayList<>(preCommitVotes.values());
                HotStuffMessage firstMsg = msgsList.get(0);
                HotStuffMessage lastMsg = msgsList.get(msgsList.size() - 1);
                /*System.out.println("entrou aqui: " + new String(jsonToVerify.getBytes()));
                
                for(SigShare s : sigSharesArray) {
                    System.out.println("Share from node " + s);
                }*/
                Map<Integer, SigShare> sigSharesMap = new HashMap<>();
                for (Map.Entry<Integer, HotStuffMessage> entry : preCommitVotes.entrySet()) {
                    sigSharesMap.put(entry.getKey(), entry.getValue().getVoteSignature());
                }

                byte[] dataFirst = createVoteData(firstMsg.getViewNumber(), Message.Type.PRE_COMMIT_VOTE, firstMsg.getNodeHash());
                byte[] dataLast = createVoteData(firstMsg.getViewNumber(), Message.Type.PRE_COMMIT_VOTE, lastMsg.getNodeHash());
        
                if (verifyThresholdVote(sigSharesMap, dataFirst) || verifyThresholdVote(sigSharesMap, dataLast)) {
                    //System.out.println("Threshold signature successful");
                    runCommitPhase();
                } else {
                    if(preCommitVotes.size() == n)
                        System.out.println("Threshold signature verification FAILED");
                    else
                        System.out.println("Threshold signature verification FAILED (still waiting for votes)");
                }
            } catch (Exception ex) {
                System.out.println("Threshold signature verification error: " + ex.getMessage());
                return;
            }
        }
        // Advance to next phase when we reach exactly (n-f) votes
    }
    
    /**
     * COMMIT phase
     */
    private void runCommitPhase() throws Exception {
        if (!isLeader() || commitStarted) return;
        commitStarted = true;

        System.out.println("[CONSENSUS] Leader " + myId + " running COMMIT phase");
        
        // Create precommitQC
        QuorumCertificate precommitQC = new QuorumCertificate(QuorumCertificate.QCType.PRE_COMMIT, viewNumber, currentProposal.getHash());
        for (Map.Entry<Integer, HotStuffMessage> entry : preCommitVotes.entrySet()) {
            precommitQC.addVote(entry.getKey(), entry.getValue().getVoteSignature());
        }
        
        //System.out.println("[CONSENSUS] Leader " + myId + " created " + precommitQC);
        
        // Broadcast COMMIT message
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setQc(precommitQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            link.send(Link.Type.NODE, nodeId, Message.Type.COMMIT, payload);
        }
        // System.out.println("[TEST] Sleeping for 2 seconds before DECIDE. Kill this process now to simulate crash.");
        // Thread.sleep(2000);
        // System.out.println("[TEST] Woke up, now sending DECIDE. If you killed the process before, this won't happen.");
    }
    
    /**
     * Handle incoming COMMIT message (all replicas)
     */
    public void handleCommit(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        QuorumCertificate qc = hsMsg.getQc();
        
        if (qc != null && qc.matches(QuorumCertificate.QCType.PRE_COMMIT, viewNumber)) {
            // Lock on this QC (Line 25 of Algorithm 2)
            lockedQC = qc;
            
            //System.out.println("[CONSENSUS] Node " + myId + " locked on " + qc);
            
            // Vote commit
            SigShare voteSignature = crypto.signShare(createVoteData(viewNumber, Message.Type.COMMIT_VOTE, qc.getNodeHash()));
            
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(qc.getNodeHash());
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);

            
            String payload = gson.toJson(voteMsg);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.COMMIT_VOTE, payload);
            
            //System.out.println("[CONSENSUS] Node " + myId + " voted COMMIT");
        }
    }
    
    /**
     * Handle incoming COMMIT_VOTE (leader only)
     */
    public void handleCommitVote(Message msg) throws Exception {
        if (!isLeader()) return;
        
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        commitVotes.put(msg.getSenderId(), hsMsg);
        
        System.out.println("[CONSENSUS] Leader " + myId + " received COMMIT_VOTE from node " + msg.getSenderId()
                         + " (collected " + commitVotes.size() + "/" + (n-f) + ")");
        if(commitVotes.size() >= (n - f)) {
            SigShare[] sigSharesArray = commitVotes.values().stream().map(HotStuffMessage::getVoteSignature).toArray(SigShare[]::new); 
            try {       
                List<HotStuffMessage> msgsList = new ArrayList<>(commitVotes.values());
                HotStuffMessage firstMsg = msgsList.get(0);
                HotStuffMessage lastMsg = msgsList.get(msgsList.size() - 1);
                /*System.out.println("entrou aqui: " + new String(jsonToVerify.getBytes()));
                
                for(SigShare s : sigSharesArray) {
                    System.out.println("Share from node " + s);
                }*/
                Map<Integer, SigShare> sigSharesMap = new HashMap<>();
                for (Map.Entry<Integer, HotStuffMessage> entry : commitVotes.entrySet()) {
                    sigSharesMap.put(entry.getKey(), entry.getValue().getVoteSignature());
                }

                byte[] dataFirst = createVoteData(firstMsg.getViewNumber(), Message.Type.COMMIT_VOTE, firstMsg.getNodeHash());
                byte[] dataLast = createVoteData(firstMsg.getViewNumber(), Message.Type.COMMIT_VOTE, lastMsg.getNodeHash());
        
                if (verifyThresholdVote(sigSharesMap, dataFirst) || verifyThresholdVote(sigSharesMap, dataLast)) {
                    //System.out.println("Threshold signature successful");
                    runDecidePhase();
                } else {
                    if(commitVotes.size() == n)
                        System.out.println("Threshold signature verification FAILED");
                    else
                        System.out.println("Threshold signature verification FAILED (still waiting for votes)");
                }
            } catch (Exception ex) {
                System.out.println("Threshold signature verification error: " + ex.getMessage());
                return;
            }
        }
        // Advance to next phase when we reach exactly (n-f) votes
    }
    
    /**
     * DECIDE phase
     */
    private void runDecidePhase() throws Exception {
        if (!isLeader() || decideStarted) return;
        decideStarted = true;
        System.out.println("[CONSENSUS] Leader " + myId + " running DECIDE phase");
        
        // Create commitQC
        QuorumCertificate commitQC = new QuorumCertificate(QuorumCertificate.QCType.COMMIT, viewNumber, currentProposal.getHash());
        for (Map.Entry<Integer, HotStuffMessage> entry : commitVotes.entrySet()) {
            commitQC.addVote(entry.getKey(), entry.getValue().getVoteSignature());
        }
        
        //System.out.println("[CONSENSUS] Leader " + myId + " created " + commitQC);
        
        // Broadcast DECIDE message
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setQc(commitQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            link.send(Link.Type.NODE, nodeId, Message.Type.DECIDE, payload);
        }
        
        // Leader will receive its own DECIDE message and process like others
        // This ensures all nodes move to next view synchronously
    }
    
    /**
     * Handle incoming DECIDE message (all replicas)
     */
    public void handleDecide(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        QuorumCertificate commitQC = hsMsg.getQc();
        handleDecide(commitQC);
    }
    
    private void handleDecide(QuorumCertificate commitQC) throws Exception {
        if (commitQC != null && commitQC.matches(QuorumCertificate.QCType.COMMIT, viewNumber)) {
            TreeNode decidedNode = blockchain.getNode(commitQC.getNodeHash());
            
            //System.out.println("[CONSENSUS] *** Node " + myId + " DECIDED on: " + decidedNode + " ***");
            
            // Execute the committed branch            
            currentProposal = null;
            //System.out.println("cleared current proposal");
            
            if (isLeader()) {
                newViewMessages.clear();   // only leader resets collection
            }

            viewNumber++;
            // Invoke callback
            if (decideCallback != null) {
                decideCallback.onDecide(decidedNode,  viewNumber - 1);
            }

            link.send(Link.Type.CLIENT, 1, Message.Type.REPLY, "message " + decidedNode.getRequestKey() + " committed in view " + (viewNumber - 1));

            // Move to next view
            Thread.sleep(100); // Small delay before starting next view
            startView();
        }
    }
    
    /**
     * SafeNode predicate (Lines 25-27 of Algorithm 2)
     */
    private boolean safeNode(TreeNode node, QuorumCertificate qc) {
        // Safety rule: node extends from lockedQC.node
        if (lockedQC != null) {
            TreeNode lockedNode = blockchain.getNode(lockedQC.getNodeHash());
            if (lockedNode != null && node.extendsFrom(lockedNode)) {
                return true;
            }
        } else {
            // No lock yet, can accept
            return true;
        }
        
        // Liveness rule: qc.viewNumber > lockedQC.viewNumber
        if (qc != null && lockedQC != null && qc.getViewNumber() > lockedQC.getViewNumber()) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Determine leader for a given view (simple round-robin)
     */
    private int getLeader(int view) {
        return ((view - 1) % n) + 1;
    }
    
    /**
     * Check if this node is the leader for current view
     */
    public boolean isLeader() {
        return myId == getLeader(viewNumber);
    }
    
    /**
     * Add a command to the pending queue (called when client sends request)
     */
    public void addCommand(String command, String requestKey) throws Exception {
        pendingCommands.offer(new CommandRequest(command, requestKey));
        System.out.println("[CONSENSUS] Node " + myId + " queued command with key " + requestKey + ": \"" + command + "\"");

        // If this node is the leader and we have enough NEW_VIEW messages, try to propose
        if (isLeader() && newViewMessages.size() >= (n - f)) {
            System.out.println("Entrei aqui");
            runPreparePhase();
        }
    }
    
    /**
     * Create vote data for signing
     */
    

    private byte[] createVoteData(int viewnumber, Message.Type voteType, byte[] nodeHash) {
        java.util.TreeMap<String, Object> map = new java.util.TreeMap<>();
        map.put("viewNumber", viewnumber); // Using viewNumber as a stable messageId
        map.put("type", voteType.toString());
        map.put("payload", nodeHash == null ? "" : Arrays.toString(nodeHash));
        
        return gson.toJson(map).getBytes(StandardCharsets.UTF_8);
    }
    
    public int getViewNumber() {
        return viewNumber;
    }
    
    /**
     * Callback interface for when consensus decides
     */
    public interface DecideCallback {
        void onDecide(TreeNode decidedNode, int view) throws Exception;
    }
    
    
    /**
     * Internal class to track command requests
     */
  
    private static class CommandRequest {
        final String command;
        final String requestKey;

        CommandRequest(String command, String requestKey) {
            this.command = command;
            this.requestKey = requestKey;
        }
    }

    private boolean verifyThresholdVote(Map<Integer, SigShare> votesMap, byte[]... candidateDatas) {
        List<SigShare> votes = new ArrayList<>(votesMap.values());

        for (byte[] data : candidateDatas) {
            if (data == null) continue;

            if (combinations(votes).stream().anyMatch(subset -> {
                try {
                    return crypto.verifyShare(data, subset.toArray(new SigShare[0]));
                } catch (Exception e) {
                    return false;
                }
            })) {
                return true; 
            }
        }
        return false; 
    }

    private List<List<SigShare>> combinations(List<SigShare> list) {
        List<List<SigShare>> result = new ArrayList<>();
        combineHelper(list, 0, new ArrayList<>(), result);
        return result;
    }

    private void combineHelper(List<SigShare> list, int start, List<SigShare> current, List<List<SigShare>> result) {
        if (current.size() == (n - f)) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < list.size(); i++) {
            current.add(list.get(i));
            combineHelper(list, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }
}
