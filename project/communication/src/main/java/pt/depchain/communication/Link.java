package pt.depchain.communication;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;


import java.net.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.security.*;
import java.util.*;
import pt.depchain.crypto.CryptoLibrary;
import java.util.concurrent.ConcurrentHashMap;


public class Link {

    public enum Type { CLIENT, NODE }
    private final String myId;
    private final Type myType;
    private final Gson gson = new Gson();
    private final DatagramSocket socket;
    private final CryptoLibrary cryptoLibrary;
    private final Map<String, InetSocketAddress> clientAddresses = new HashMap<>();
    private final Map<String, InetSocketAddress> nodeAddresses = new HashMap<>();
    private final Set<String> delivered = Collections.synchronizedSet(new HashSet<>()); 
    private final Map<String, Object[]> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingStatus = new ConcurrentHashMap<>();
    private int nextMessageId = 0;  
    private final long TIMEOUT = 1000; // ms

    public Link(String myId, Type myType, String membershipFile, String myPrivKey, String myPubKey, CryptoLibrary crypto) throws Exception {
        this.myId = myId;
        this.myType = myType;
        this.cryptoLibrary = crypto;

        JsonArray root = JsonParser.parseReader(new FileReader(membershipFile)).getAsJsonArray();
        JsonObject membership = root.get(0).getAsJsonObject();

        JsonArray clients = membership.getAsJsonArray("clients");
        for (JsonElement elem : clients) {
            JsonObject obj = elem.getAsJsonObject();
            String id = obj.get("id").getAsString();
            String host = obj.get("host").getAsString();
            int port = obj.get("port").getAsInt();
            String pub = obj.get("pub").getAsString();

            clientAddresses.put(id, new InetSocketAddress(host, port));
            cryptoLibrary.addPublicKey("CLIENT-" + id, "../config/" + pub);
        }

        JsonArray nodes = membership.getAsJsonArray("nodes");
        for (JsonElement elem : nodes) {
            JsonObject obj = elem.getAsJsonObject();
            String id = obj.get("id").getAsString();
            String host = obj.get("host").getAsString();
            int port = obj.get("port").getAsInt();
            String pub = obj.get("pub").getAsString();

            nodeAddresses.put(id, new InetSocketAddress(host, port));
            cryptoLibrary.addPublicKey("NODE-" + id, "../config/" + pub);
        }
    
        InetSocketAddress myAddr;
        if (myType == Type.CLIENT) {
            myAddr = clientAddresses.get(myId);
        } else {
            myAddr = nodeAddresses.get(myId);
        }

        if (myAddr == null) {
            throw new RuntimeException("ID " + myId + " not found in membership");
        }
        
        socket = new DatagramSocket(myAddr.getPort());

        System.out.println("Binding " + myType + " " + myId + " to port " + myAddr.getPort());

        new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(100); // Check every 100ms
                    long now = System.currentTimeMillis();

                    for (Map.Entry<String, Object[]> entry : pending.entrySet()) {
                        String uniqueId = entry.getKey();
                        Message msg = (Message) entry.getValue()[0];
                        Type destType = (Type) entry.getValue()[1];
                        long lastSent = pendingStatus.get(uniqueId);

                        if (now - lastSent >= TIMEOUT) {
                            
                            InetSocketAddress dest;
                            if (destType == Type.CLIENT) {
                                dest = clientAddresses.get(msg.getReceiver());
                            } else {
                                dest = nodeAddresses.get(msg.getReceiver());
                            }
                            byte[] data = gson.toJson(msg).getBytes();
                            socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));

                            pendingStatus.put(uniqueId, now);
                            //System.out.println("[LINK] Retransmitting message " + uniqueId + " to " + destType + " " + msg.getReceiver() + " (retry)");
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private synchronized int getNextMessageId() {
        return nextMessageId++;
    }

    public void send(Type destType, String destId, Message.Type type, String payload) throws Exception {
        Message msg = new Message(myId, type);
        int localMsgId = getNextMessageId();
        msg.setMessageId(localMsgId);
        msg.setPayload(payload);
        msg.setReceiver(destId);
        msg.setSignature(null);
        if (destType == Type.CLIENT || myType == Type.CLIENT){
            msg.setSignature(cryptoLibrary.sign(gson.toJson(msg).getBytes()));
        }
        else{
                    // Encrypt payload ONLY for NODE
                if (type != Message.Type.KEY_EXCHANGE && type != Message.Type.ACK && type != Message.Type.KEY_EXCHANGE_REPLY) {
                    byte[] encryptedBytes = cryptoLibrary.encryptAES(payload.getBytes(), destId);
                    String encryptedPayload = Base64.getEncoder().encodeToString(encryptedBytes);
                    msg.setPayload(encryptedPayload);
                }
        }


        byte[] data = gson.toJson(msg).getBytes();
        
        String uniqueId = makeUniqueId(myId, localMsgId, destType);
        pending.put(uniqueId, new Object[]{msg, destType});
        pendingStatus.put(uniqueId, System.currentTimeMillis());

        InetSocketAddress dest;
        if (destType == Type.CLIENT) {
            dest = clientAddresses.get(destId);
        } else {
            dest = nodeAddresses.get(destId);
        }
        //System.out.println("Sending message to " + destType + " " + destId + " at " + dest);
        socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));
    }

    public void sendAs(String spoofedSenderId, Type destType, String destId, Message.Type type, String payload) throws Exception {
        Message msg = new Message(spoofedSenderId, type);
        int localMsgId = getNextMessageId();
        msg.setMessageId(localMsgId);
        msg.setPayload(payload);
        msg.setReceiver(destId);
        msg.setSignature(null);
        if (destType == Type.CLIENT){
            msg.setSignature(cryptoLibrary.sign(gson.toJson(msg).getBytes()));
        }

        // Encrypt payload ONLY for NODE
        if (destType == Type.NODE && type != Message.Type.KEY_EXCHANGE && type != Message.Type.ACK && type != Message.Type.KEY_EXCHANGE_REPLY) {
            byte[] encryptedBytes = cryptoLibrary.encryptAES(payload.getBytes(), destId);
            String encryptedPayload = Base64.getEncoder().encodeToString(encryptedBytes);
            msg.setPayload(encryptedPayload);
        }

        byte[] data = gson.toJson(msg).getBytes();
        
        String uniqueId = makeUniqueId(myId, localMsgId, destType);
        pending.put(uniqueId, new Object[]{msg, destType});
        pendingStatus.put(uniqueId, System.currentTimeMillis());

        InetSocketAddress dest;
        if (destType == Type.CLIENT) {
            dest = clientAddresses.get(destId);
        } else {
            dest = nodeAddresses.get(destId);
        }
        //System.out.println("Sending message to " + destType + " " + destId + " at " + dest);
        socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));
    }

    public Message receive() throws Exception {
        byte[] buffer = new byte[65536];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        
        while (true) {
            socket.receive(packet);

            String json = new String(packet.getData(), 0, packet.getLength());
            Message msg = gson.fromJson(json, Message.class);

            String senderType;
            String senderId; // String for client, int for node

            if (clientAddresses.containsKey(msg.getSenderId())) {
                senderType = "CLIENT";
                senderId = msg.getSenderId(); 
            } else if (nodeAddresses.containsKey(msg.getSenderId())) {
                senderType = "NODE";
                senderId = msg.getSenderId(); 
            } else {
                System.out.println("Unknown sender: " + msg.getSenderId());
                continue;
            }

            // Decrypt payload if it's a NODE message
            if (myType == Type.NODE && "NODE".equals(senderType) && msg.getType() != Message.Type.KEY_EXCHANGE && msg.getType() != Message.Type.ACK && msg.getType() != Message.Type.KEY_EXCHANGE_REPLY) {
                byte[] encryptedBytes = Base64.getDecoder().decode(msg.getPayload());
                byte[] decryptedBytes = cryptoLibrary.decryptAES(encryptedBytes, msg.getSenderId());
                String decryptedPayload = new String(decryptedBytes, StandardCharsets.UTF_8);
                msg.setPayload(decryptedPayload);
            }

            if(myType.equals("CLIENT") || senderType.equals("CLIENT")){
                byte[] signature = msg.getSignature();
                msg.setSignature(null);
                String jsonToVerify = gson.toJson(msg);
                if (!cryptoLibrary.verify(jsonToVerify.getBytes(), signature, String.valueOf((senderType + "-" + senderId)))) {
                    System.out.println("Signature verification FAILED from " + senderType + " " + senderId);
                    continue;
                }
            }


            // Handle ACKs
            if (msg.getType() == Message.Type.ACK) {
                String originalSenderId = msg.getReceiver(); 
                Type type = senderType.equals("CLIENT") ? Type.CLIENT : Type.NODE;
                String uniqueId;
                if (myType == Type.CLIENT) {
                    // For client broadcasts, we added "-replicaId" to the uniqueId
                    uniqueId = makeUniqueId(originalSenderId, msg.getMessageId(), type) + "-" + msg.getSenderId();
                } else {
                    // Node-to-node messages use the old uniqueId
                    uniqueId = makeUniqueId(originalSenderId, msg.getMessageId(), type);
                }

                if (pending.remove(uniqueId) != null) {
                    pendingStatus.remove(uniqueId);
                    //System.out.println("[LINK] ACK processed, removed from pending: " + uniqueId);
                } else {
                    System.out.println("[LINK] ACK received but could not find pending message: " + uniqueId);
                }
                continue; 
            }

            Type senderTypeEnum = senderType.equals("CLIENT") ? Type.CLIENT : Type.NODE;
            String uniqueId = makeUniqueId(msg.getSenderId(), msg.getMessageId(), senderTypeEnum);
            if (delivered.contains(uniqueId)) {
                System.out.println("[LINK] Duplicate received, ignoring: " + uniqueId 
                    + " type=" + msg.getType() + " from " + msg.getSenderId());
                continue;
            }
            delivered.add(uniqueId);

            Message ack = new Message(myId, Message.Type.ACK);
            ack.setMessageId(msg.getMessageId());
            ack.setReceiver(msg.getSenderId());
            InetSocketAddress ackDest;
            if ("CLIENT".equals(senderType)) {
                ackDest = clientAddresses.get(senderId);
            } else {
                ackDest = nodeAddresses.get(senderId);
            }
            ack.setSignature(cryptoLibrary.sign(gson.toJson(ack).getBytes()));

            socket.send(new DatagramPacket(gson.toJson(ack).getBytes(), gson.toJson(ack).getBytes().length, ackDest.getAddress(), ackDest.getPort()));
            return msg;
        }
    }

    public void broadcastWithId(String[] replicaIds, Message.Type type, String payload, int messageId) throws Exception {
        for (String replicaId : replicaIds) {
            Message msg = new Message(myId, type);
            msg.setMessageId(messageId);  
            msg.setPayload(payload);
            msg.setReceiver(replicaId);

            msg.setSignature(null);
            msg.setSignature(cryptoLibrary.sign(gson.toJson(msg).getBytes()));

            byte[] data = gson.toJson(msg).getBytes();
            String uniqueId = makeUniqueId(myId, messageId, Type.NODE)  + "-" + replicaId;  
            pending.put(uniqueId, new Object[]{msg, Type.NODE});
            pendingStatus.put(uniqueId, System.currentTimeMillis());

            InetSocketAddress dest = nodeAddresses.get(replicaId);
            socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));
        }
    }

    private String makeUniqueId(String senderId, int messageId, Type senderType) {
        return senderType + "-" + senderId + "-" + messageId;
    }
}