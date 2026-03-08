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
import threshsig.SigShare;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;


public class Link {

    public enum Type { CLIENT, NODE }
    private final int myId;
    private final Type myType;
    private final Gson gson = new Gson();
    private final DatagramSocket socket;
    private final CryptoLibrary cryptoLibrary;
    private final Map<Integer, InetSocketAddress> clientAddresses = new HashMap<>();
    private final Map<Integer, InetSocketAddress> nodeAddresses = new HashMap<>();
    private final Set<String> delivered = Collections.synchronizedSet(new HashSet<>()); 
    private final Map<String, Object[]> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingStatus = new ConcurrentHashMap<>();
    private int nextMessageId = 0;  
    private final long TIMEOUT = 1000; // ms

    public Link(int myId, Type myType, String membershipFile, String myPrivKey, String myPubKey) throws Exception {
        this.myId = myId;
        this.myType = myType;
        this.cryptoLibrary = new CryptoLibrary(myPrivKey, myPubKey, myId);

        JsonArray root = JsonParser.parseReader(new FileReader(membershipFile)).getAsJsonArray();
        JsonObject membership = root.get(0).getAsJsonObject();

        JsonArray clients = membership.getAsJsonArray("clients");
        for (JsonElement elem : clients) {
            JsonObject obj = elem.getAsJsonObject();
            int id = obj.get("id").getAsInt();
            String host = obj.get("host").getAsString();
            int port = obj.get("port").getAsInt();
            String pub = obj.get("pub").getAsString();

            clientAddresses.put(id, new InetSocketAddress(host, port));
            cryptoLibrary.addPublicKey("CLIENT-" + id, "../config/" + pub);
        }

        JsonArray nodes = membership.getAsJsonArray("nodes");
        for (JsonElement elem : nodes) {
            JsonObject obj = elem.getAsJsonObject();
            int id = obj.get("id").getAsInt();
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

    public void send(Type destType, int destId, Message.Type type, String payload) throws Exception {
        Message msg = new Message(myId, type);
        int localMsgId = getNextMessageId();
        msg.setMessageId(localMsgId);
        msg.setPayload(payload);
        msg.setReceiver(destId);

        //System.out.println("send: " + msg.toString());

        msg.setSignature(null);
        byte[] bytesToSign = gson.toJson(msg.getSeedMap()).getBytes(StandardCharsets.UTF_8);
        msg.setSignature(cryptoLibrary.signShare(bytesToSign));

        byte[] data = gson.toJson(msg).getBytes();
        //System.out.println("Data to send: " + new String(data));
        
        String uniqueId = makeUniqueId(myId, localMsgId, destType);
        pending.put(uniqueId, new Object[]{msg, destType});
        pendingStatus.put(uniqueId, System.currentTimeMillis());

        InetSocketAddress dest;
        if (destType == Type.CLIENT) {
            dest = clientAddresses.get(destId);
        } else {
            dest = nodeAddresses.get(destId);
        }
        if(myId == 3 && destId == 1) {
            System.out.println("Sending message to " + destType + " " + destId + " at " + dest);
        }
        socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));
    }

    // Map to collect sigshares for each unique message
    private final Map<String, Map<Integer, SigShare>> sigShareBuffer = new ConcurrentHashMap<>();
    
