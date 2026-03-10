package pt.depchain.service;

import java.util.Random;

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

/**
 * Nó bizantino com dois comportamentos maliciosos:
 * 
 * 1) COMO LÍDER: propõe comando corrompido (altera dados do cliente)
 * 2) COMO RÉPLICA: forja voto com hash diferente do proposto
 */
public class ByzantineHotStuffConsensus extends HotStuffConsensus {

    Boolean useBadStrings = true; 
    Boolean useBadHash = true;
    
    public ByzantineHotStuffConsensus(int myId, int n, int f, Link link, 
                                     CryptoLibrary crypto, Blockchain blockchain) {
        super(myId, n, f, link, crypto, blockchain);
    }
    
    // ═══════════════════════════════════════════════════════════
    // ATAQUE 1: LÍDER MALICIOSO - propõe comando corrompido
    // ═══════════════════════════════════════════════════════════
    
    @Override
    protected void runPreparePhase() throws Exception {
        if (!isLeader() || prepareStarted) return;
        prepareStarted = true;

        System.out.println("[BYZANTINE LEADER] ══════════════════════════════");
        System.out.println("[BYZANTINE LEADER] Proposing CORRUPTED block in view " + viewNumber);
        
        // Find highQC
        QuorumCertificate highQC = null;
        for (HotStuffMessage msg : newViewMessages.values()) {
            if (msg.getQc() != null) {
                if (highQC == null || msg.getQc().getViewNumber() > highQC.getViewNumber()) {
                    highQC = msg.getQc();
                }
            }
        }
        
        // TreeNode hashforged= new TreeNode("CORRUPTED_COMMAND", "CORRUPTED_KEY", null, viewNumber+1);
        TreeNode parentNode;
        TreeNode parent;
        if (highQC != null) {
            parent = blockchain.getNode(highQC.getNodeHash());
        } else {
            parent = blockchain.getLastCommittedNode();
        }
        
        if (currentProposal != null) {
            pendingCommands.removeIf(cmd -> cmd.requestKey.equals(currentProposal.getRequestKey()));
        } else {
            CommandRequest cmdReq = pendingCommands.poll();
            if (cmdReq == null) {
                prepareStarted = false;
                return;
            }
            
            String command;
            if (useBadStrings) {
                command = cmdReq.command + "_CORRUPTED";
            } else {
                command = cmdReq.command;
            }

            byte[] forgedHash;
            if (useBadHash) {
                TreeNode forgedNode = new TreeNode(command, cmdReq.requestKey, null, viewNumber+1);
                forgedHash = forgedNode.getHash();
            } else {
                forgedHash = parent.getHash(); // hash normal baseado no conteúdo
            }

            currentProposal = new TreeNode(command, cmdReq.requestKey, forgedHash, viewNumber);
            blockchain.addNode(currentProposal);
        }
        
        HotStuffMessage hsMsg = new HotStuffMessage();
        hsMsg.setProposal(currentProposal);
        hsMsg.setQc(highQC);
        
        String payload = gson.toJson(hsMsg);
        for (int nodeId = 1; nodeId <= n; nodeId++) {
            link.send(Link.Type.NODE, nodeId, Message.Type.PREPARE, payload);
        }
        System.out.println("[BYZANTINE LEADER] Sent CORRUPTED PREPARE to all nodes");
        System.out.println("[BYZANTINE LEADER] ══════════════════════════════");
    }
    
    // ═══════════════════════════════════════════════════════════
    // ATAQUE 2: RÉPLICA MALICIOSA - forja voto com hash diferente
    // ═══════════════════════════════════════════════════════════
    
    @Override
    public void handlePrepare(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        blockchain.addNode(proposal);
        currentProposal = proposal;
        
        if (safeNode(proposal, justify)) {
            // ATAQUE: forjar hash diferente do proposto!
            byte[] realHash = proposal.getHash();
            byte[] forgedHash = new byte[realHash.length];
            System.arraycopy(realHash, 0, forgedHash, 0, realHash.length);
            forgedHash[0] = (byte)(forgedHash[0] ^ 0xFF); // flip bits do primeiro byte
            
            System.out.println("[BYZANTINE REPLICA] ══════════════════════════════");
            System.out.println("[BYZANTINE REPLICA] Forging PREPARE_VOTE with wrong hash!");
            System.out.println("[BYZANTINE REPLICA] Real hash[0]:   " + realHash[0]);
            System.out.println("[BYZANTINE REPLICA] Forged hash[0]: " + forgedHash[0]);
            
            // Assinar com hash forjado (assinatura válida mas sobre dados errados)
            SigShare voteSignature = crypto.signShare(
                createVoteData(viewNumber, Message.Type.PREPARE_VOTE, forgedHash)
            );
            
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(forgedHash);  // HASH ERRADO!
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
            
            System.out.println("[BYZANTINE REPLICA] Sent FORGED vote to leader " + msg.getSenderId());
            System.out.println("[BYZANTINE REPLICA] ══════════════════════════════");
        }
    }
}
