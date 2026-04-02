package pt.depchain.hotstuff;

import com.google.gson.Gson;
import pt.depchain.communication.Link;
import pt.depchain.communication.Message;
import pt.depchain.communication.Block;
import pt.depchain.crypto.CryptoLibrary;
import pt.depchain.hotstuff.HotStuffConsensus.CommandRequest;
import threshsig.SigShare;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;

public class HotStuffConsensus {
    
    protected final int myId;
    protected final int n; // total number of nodes
    protected final int f; // number of Byzantine faults tolerated
    protected final Link link;
    protected final CryptoLibrary crypto;
    protected final Blockchain blockchain;
    protected final Gson gson = new Gson();
    

    protected int viewNumber;
    protected QuorumCertificate prepareQC;
    private QuorumCertificate lockedQC;
    protected TreeNode currentProposal;
    protected boolean prepareStarted = false;
    private boolean precommitStarted = false;
    private boolean commitStarted = false;
    private boolean decideStarted = false;


    // data for each phase

    // Vote collection for current view
    protected final Map<String, HotStuffMessage> newViewMessages = new ConcurrentHashMap<>();
    protected final Map<String, HotStuffMessage> prepareVotes = new ConcurrentHashMap<>();
    private final Map<String, HotStuffMessage> preCommitVotes = new ConcurrentHashMap<>();
    private final Map<String, HotStuffMessage> commitVotes = new ConcurrentHashMap<>();
    
    // Command queue (for leader)
    protected final Queue<CommandRequest> pendingCommands = new LinkedBlockingQueue<>();

    //Phase 2: block List (for leader) || its not a queue because we want to be able to sort it
    protected final Queue<Block> pendingBlocks = new LinkedBlockingQueue<>();

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

    public void startView() throws Exception {
        System.out.println("[CONSENSUS] Node " + myId + " starting view " + viewNumber);
        
        // Clear vote collection from previous view
        prepareVotes.clear();
        preCommitVotes.clear();
        commitVotes.clear();
        
        String leader = Integer.toString(getLeader(viewNumber));
        
        // Send NEW_VIEW message to leader
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setQc(prepareQC);
        
        String payload = gson.toJson(hsMsg);
        link.send(Link.Type.NODE, leader, Message.Type.NEW_VIEW, payload);
        
        System.out.println("[CONSENSUS] Node " + myId + " sent NEW_VIEW to leader " + leader);
    }
    

    public void handleNewView(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        newViewMessages.put(msg.getSenderId(), hsMsg);
        
        
        
        //System.out.println("[CONSENSUS] Leader " + myId + " received NEW_VIEW from node " + msg.getSenderId() 
        //                 + " (collected " + newViewMessages.size() + "/" + (n-f) + ")");
        
        // Start PREPARE phase when we reach exactly (n-f) NEW_VIEW messages
        
        System.out.println("Current NEW_VIEW messages: " + newViewMessages.size());
        
        if (isLeader() && newViewMessages.size() == (n - f)) {
            runPreparePhase();
        }
    }
    

    protected void runPreparePhase() throws Exception {
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
        System.out.println("currentProposal: " + currentProposal);
        if (currentProposal != null) {
            // Re-propose the previous proposal (crash recovery)
            //pendingCommands.removeIf(cmd -> cmd.requestKey.equals(currentProposal.getRequestKey()));
            pendingBlocks.removeIf(cmd -> cmd.toString().equals(currentProposal.getBlock().toString()));
            System.out.println("[CONSENSUS] Re-proposing previous proposal: " + currentProposal);
        } else {
            // Take a new command from the pending queue
            Block blc = pendingBlocks.poll();
            if (blc == null) {
                prepareStarted = false;
                System.out.println("[CONSENSUS] No blocks to propose, waiting...");
                return; // nothing to propose
            }
            currentProposal = new TreeNode(blc, parent.getHash(), viewNumber);
            blockchain.addNode(currentProposal);
        }
        
        System.out.println("[CONSENSUS] Leader " + myId + " running PREPARE phase for view " + viewNumber);
                
        // Broadcast PREPARE message
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setProposal(currentProposal);
        hsMsg.setQc(highQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            String nodeString = Integer.toString(getLeader(nodeId));
            link.send(Link.Type.NODE, nodeString, Message.Type.PREPARE, payload);
        }
    }
    

