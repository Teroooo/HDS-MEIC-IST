package pt.depchain.hotstuff;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import threshsig.SigShare;


public class QuorumCertificate implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public enum QCType {
        PREPARE,
        PRE_COMMIT,
        COMMIT
    }
    
    private final QCType type;
    private final int viewNumber;
    private final byte[] nodeHash;
    private final List<String> voterIds;
    private final List<SigShare> signatures;
    
    public QuorumCertificate(QCType type, int viewNumber, byte[] nodeHash) {
        this.type = type;
        this.viewNumber = viewNumber;
        this.nodeHash = nodeHash;
        this.voterIds = new ArrayList<>();
        this.signatures = new ArrayList<>();
    }
    
    public void addVote(String voterId, SigShare signature) {
        if (!voterIds.contains(voterId)) {
            voterIds.add(voterId);
            signatures.add(signature);
        }
    }
    
    public QCType getType() {
        return type;
    }
    
    public int getViewNumber() {
        return viewNumber;
    }
    
    public byte[] getNodeHash() {
        return nodeHash;
    }
    
    public List<String> getVoterIds() {
        return voterIds;
    }
    
    public List<SigShare> getSignatures() {
        return signatures;
    }
    
    public int getVoteCount() {
        return voterIds.size();
    }
    
    public boolean isValid(int n, int f) {
        return voterIds.size() >= (n - f);
    }
    
    public boolean matches(QCType type, int viewNumber) {
        return this.type == type && this.viewNumber == viewNumber;
    }
    
    public boolean isForNode(byte[] nodeHash) {
        return Arrays.equals(this.nodeHash, nodeHash);
    }
    
    @Override
    public String toString() {
        return "QC{" +
                "type=" + type +
                ", view=" + viewNumber +
                ", votes=" + voterIds.size() +
                ", voters=" + voterIds +
                '}';
    }
}
