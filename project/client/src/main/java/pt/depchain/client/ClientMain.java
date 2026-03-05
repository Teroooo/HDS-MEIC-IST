package pt.depchain.client;

import pt.depchain.communication.*;
import java.net.*;
import java.util.Scanner;
import com.google.gson.JsonObject;

public class ClientMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java ClientMain <clientId>");
            System.exit(1);
        }  

        int clientId = Integer.parseInt(args[0]);
        int messageId = 0; 
        Link link = new Link(clientId, Link.Type.CLIENT, "../config/membership.json", "../config/client" + clientId + ".priv", "../config/client" + clientId + ".pub");

        new Thread(() -> {
            try {
                while (true) {
                    Message msg = link.receive();
                    System.out.println("\nReceived from " + msg.getSenderId() + ": " + msg.getPayload()); 
                    System.out.print("> ");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.println("\nSelect operation:");
            System.out.println("1 - Append String");
            System.out.println("0 - Exit");
            System.out.print("> ");

            String choice = scanner.nextLine();

            switch (choice) {

                case "1":
                           
                    System.out.print("Enter string to append: ");
                    String text = scanner.nextLine();

                    messageId++;
                    JsonObject payloadJson = new JsonObject();
                    payloadJson.addProperty("text", text);
                    payloadJson.addProperty("clientId", clientId);
                    payloadJson.addProperty("messageId", messageId);

                    String payload = payloadJson.toString();
                    int[] replicas = {1,2,3,4};

                   
                    link.broadcastWithId(replicas, Message.Type.APPEND_STRING, payload, messageId);

                    System.out.println("Append request sent.");
                    break;

                case "0":
                    System.exit(0);
                    break;

                default:
                    System.out.println("Invalid option.");
            }
        }
    }
}