    public void handlePrepare(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        //System.out.println("[CONSENSUS] Node " + myId + " received PREPARE from leader " + msg.getSenderId() + ": " + proposal);
        
        
        // Check if safe to accept (safeNode predicate)
        if (safeNode(proposal, justify)) {
            
            blockchain.addNode(proposal);
            currentProposal = proposal;
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
                Map<String, SigShare> sigSharesMap = new HashMap<>();
                for (Map.Entry<String, HotStuffMessage> entry : prepareVotes.entrySet()) {
                    sigSharesMap.put(entry.getKey(), entry.getValue().getVoteSignature());
                }

                byte[] dataFirst = createVoteData(firstMsg.getViewNumber(), Message.Type.PREPARE_VOTE, firstMsg.getNodeHash());
                byte[] dataLast = createVoteData(firstMsg.getViewNumber(), Message.Type.PREPARE_VOTE, lastMsg.getNodeHash());
        
                if (verifyThresholdVote(sigSharesMap, dataFirst)) {
                    System.out.println("Threshold first signature successful");
                    TreeNode verifiedProposal = blockchain.getNode(firstMsg.getNodeHash());
                    runPreCommitPhase(verifiedProposal);
                } else if(verifyThresholdVote(sigSharesMap, dataLast)){
                    System.out.println("Threshold last signature successful");
                    TreeNode verifiedProposal = blockchain.getNode(lastMsg.getNodeHash());
                    runPreCommitPhase(verifiedProposal);
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
    
    private void runPreCommitPhase(TreeNode verifiedProposal) throws Exception {
        if (!isLeader() || precommitStarted) return;
        precommitStarted = true;

        currentProposal = verifiedProposal;
        System.out.println("[CONSENSUS] Leader " + myId + " running PRE-COMMIT phase");
        
        // Create prepareQC
        prepareQC = new QuorumCertificate(QuorumCertificate.QCType.PREPARE, viewNumber, currentProposal.getHash());
        for (Map.Entry<String, HotStuffMessage> entry : prepareVotes.entrySet()) {
            prepareQC.addVote(entry.getKey(), entry.getValue().getVoteSignature());
        }
        
        System.out.println("[CONSENSUS] Leader " + myId + " created " + prepareQC);
        
        // Broadcast PRE_COMMIT message
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setQc(prepareQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            String nodeString = Integer.toString(getLeader(nodeId));
            link.send(Link.Type.NODE, nodeString, Message.Type.PRE_COMMIT, payload);
        }
        // System.out.println("[TEST] Sleeping for 2 seconds before Commit. Kill this process now to simulate crash.");
        // Thread.sleep(2000);
        // System.out.println("[TEST] Woke up, now sending Commit. If you killed the process before, this won't happen.");
    }
    
    
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
                Map<String, SigShare> sigSharesMap = new HashMap<>();
                for (Map.Entry<String, HotStuffMessage> entry : preCommitVotes.entrySet()) {
                    sigSharesMap.put(entry.getKey(), entry.getValue().getVoteSignature());
                }

                byte[] dataFirst = createVoteData(firstMsg.getViewNumber(), Message.Type.PRE_COMMIT_VOTE, firstMsg.getNodeHash());
                byte[] dataLast = createVoteData(firstMsg.getViewNumber(), Message.Type.PRE_COMMIT_VOTE, lastMsg.getNodeHash());
        
                if (verifyThresholdVote(sigSharesMap, dataFirst)) {
                    //System.out.println("Threshold signature successful");
                    TreeNode verifiedProposal = blockchain.getNode(firstMsg.getNodeHash());
                    runCommitPhase(verifiedProposal);
                } else if(verifyThresholdVote(sigSharesMap, dataLast)){
                    TreeNode verifiedProposal = blockchain.getNode(lastMsg.getNodeHash());
                    runCommitPhase(verifiedProposal);
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
    

    private void runCommitPhase(TreeNode verifiedProposal) throws Exception {
        if (!isLeader() || commitStarted) return;
        commitStarted = true;

        currentProposal = verifiedProposal;
        System.out.println("[CONSENSUS] Leader " + myId + " running COMMIT phase");
        
        // Create precommitQC
        QuorumCertificate precommitQC = new QuorumCertificate(QuorumCertificate.QCType.PRE_COMMIT, viewNumber, currentProposal.getHash());
        for (Map.Entry<String, HotStuffMessage> entry : preCommitVotes.entrySet()) {
            precommitQC.addVote(entry.getKey(), entry.getValue().getVoteSignature());
        }
        
        //System.out.println("[CONSENSUS] Leader " + myId + " created " + precommitQC);
        
        // Broadcast COMMIT message
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setQc(precommitQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            String nodeString = Integer.toString(getLeader(nodeId));
            link.send(Link.Type.NODE, nodeString, Message.Type.COMMIT, payload);
        }
        // System.out.println("[TEST] Sleeping for 2 seconds before DECIDE. Kill this process now to simulate crash.");
        // Thread.sleep(2000);
        // System.out.println("[TEST] Woke up, now sending DECIDE. If you killed the process before, this won't happen.");
    }
    

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
                Map<String, SigShare> sigSharesMap = new HashMap<>();
                for (Map.Entry<String, HotStuffMessage> entry : commitVotes.entrySet()) {
                    sigSharesMap.put(entry.getKey(), entry.getValue().getVoteSignature());
                }

                byte[] dataFirst = createVoteData(firstMsg.getViewNumber(), Message.Type.COMMIT_VOTE, firstMsg.getNodeHash());
                byte[] dataLast = createVoteData(firstMsg.getViewNumber(), Message.Type.COMMIT_VOTE, lastMsg.getNodeHash());
        
                if (verifyThresholdVote(sigSharesMap, dataFirst)) {
                    //System.out.println("Threshold signature successful");
                    TreeNode verifiedProposal = blockchain.getNode(firstMsg.getNodeHash());
                    runDecidePhase(verifiedProposal);
                } else if(verifyThresholdVote(sigSharesMap, dataLast)){
                    TreeNode verifiedProposal = blockchain.getNode(lastMsg.getNodeHash());
                    runDecidePhase(verifiedProposal);
                }  else {
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
    

    private void runDecidePhase(TreeNode verifiedProposal) throws Exception {
        if (!isLeader() || decideStarted) return;
        decideStarted = true;
        
        currentProposal = verifiedProposal;
        System.out.println("[CONSENSUS] Leader " + myId + " running DECIDE phase");
        
        System.out.println("\n[CONSENSUS] Leader checking proposed block: "+ currentProposal.getBlock() + "\n");
        
        // Create commitQC
        QuorumCertificate commitQC = new QuorumCertificate(QuorumCertificate.QCType.COMMIT, viewNumber, currentProposal.getHash());
        for (Map.Entry<String, HotStuffMessage> entry : commitVotes.entrySet()) {
            commitQC.addVote(entry.getKey(), entry.getValue().getVoteSignature());
        }
        
        //System.out.println("[CONSENSUS] Leader " + myId + " created " + commitQC);
        
        // Broadcast DECIDE message
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setQc(commitQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            String nodeString = Integer.toString(getLeader(nodeId));
            link.send(Link.Type.NODE, nodeString, Message.Type.DECIDE, payload);
        }
        
        // Leader will receive its own DECIDE message and process like others
        // This ensures all nodes move to next view synchronously
    }
    

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

            // Invoke callback
            if (decideCallback != null) {
                decideCallback.onDecide(decidedNode,  viewNumber);
            }

            // Move to next view
            Thread.sleep(100); // Small delay before starting next view
            this.advanceView();
        }
    }
    

    protected boolean safeNode(TreeNode node, QuorumCertificate qc) {
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
    

    private int getLeader(int view) {
        return ((view - 1) % n) + 1;
    }
    

    public boolean isLeader() {
        return myId == getLeader(viewNumber);
    }

    public int getViewNumber() {
        return viewNumber;
    }
    

    public void addCommand(String command, String requestKey) throws Exception {
        pendingCommands.offer(new CommandRequest(command, requestKey));
        System.out.println("[CONSENSUS] Node " + myId + " queued command with key " + requestKey + ": \"" + command + "\"");

        // If this node is the leader and we have enough NEW_VIEW messages, try to propose
        if (isLeader() && newViewMessages.size() >= (n - f)) {
            runPreparePhase();
        }
    }

    //PHASE 2: addBlock
    public void addBlock(Block block) throws Exception {
        pendingBlocks.add(block);
        System.out.println("[CONSENSUS] Node " + myId + " queued block with hash " + block.getHash()+ ": \"" + block + "\"");

        // If this node is the leader and we have enough NEW_VIEW messages, try to propose
        if (isLeader() && newViewMessages.size() >= (n - f)) {
            runPreparePhase();
        }
    }

    protected byte[] createVoteData(int viewnumber, Message.Type voteType, byte[] nodeHash) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
            
            dos.writeInt(viewnumber);
            dos.writeUTF(voteType.toString());
            if (nodeHash != null) {
                dos.write(nodeHash);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to serialize vote data", e);
        }
    }
    
    
    public interface DecideCallback {
        void onDecide(TreeNode decidedNode, int view) throws Exception;
    }
    

    protected static class CommandRequest {
        public final String command;
        public final String requestKey;

        CommandRequest(String command, String requestKey) {
            this.command = command;
            this.requestKey = requestKey;
        }
    }


    public boolean verifyThresholdVote(Map<String, SigShare> votesMap, byte[]... candidateDatas) {
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