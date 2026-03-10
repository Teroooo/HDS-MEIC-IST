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


public class ByzantineHotStuffConsensus extends HotStuffConsensus {

    public enum AttackMode {
        BAD_HASH,           // Proposta com hash errada (líder) + voto forjado (réplica)
        DUPLICATE_MSG,      // Réplica envia voto duplicado
        BAD_SHARE,          // Réplica assina com dados corrompidos (share inválida)
        WRONG_SENDER        // Réplica envia voto com sender ID falsificado
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
        }
    }
    
    // 
    // LÍDER MALICIOSO - propõe comando corrompido
    // 
    
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
        }
    }

    //  BAD_HASH: forja voto com hash diferente 
    private void handlePrepare_BadHash(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        blockchain.addNode(proposal);
        currentProposal = proposal;
        
        if (safeNode(proposal, justify)) {
            byte[] realHash = proposal.getHash();
            byte[] forgedHash = new byte[realHash.length];
            System.arraycopy(realHash, 0, forgedHash, 0, realHash.length);
            forgedHash[0] = (byte)(forgedHash[0] ^ 0xFF);
            
            System.out.println("[BYZANTINE REPLICA] Forging PREPARE_VOTE with wrong hash!");
            
            SigShare voteSignature = crypto.signShare(
                createVoteData(viewNumber, Message.Type.PREPARE_VOTE, forgedHash)
            );
            
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(forgedHash);
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
        }
    }

    //   DUPLICATE_MSG: envia voto correto duas vezes 
    private void handlePrepare_Duplicate(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        blockchain.addNode(proposal);
        currentProposal = proposal;
        
        if (safeNode(proposal, justify)) {
            SigShare voteSignature = crypto.signShare(
                createVoteData(viewNumber, Message.Type.PREPARE_VOTE, proposal.getHash())
            );
            
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(proposal.getHash());
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);

            System.out.println("[BYZANTINE REPLICA] Sending DUPLICATE PREPARE_VOTE!");
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
        }
    }

    //  BAD_SHARE: assina dados corrompidos (share inválida) 
    private void handlePrepare_BadShare(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        blockchain.addNode(proposal);
        currentProposal = proposal;
        
        if (safeNode(proposal, justify)) {
            // Assina dados ERRADOS mas reporta o hash correto
            byte[] corruptData = "CORRUPTED_RANDOM_DATA".getBytes();
            SigShare badSignature = crypto.signShare(corruptData);
            
            System.out.println("[BYZANTINE REPLICA] Sending PREPARE_VOTE with BAD SHARE!");
            
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(proposal.getHash()); // hash correto
            voteMsg.setVoteSignature(badSignature);    // mas assinatura sobre dados errados!
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);
            link.send(Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
        }
    }

    // WRONG_SENDER: envia voto com sender ID falsificado 
    private void handlePrepare_WrongSender(Message msg) throws Exception {
        HotStuffMessage hsMsg = gson.fromJson(msg.getPayload(), HotStuffMessage.class);
        TreeNode proposal = hsMsg.getProposal();
        QuorumCertificate justify = hsMsg.getQc();
        
        blockchain.addNode(proposal);
        currentProposal = proposal;
        
        if (safeNode(proposal, justify)) {
            SigShare voteSignature = crypto.signShare(
                createVoteData(viewNumber, Message.Type.PREPARE_VOTE, proposal.getHash())
            );
            
            // Escolhe um sender ID falso (outro nó qualquer)
            int fakeSenderId = (myId % n) + 1;
            System.out.println("[BYZANTINE REPLICA] Sending PREPARE_VOTE with WRONG SENDER ID! Real=" + myId + " Spoofed=" + fakeSenderId);
            
            HotStuffMessage voteMsg = new HotStuffMessage();
            voteMsg.setNodeHash(proposal.getHash());
            voteMsg.setVoteSignature(voteSignature);
            voteMsg.setViewNumber(viewNumber);
            
            String payload = gson.toJson(voteMsg);
            link.sendAs(fakeSenderId, Link.Type.NODE, msg.getSenderId(), Message.Type.PREPARE_VOTE, payload);
        }
    }
}
