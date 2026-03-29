package pt.depchain.hotstuff;

import java.io.Serializable;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

public class TreeNode implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String command;
    private final byte[] parentHash;
    private final int viewNumber;
    private byte[] hash;
    private List<TreeNode> children;
    private final String requestKey;
    
    // Root node constructor
    public TreeNode() {
        this.command = "GENESIS";
        this.parentHash = null;
        this.viewNumber = 0;
        this.children = new ArrayList<>();
        this.hash = computeHash();
        this.requestKey = "GENESIS";
        System.out.println("  [HASH] Computing hash for node: " + HexFormat.of().formatHex(hash));
    }
    
    // Regular node constructor
    public TreeNode(String command, String requestKey, byte[] parentHash, int viewNumber) {
        this.command = command;
        this.requestKey = requestKey;
        this.parentHash = parentHash;
        this.viewNumber = viewNumber;
        this.children = new ArrayList<>();
        this.hash = computeHash();
        System.out.println("  [HASH] Computing hash for node: " + this.hash);
    }
    
    public String getCommand() {
        return command;
    }
    
    public byte[] getParentHash() {
        return parentHash;
    }
    
    public int getViewNumber() {
        return viewNumber;
    }
    
    public byte[] getHash() {
        return hash;
    }
    
    public String getRequestKey() {
        return requestKey;
    }

    public List<TreeNode> getChildren() {
        return children;
    }
    
    public void addChild(TreeNode child) {
        children.add(child);
    }
    
    public boolean isRoot() {
        return parentHash == null;
    }
    

    private byte[] computeHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(command.getBytes());
            if (parentHash != null) {
                digest.update(parentHash);
            }
            digest.update(String.valueOf(viewNumber).getBytes());
            return digest.digest();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hash", e);
        }
    }
    

    public boolean extendsFrom(TreeNode other) {
        if (other == null) return true; // null means root/genesis
        if (Arrays.equals(this.hash, other.hash)) return true;
        if (this.parentHash == null) return false;
        return Arrays.equals(this.parentHash, other.hash);
    }
    

    public List<byte[]> getPathToRoot() {
        List<byte[]> path = new ArrayList<>();
        path.add(this.hash);
        if (parentHash != null) {
            path.add(parentHash);
        }
        return path;
    }
    
    @Override
    public String toString() {
        return "TreeNode{" +
                "command='" + command + '\'' +
                ", viewNumber=" + viewNumber +
                ", hash=" + (hash != null ? bytesToHex(hash).substring(0, 8) : "null") +
                ", parentHash=" + (parentHash != null ? bytesToHex(parentHash).substring(0, 8) : "null") +
                '}';
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
