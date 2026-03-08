package pt.depchain.communication;

import java.io.Serializable;

import threshsig.SigShare;

public class Message implements Serializable {

    private int senderId;

    private int receiverId;

    private int messageId;

    private Type type;

    private String payload;
    
    private SigShare signature;

    public enum Type {
        APPEND_STRING, ACK,
        // HotStuff protocol messages
        NEW_VIEW, PREPARE, PREPARE_VOTE,
        PRE_COMMIT, PRE_COMMIT_VOTE,
        COMMIT, COMMIT_VOTE,
        DECIDE
    }

    public Message(int senderId, Type type) {
        this.senderId = senderId;
        this.type = type;
    }

    public int getSenderId() {
        return senderId;
    }

    public void setSenderId(int senderId) {
        this.senderId = senderId;
    }

    public int getReceiver() {
        return receiverId;
    }

    public void setReceiver(int receiverId) {
        this.receiverId = receiverId;
    }

    public int getMessageId() {
        return messageId;
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getPayload() { 
        return payload; 
    }

    public void setPayload(String payload) { 
        this.payload = payload; 
    }

    public SigShare getSignature() { 
        return signature; 
    }
    
    public void setSignature(SigShare signature) { 
        this.signature = signature; 
    }

    public java.util.TreeMap<String, Object> getSeedMap() {
    java.util.TreeMap<String, Object> map = new java.util.TreeMap<>();
    // DO NOT include senderId or receiverId if they change per hop
    map.put("messageId", this.messageId);
    map.put("type", this.type.name());
    map.put("payload", this.payload == null ? "" : this.payload);
    return map;
}
}