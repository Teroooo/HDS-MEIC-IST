package pt.depchain.hotstuff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple in-memory blockchain storage.
 * Keeps track of the tree of proposed nodes and the committed branch.
 */
public class Blockchain {
    
    private final TreeNode root;
    private final Map<String, TreeNode> nodesByHash;
    private final List<String> committedCommands;
    private TreeNode lastCommittedNode;
    
    public Blockchain() {
        this.root = new TreeNode(); // Genesis block
        this.nodesByHash = new HashMap<>();
        this.committedCommands = new ArrayList<>();
        this.lastCommittedNode = root;
        
        nodesByHash.put(bytesToHex(root.getHash()), root);
        committedCommands.add(root.getCommand());
    }
    
    public TreeNode getRoot() {
        return root;
    }
    
    public TreeNode getLastCommittedNode() {
        return lastCommittedNode;
    }
    
    /**
     * Add a node to the tree
     */
    public void addNode(TreeNode node) {
        String hashStr = bytesToHex(node.getHash());
        if (!nodesByHash.containsKey(hashStr)) {
            nodesByHash.put(hashStr, node);
            
            // Link to parent
            if (node.getParentHash() != null) {
                String parentHashStr = bytesToHex(node.getParentHash());
                TreeNode parent = nodesByHash.get(parentHashStr);
                if (parent != null) {
                    parent.addChild(node);
                }
            }
        }
    }
    
    /**
     * Get node by hash
     */
    public TreeNode getNode(byte[] hash) {
        return nodesByHash.get(bytesToHex(hash));
    }
    
    /**
     * Execute committed branch up to the given node
     */
    public void executeCommittedBranch(TreeNode committedNode) {
        if (committedNode == null) return;
        
        // Find path from lastCommittedNode to committedNode
        List<TreeNode> pathToCommit = new ArrayList<>();
        TreeNode current = committedNode;
        
        while (current != null && !Arrays.equals(current.getHash(), lastCommittedNode.getHash())) {
            pathToCommit.add(0, current);
            if (current.getParentHash() != null) {
                current = getNode(current.getParentHash());
            } else {
                break;
            }
        }
        
        // Execute commands in order
        for (TreeNode node : pathToCommit) {
            if (!node.getCommand().equals("GENESIS")) {
                committedCommands.add(node.getCommand());
                System.out.println("  [BLOCKCHAIN] Committed: \"" + node.getCommand() + "\" (view " + node.getViewNumber() + ")");
            }
        }
        
        lastCommittedNode = committedNode;
    }
    
    /**
     * Get all committed commands
     */
    public List<String> getCommittedCommands() {
        return new ArrayList<>(committedCommands);
    }
    
    /**
     * Get the full blockchain state as a string
     */
    public String getBlockchainState() {
        StringBuilder sb = new StringBuilder();
        sb.append("Blockchain (").append(committedCommands.size()).append(" commands):\n");
        for (int i = 0; i < committedCommands.size(); i++) {
            sb.append("  ").append(i).append(": ").append(committedCommands.get(i)).append("\n");
        }
        return sb.toString();
    }
    
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
