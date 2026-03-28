package pt.depchain.hotstuff;

import java.io.Serializable;

import threshsig.SigShare;


public class HotStuffMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private TreeNode proposal;
    private QuorumCertificate qc;
    private SigShare voteSignature;
    private byte[] nodeHash;
    private String clientCommand;
    private int viewNumber;
    private TreeNode[] syncNodes; // For synchronization phase
    
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
    
    public SigShare getVoteSignature() {
        return voteSignature;
    }
    
    public void setVoteSignature(SigShare voteSignature) {
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

    public int getViewNumber() {
        return this.viewNumber;
    }

    public void setViewNumber(int viewNumber) {
        this.viewNumber = viewNumber;
    }

    public TreeNode[] getSyncNodes() {
        return syncNodes;
    }

    public void setSyncNodes(TreeNode[] syncNodes) {
        this.syncNodes = syncNodes;
    }
}