    public Message receive() throws Exception {
        byte[] buffer = new byte[65536];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (true) {
            socket.receive(packet);

            InetAddress senderAddress = packet.getAddress();
            int senderPort = packet.getPort();
            InetSocketAddress senderSocket = new InetSocketAddress(senderAddress, senderPort);

            String senderType = "UNKNOWN";
            int senderId = -1;

            for (var entry : clientAddresses.entrySet()) {
                if (entry.getValue().equals(senderSocket)) {
                    senderType = "CLIENT";
                    senderId = entry.getKey();
                    break;
                }
            }
            if (senderId == -1) {
                for (var entry : nodeAddresses.entrySet()) {
                    if (entry.getValue().equals(senderSocket)) {
                        senderType = "NODE";
                        senderId = entry.getKey();
                        break;
                    }
                }
            }

            String json = new String(packet.getData(), 0, packet.getLength());
            Message msg = gson.fromJson(json, Message.class);

            if(msg.getType() != Message.Type.ACK) {
                SigShare signature = msg.getSignature();
                msg.setSignature(null);
                //System.out.println("Received message: " + msg.toString() + " from " + senderType + " " + senderId);
                String sigKey = msg.getType() + ":" + msg.getMessageId();
                
                // 1. Get or create the inner map for this specific message
                Map<Integer, SigShare> sharesMap = sigShareBuffer.computeIfAbsent(sigKey, 
                    k -> new ConcurrentHashMap<>());

                // 2. Add the share using the Node ID as the key
                // This automatically overwrites or ignores duplicates from the same node
                sharesMap.put(signature.getId(), signature);

                            // Only verify/process if we have at least k shares
                if (sharesMap.size() >= cryptoLibrary.k) {
                    SigShare[] sigSharesArray = sharesMap.values().toArray(new SigShare[0]); 
                    try {
                        byte[] bytesToVerify = gson.toJson(msg.getSeedMap()).getBytes(StandardCharsets.UTF_8);
                        /*System.out.println("entrou aqui: " + new String(jsonToVerify.getBytes()));
                        
                        for(SigShare s : sigSharesArray) {
                            System.out.println("Share from node " + s);
                        }*/
                        if (!cryptoLibrary.verifyShare(bytesToVerify, sigSharesArray)) {
                            System.out.println("Threshold signature verification FAILED for " + sigKey);
                            sigShareBuffer.remove(sigKey);
                            continue;
                        }
                    } catch (Exception ex) {
                        System.out.println("Threshold signature verification error: " + ex.getMessage());
                        sigShareBuffer.remove(sigKey);
                        continue;
                    }
                    sigShareBuffer.remove(sigKey); // Clean up after verification
                }
            }

                // Handle ACKs
                if (msg.getType() == Message.Type.ACK) {
                    int originalSenderId = msg.getReceiver(); 
                    Type type = senderType.equals("CLIENT") ? Type.CLIENT : Type.NODE;
                    String uniqueId;
                    if (myType == Type.CLIENT) {
                        uniqueId = makeUniqueId(originalSenderId, msg.getMessageId(), type) + "-" + msg.getSenderId();
                    } else {
                        uniqueId = makeUniqueId(originalSenderId, msg.getMessageId(), type);
                    }

                    if (pending.remove(uniqueId) != null) {
                        pendingStatus.remove(uniqueId);
                    } else {
                        System.out.println("[LINK] ACK received but could not find pending message: " + uniqueId);
                    }
                    continue; 
                }

                Type senderTypeEnum = senderType.equals("CLIENT") ? Type.CLIENT : Type.NODE;
                String uniqueId = makeUniqueId(msg.getSenderId(), msg.getMessageId(), senderTypeEnum);
                System.out.println(msg.getSenderId() + " " + msg.getMessageId() + " uniqueId: " + uniqueId);
                for (String id : delivered) {
                    System.out.println("Delivered: " + id);
                }
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
                ack.setSignature(cryptoLibrary.signShare(gson.toJson(ack).getBytes()));

                socket.send(new DatagramPacket(gson.toJson(ack).getBytes(), gson.toJson(ack).getBytes().length, ackDest.getAddress(), ackDest.getPort()));
                return msg;
            //System.err.println("Not enough shares yet, keep waiting: " + sigShareBuffer.toString());
        }
    }

    public void broadcastWithId(int[] replicaIds, Message.Type type, String payload, int messageId) throws Exception {
        for (int replicaId : replicaIds) {
            Message msg = new Message(myId, type);
            msg.setMessageId(messageId);  
            msg.setPayload(payload);
            msg.setReceiver(replicaId);

            msg.setSignature(null);
            msg.setSignature(cryptoLibrary.signShare(gson.toJson(msg).getBytes()));

            byte[] data = gson.toJson(msg).getBytes();
            String uniqueId = makeUniqueId(myId, messageId, Type.NODE)  + "-" + replicaId;  
            pending.put(uniqueId, new Object[]{msg, Type.NODE});
            pendingStatus.put(uniqueId, System.currentTimeMillis());

            InetSocketAddress dest = nodeAddresses.get(replicaId);
            socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));
        }
    }

    private String makeUniqueId(int senderId, int messageId, Type senderType) {
        return senderType + "-" + senderId + "-" + messageId;
    }
}