package pt.depchain.crypto;

import threshsig.*;
import java.io.*;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ThresholdKeyGenerator {
    public static void main(String[] args) throws Exception {
        int k = 3;
        int l = 4;
        int keysize = 512;

        Dealer dealer = new Dealer(keysize);
        dealer.generateKeys(k, l);

        GroupKey groupKey = dealer.getGroupKey();
        KeyShare[] shares = dealer.getShares();

        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        // --- GroupKey JSON ---
        Map<String, String> groupJson = new HashMap<>();
        groupJson.put("k", Integer.toString(groupKey.getK()));
        groupJson.put("l", Integer.toString(groupKey.getL()));
        groupJson.put("keysize", Integer.toString(keysize));
        groupJson.put("n", groupKey.getModulus().toString());
        groupJson.put("e", groupKey.getExponent().toString());

        Files.writeString(Paths.get("../config/groupKey.json"), gson.toJson(groupJson));

        // --- Save each share ---
        Field gvField = KeyShare.class.getDeclaredField("groupVerifier");
        gvField.setAccessible(true);
        Field nField = KeyShare.class.getDeclaredField("n");
        nField.setAccessible(true);

        for (int i = 0; i < shares.length; i++) {
            KeyShare s = shares[i];

            Map<String, String> shareJson = new HashMap<>();
            shareJson.put("id", Integer.toString(s.getId()));
            shareJson.put("secret", s.getSecret().toString());
            shareJson.put("verifier", s.getVerifier().toString());
            shareJson.put("groupVerifier", gvField.get(s).toString());
            shareJson.put("n", nField.get(s).toString());
            shareJson.put("signVal", s.getSignVal().toString());

            Files.writeString(Paths.get("../config/node" + (i + 1) + ".share.json"), gson.toJson(shareJson));
        }

        System.out.println("Threshold key generation complete.");
    }
}