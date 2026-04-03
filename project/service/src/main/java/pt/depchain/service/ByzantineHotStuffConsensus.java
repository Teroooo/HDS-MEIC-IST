package pt.depchain.service;

import java.util.Random;

import pt.depchain.communication.Block;

/**
 * Classe de Testes de Integração para simular um nó bizantino em um sistema de
 *  consenso HotStuff.
 *
 */


import pt.depchain.communication.Link;
import pt.depchain.communication.Message;
import pt.depchain.crypto.CryptoLibrary;
import pt.depchain.hotstuff.Blockchain;
import pt.depchain.hotstuff.HotStuffConsensus;
import pt.depchain.hotstuff.HotStuffMessage;
import pt.depchain.hotstuff.TreeNode;
import pt.depchain.hotstuff.QuorumCertificate;
import threshsig.SigShare;


public class ByzantineHotStuffConsensus extends HotStuffConsensus {

    public enum AttackMode {
        BAD_HASH,           // Proposta com hash errada (líder) + voto forjado (réplica)
        DUPLICATE_MSG,      // Réplica envia voto duplicado
        BAD_SHARE,          // Réplica assina com dados corrompidos (share inválida)
        WRONG_SENDER,        // Réplica envia voto com sender ID falsificado
        APPROVAL_FRONTRUNNING // Líder propõe comando legítimo mas com hash forjado para frontrunning
    }

    private final AttackMode attackMode;
    Boolean useBadStrings = true; 
    Boolean useBadHash = true;
    
    public ByzantineHotStuffConsensus(int myId, int n, int f, Link link, 
                                     CryptoLibrary crypto, Blockchain blockchain) {
        this(myId, n, f, link, crypto, blockchain, AttackMode.BAD_HASH);
    }

    public ByzantineHotStuffConsensus(int myId, int n, int f, Link link,
                                     CryptoLibrary crypto, Blockchain blockchain,
                                     AttackMode attackMode) {
        super(myId, n, f, link, crypto, blockchain);
        this.attackMode = attackMode;
        // Configurar flags consoante o modo
        switch (attackMode) {
            case BAD_HASH:
                this.useBadStrings = true;
                this.useBadHash = true;
                break;
            case DUPLICATE_MSG:
            case BAD_SHARE:
                this.useBadStrings = false;
                this.useBadHash = false;
                break;
            case WRONG_SENDER:
                break;
            case APPROVAL_FRONTRUNNING:

                break;
        }
    }
    
    // 
    // LÍDER MALICIOSO - propõe comando corrompido
    // 
    
    @Override
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

            byte[] forgedHash;
            if (useBadHash) {
                TreeNode forgedNode = new TreeNode(blc, null, viewNumber);
                forgedHash = forgedNode.getHash();
            } else {
                forgedHash = parent.getHash(); // hash normal baseado no conteúdo
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
        System.out.println("[BYZANTINE LEADER] Sent CORRUPTED PREPARE to all nodes");
        System.out.println("[BYZANTINE LEADER] ══════════════════════════════");
    
    }
    
    // 
    // RÉPLICA MALICIOSA - forja voto com hash diferente
    // 
    
    @Override
    public void handlePrepare(Message msg) throws Exception {
        switch (attackMode) {
            case BAD_HASH:
                handlePrepare_BadHash(msg);
                break;
            case DUPLICATE_MSG:
                handlePrepare_Duplicate(msg);
                break;
            case BAD_SHARE:
                handlePrepare_BadShare(msg);
                break;
            case WRONG_SENDER:
                handlePrepare_WrongSender(msg);
                break;
            case APPROVAL_FRONTRUNNING:
                handlePrepare_ApprovalFrontrunning(msg);
                break;
        }
    }

    //  BAD_HASH: forja voto com hash diferente 
    public void handlePrepare_BadHash(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        //System.out.println("[CONSENSUS] Node " + myId + " received PREPARE from leader " + msg.getSenderId() + ": " + proposal);
        
        
        // Check if safe to accept (safeNode predicate)
        if (safeNode(proposal, justify)) {
            
            blockchain.addNode(proposal);
            currentProposal = proposal;

            byte[] realHash = proposal.getHash();
            byte[] forgedHash = new byte[realHash.length];
            System.arraycopy(realHash, 0, forgedHash, 0, realHash.length);
            forgedHash[0] = (byte)(forgedHash[0] ^ 0xFF);
            
            System.out.println("[BYZANTINE REPLICA] Forging PREPARE_VOTE with wrong hash!");
            
            // Vote for this proposal
            SigShare voteSignature = crypto.signShare(createVoteData(viewNumber, Message.Type.PREPARE_VOTE, forgedHash));
            
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(forgedHash);
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
            
            //System.out.println("[CONSENSUS] Node " + myId + " voted PREPARE for " + proposal);
        } else {
            //System.out.println("[CONSENSUS] Node " + myId + " rejected PREPARE (safeNode failed)");
        }
    }

    //   DUPLICATE_MSG: envia voto correto duas vezes 
    public void handlePrepare_Duplicate(Message msg) throws Exception {
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
            System.out.println("[BYZANTINE REPLICA] Sending DUPLICATE PREPARE_VOTE!");
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
            //System.out.println("[CONSENSUS] Node " + myId + " voted PREPARE for " + proposal);
        } else {
            //System.out.println("[CONSENSUS] Node " + myId + " rejected PREPARE (safeNode failed)");
        }
    }

    //  BAD_SHARE: assina dados corrompidos (share inválida) 
    public void handlePrepare_BadShare(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        //System.out.println("[CONSENSUS] Node " + myId + " received PREPARE from leader " + msg.getSenderId() + ": " + proposal);
        
        
        // Check if safe to accept (safeNode predicate)
        if (safeNode(proposal, justify)) {
            
            blockchain.addNode(proposal);
            currentProposal = proposal;

            byte[] corruptData = "CORRUPTED_RANDOM_DATA".getBytes();
            SigShare badSignature = crypto.signShare(corruptData);

            System.out.println("[BYZANTINE REPLICA] Sending PREPARE_VOTE with BAD SHARE!");
                       
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(proposal.getHash());
            voteMsg.setVoteSignature(badSignature);
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
            
            //System.out.println("[CONSENSUS] Node " + myId + " voted PREPARE for " + proposal);
        } else {
            //System.out.println("[CONSENSUS] Node " + myId + " rejected PREPARE (safeNode failed)");
        }
    }

    // WRONG_SENDER: envia voto com sender ID falsificado 
    public void handlePrepare_WrongSender(Message msg) throws Exception {
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
            
            int fakeSenderId = (myId % n) + 1;
            System.out.println("[BYZANTINE REPLICA] Sending PREPARE_VOTE with WRONG SENDER ID! Real=" + myId + " Spoofed=" + fakeSenderId);

            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(proposal.getHash());
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);
            link.sendAs(String.valueOf(fakeSenderId), Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
            
            //System.out.println("[CONSENSUS] Node " + myId + " voted PREPARE for " + proposal);
        } else {
            //System.out.println("[CONSENSUS] Node " + myId + " rejected PREPARE (safeNode failed)");
        }
    }

    private void handlePrepare_ApprovalFrontrunning(Message msg) throws Exception {

    }
}
