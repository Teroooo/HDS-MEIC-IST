package pt.depchain.communication;

import java.io.Serializable;

public class Message implements Serializable {

    private int senderId;

    private int receiverId;

    private int messageId;

    private Type type;

    private String payload;
    
    private byte[] signature;

    public enum Type {
        APPEND_STRING, ACK
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

    public byte[] getSignature() { 
        return signature; 
    }
    
    public void setSignature(byte[] signature) { 
        this.signature = signature; 
    }
}