package pt.depchain.hotstuff;

import java.io.Serializable;

/**
 * Extended message payload for HotStuff protocol messages.
 * This wraps the data that goes in the Message.payload field (serialized).
 */
public class HotStuffMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private TreeNode proposal;
    private QuorumCertificate qc;
    private byte[] voteSignature;
    private byte[] nodeHash;
    private String clientCommand;
    
    public HotStuffMessage() {
    }
    
    public TreeNode getProposal() {
        return proposal;
    }
    
    public void setProposal(TreeNode proposal) {
        this.proposal = proposal;
    }
    
    public QuorumCertificate getQc() {
        return qc;
    }
    
    public void setQc(QuorumCertificate qc) {
        this.qc = qc;
    }
    
    public byte[] getVoteSignature() {
        return voteSignature;
    }
    
    public void setVoteSignature(byte[] voteSignature) {
        this.voteSignature = voteSignature;
    }
    
    public byte[] getNodeHash() {
        return nodeHash;
    }
    
    public void setNodeHash(byte[] nodeHash) {
        this.nodeHash = nodeHash;
    }
    
    public String getClientCommand() {
        return clientCommand;
    }
    
    public void setClientCommand(String clientCommand) {
        this.clientCommand = clientCommand;
    }
}
