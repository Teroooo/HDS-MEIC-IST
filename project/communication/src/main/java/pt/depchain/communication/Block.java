package pt.depchain.communication;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

public class Block implements Serializable{

    @SerializedName("block_hash") // Matches the JSON key exactly
    private String hash;

    @SerializedName("previous_block_hash")
    private String previousHash;

    private List<Transaction> transactions;

    @SerializedName("state") // Maps the "state" JSON object to your "states" Map
    private Map<String, State> states;

    // No-args constructor is required by Gson
    public Block() {}

    public Block(String hash, String previousHash, List<Transaction> transactions, Map<String, State> states) {
        this.hash = hash;
        this.previousHash = previousHash;
        this.transactions = transactions;
        this.states = states;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<Transaction> transactions) {
        this.transactions = transactions;
    }

    public Map<String, State> getStates() {
        return states;
    }

    public void setStates(Map<String, State> states) {
        this.states = states;
    }

    @Override
    public String toString() {
        // setPrettyPrinting() adds the indentation and line breaks
        // serializeNulls() ensures "previous_block_hash": null actually shows up
        Gson gson = new GsonBuilder()
                        .setPrettyPrinting()
                        .serializeNulls() 
                        .create();
        return gson.toJson(this);
    }
}
