package pt.depchain.hotstuff;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pt.depchain.communication.Block;

/**
 * Simple in-memory blockchain storage.
 * Keeps track of the tree of proposed nodes and the committed branch.
 */
public class Blockchain {
    
    private final TreeNode root;
    private final Map<String, TreeNode> nodesByHash;
    private final List<String> committedCommands;
    private TreeNode lastCommittedNode;
    
    private final List<Block> committedBlocks;

    public Blockchain() {
        Block genesisBlock = loadGenesisBlock();
        this.root = new TreeNode(genesisBlock); // Genesis block
        this.nodesByHash = new HashMap<>();
        this.committedBlocks = new ArrayList<>();
        this.committedCommands = new ArrayList<>();
        this.lastCommittedNode = root;
        
        nodesByHash.put(bytesToHex(root.getHash()), root);
        committedCommands.add(root.getCommand());

        if (genesisBlock.getTransactions() != null) {
            for (Object tx : genesisBlock.getTransactions()) {
                // TODO: replace with EVMExecutor later
                System.out.println("[GENESIS] Processing transaction: " + tx.toString());
            }
        }

        //Phase 2: Add genesis block to committed blocks
        committedBlocks.add(root.getBlock());
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
                } else {
                    // System.err.println("[BLOCKCHAIN-ERROR] Parent node not found for node: " + node);
                }
            } else {
                // System.out.println("\n\n\n\n  [BLOCKCHAIN] Added root node: " + node + "\n\n\n\n");
            }
        } else {
            // System.out.println("\n\n\n\n  [BLOCKCHAIN] Node already exists: " + node + "\n\n\n\n");
        }
        // System.out.println("\n\n\n\n  [BLOCKCHAIN] Current blockchain state after adding node: " + committedCommands + "\n\n"+ nodesByHash +" \n\n");
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
        int index = committedBlocks.size();

        for (TreeNode node : pathToCommit) {
            if (!node.getBlock().equals("GENESIS")) {
                committedBlocks.add(node.getBlock());

                persistBlock(node.getBlock(), index);
                index++;

                System.out.println("  [BLOCKCHAIN] Committed: \"" + node.getBlock() + "\"");
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
    
    public List<Block> getCommittedBlocks() {
        return new ArrayList<>(committedBlocks);
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

    //PHASE 2:
    public String getBlockchainStateWithBlocks() {
        StringBuilder sb = new StringBuilder();
        sb.append("Blockchain (").append(committedBlocks.size()).append(" blocks):\n");
        for (int i = 0; i < committedBlocks.size(); i++) {
            sb.append("  Block ").append(i).append(": ").append(committedBlocks.get(i).toString()).append("\n");
 
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

    public TreeNode[] getChildrenNodesFromHash(byte[] hash) {
        TreeNode node = nodesByHash.get(bytesToHex(hash));
        if (node != null) {
            return node.getChildren().toArray(new TreeNode[0]);
        }
        return new TreeNode[0];
    }

    private Block loadGenesisBlock() {
        try {
            Gson gson = new Gson();
            FileReader reader = new FileReader("../blocks/genesis.json");
            return gson.fromJson(reader, Block.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load genesis block", e);
        }
    }

    private void persistBlock(Block block, int index) {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            File dir = new File("../blocks");
            if (!dir.exists()) dir.mkdirs();

            File file = new File(dir, "block" + index + ".json");
            FileWriter writer = new FileWriter(file);

            gson.toJson(block, writer);
            writer.flush();
            writer.close();

            System.out.println("[BLOCKCHAIN] Persisted block to " + file.getPath());

        } catch (Exception e) {
            e.printStackTrace();
    }
}
}
