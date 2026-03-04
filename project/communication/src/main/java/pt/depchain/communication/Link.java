package pt.depchain.communication;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;


import java.net.*;
import java.io.*;
import java.security.*;
import java.util.*;
import pt.depchain.crypto.CryptoLibrary;
import java.util.concurrent.ConcurrentHashMap;


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
        this.cryptoLibrary = new CryptoLibrary(myPrivKey, myPubKey);

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
                            //System.out.println("Retransmitting message " + msgId + " to " + msg.getReceiver() + " (retry " + meta[1] + ")");
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

        msg.setSignature(null);
        msg.setSignature(cryptoLibrary.sign(gson.toJson(msg).getBytes()));

        byte[] data = gson.toJson(msg).getBytes();
        
        String uniqueId = makeUniqueId(myId, localMsgId);
        pending.put(uniqueId, new Object[]{msg, destType}); 
        pendingStatus.put(uniqueId, System.currentTimeMillis());

        InetSocketAddress dest;
        if (destType == Type.CLIENT) {
            dest = clientAddresses.get(destId);
        } else {
            dest = nodeAddresses.get(destId);
        }
        System.out.println("Sending message to " + destType + " " + destId + " at " + dest);
        socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));
    }

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


            byte[] signature = msg.getSignature();
            msg.setSignature(null);
            String jsonToVerify = gson.toJson(msg);
            if (!cryptoLibrary.verify(jsonToVerify.getBytes(), signature, String.valueOf((senderType + "-" + senderId)))) {
                System.out.println("Signature verification FAILED from " + senderType + " " + senderId);
                continue;
            }

            // Handle ACKs
            if (msg.getType() == Message.Type.ACK) {
                String uniqueId = makeUniqueId(msg.getSenderId(), msg.getMessageId());
                pending.remove(uniqueId);
                pendingStatus.remove(uniqueId);
                continue; 
            }

            String uniqueId = makeUniqueId(msg.getSenderId(), msg.getMessageId());
            if (delivered.contains(uniqueId)) continue;
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

    private String makeUniqueId(int senderId, int messageId) {
        return senderId + "-" + messageId;
    }
}