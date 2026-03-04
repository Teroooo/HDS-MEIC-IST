package pt.depchain.service;

import pt.depchain.communication.*;
import java.net.*;

public class Node {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java Node <nodeId>");
            System.exit(1);
        }  

        int nodeId = Integer.parseInt(args[0]);

        Link link = new Link(nodeId, Link.Type.NODE, "../config/membership.json", "../config/node" + nodeId + ".priv", "../config/node" + nodeId + ".pub");

        System.out.println("Node " + nodeId + " listening...");

        while (true) {
            System.out.println("Waiting for messages...");
            Message msg = link.receive();
            handleMessage(link, nodeId, msg);          
        }
    }

     private static void handleMessage(Link link, int nodeId, Message msg) throws Exception {

        switch (msg.getType()) {

            case APPEND_STRING:
                handleAppend(link, nodeId, msg);
                break;

            //Implement other message types

            default:
                System.out.println("Unknown message type from " + msg.getSenderId());
        }
    }

    private static void handleAppend(Link link, int nodeId, Message msg) throws Exception {

        System.out.println("Node " + nodeId + " received APPEND from " + msg.getSenderId() + ": " + msg.getPayload());
    }
}
