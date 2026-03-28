package pt.depchain.communication;

import java.io.Serializable;

public class Message implements Serializable {

    private String senderId;

    private String receiverId;

    private int messageId;

    private Type type;

    private String payload;
    
    private byte[] signature;

    public enum Type {
        APPEND_STRING, ACK,
        // HotStuff protocol messages
        NEW_VIEW, PREPARE, PREPARE_VOTE,
        PRE_COMMIT, PRE_COMMIT_VOTE,
        COMMIT, COMMIT_VOTE,
        DECIDE,
        REPLY,
        KEY_EXCHANGE,
        KEY_EXCHANGE_REPLY,
        SINC_VIEW_REQUEST,
        SINC_VIEW_REPLY
    }

    public Message(String senderId, Type type) {
        this.senderId = senderId;
        this.type = type;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getReceiver() {
        return receiverId;
    }

    public void setReceiver(String receiverId) {
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

    public byte[] getSignature() { 
        return signature; 
    }
    
    public void setSignature(byte[] signature) { 
        this.signature = signature; 
    }
}