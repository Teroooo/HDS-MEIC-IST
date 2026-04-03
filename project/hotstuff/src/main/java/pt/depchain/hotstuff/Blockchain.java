package pt.depchain.hotstuff;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.MutableAccount;
import java.math.BigInteger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pt.depchain.communication.Block;
import pt.depchain.communication.ByteArrayHexAdapter;
import pt.depchain.communication.State;
import pt.depchain.communication.Transaction;

/**
 * Simple in-memory blockchain storage.
 * Keeps track of the tree of proposed nodes and the committed branch.
 */
public class Blockchain {
    
    private final TreeNode root;
    private final Map<String, TreeNode> nodesByHash;
    private final List<String> committedCommands;
    private TreeNode lastCommittedNode;

    private AccountOperations accountOperations; 
    
    private final List<Block> committedBlocks;

    public Blockchain() {
        Block genesisBlock = loadGenesisBlock();

        accountOperations = new AccountOperations();

        this.root = new TreeNode(genesisBlock); // Genesis block
        this.nodesByHash = new HashMap<>();
        this.committedBlocks = new ArrayList<>();
        this.committedCommands = new ArrayList<>();
        this.lastCommittedNode = root;
        
        nodesByHash.put(bytesToHex(root.getHash()), root);
        committedCommands.add(root.getCommand());


        if (genesisBlock.getStates() != null) {
            for (Map.Entry<String, State> entry : genesisBlock.getStates().entrySet()) {
                String accountAddress = entry.getKey();
                State accountState = entry.getValue();
                accountOperations.initializeAccount(accountAddress, accountState);
            }
        }

        if (genesisBlock.getTransactions() != null) {
            for (Transaction tx : genesisBlock.getTransactions()) {
                try {
                    Address sender = Address.fromHexString(accountOperations.normalizeAddressHex(tx.getFrom()));

                    if (tx.getTo() == null) {
                        accountOperations.deployContract(
                            sender,
                            Address.fromHexString("1234567891234567891234567891234567891234")
                        );
                        continue;
                    }

                    Address rewardNode = null;
                    if (tx.getTo() != null) {
                        try {
                            rewardNode = Address.fromHexString(accountOperations.normalizeAddressHex(tx.getTo()));
                        } catch (Exception ignored) {
                            // 'to' can be alias text in genesis; reward target is optional.
                        }
                    }

                    String calldataHex = normalizeCalldataHex(tx.getData());
                    if (calldataHex != null && !calldataHex.isEmpty()) {
                        accountOperations.genericCall(sender, calldataHex, null, tx.getGasPrice(), tx.getGasLimit());
                    }
                } catch (Exception e) {
                    System.out.println("[BLOCKCHAIN] Skipping invalid genesis tx: " + e.getMessage());
                }
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
                accountOperations.updateState(node.getBlock());

                committedBlocks.add(node.getBlock());

                Block blockToPersist = accountOperations.updateState(node.getBlock());


                persistBlock(blockToPersist, index);
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

    private static String normalizeCalldataHex(byte[] data) {
        if (data == null || data.length == 0) return null;

        String asText = new String(data, StandardCharsets.UTF_8).trim();
        if (asText.startsWith("0x") && asText.length() > 2) {
            return asText.substring(2);
        }
        if (asText.matches("(?i)^[0-9a-f]+$")) {
            return asText;
        }
        return bytesToHex(data);
    }

    public TreeNode[] getChildrenNodesFromHash(byte[] hash) {
        TreeNode node = nodesByHash.get(bytesToHex(hash));
        if (node != null) {
            return node.getChildren().toArray(new TreeNode[0]);
        }
        return new TreeNode[0];
    }

    private Block loadGenesisBlock() {
        Gson gson = new GsonBuilder()
            .registerTypeHierarchyAdapter(byte[].class, new ByteArrayHexAdapter())
            .create();
        String[] candidatePaths = {
            "../blocks/genesis.json",
            "blocks/genesis.json",
            "./blocks/genesis.json",
            "project/blocks/genesis.json"
        };

        Exception lastError = null;
        for (String path : candidatePaths) {
            File file = new File(path);
            if (!file.exists()) {
                continue;
            }

            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                Block block = gson.fromJson(reader, Block.class);
                if (block == null) {
                    throw new IllegalStateException("Parsed genesis is null: " + file.getAbsolutePath());
                }
                System.out.println("[BLOCKCHAIN] Loaded genesis from: " + file.getAbsolutePath());
                return block;
            } catch (Exception e) {
                lastError = e;
                throw new RuntimeException("Failed to parse genesis block at " + file.getAbsolutePath(), e);
            }
        }

        throw new RuntimeException(
            "Failed to find genesis block. Tried paths: " + String.join(", ", candidatePaths) +
            " | user.dir=" + System.getProperty("user.dir"),
            lastError
        );
    }

    private void persistBlock(Block block, int index) {
        try {
            Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeHierarchyAdapter(byte[].class, new ByteArrayHexAdapter())
                .create();
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

    public AccountOperations getAccountOperations() {
        return accountOperations;
    }

    public long getNonce(Address address) {
        return accountOperations.getNonce(address);
    }

    public void setNonce(Address address, long nonce) {
        accountOperations.setNonce(address, nonce);
    }

    public void incrementNonce(Address address) {
        accountOperations.incrementNonce(address);
    }

    public BigInteger getBalance(Address address) {
        return accountOperations.getBalance(address);
    }

    

    public void callSmartContractOperation(Address from, byte[] calldataHex, Address nodeAddress, long gasPrice, long gasLimit) {
        accountOperations.genericCall(from, normalizeCalldataHex(calldataHex), nodeAddress, gasPrice, gasLimit);
    }

    public void printState() {
        accountOperations.printState();
    }

}
