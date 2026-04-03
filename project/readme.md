# Requirements:
- Java above version 21, 
- Apache Maven above version 3.8.
- Openssl 3.6.1:27


# Troubleshooting:
- If you are not running the project with launch make sure you have all configs set:  
public and private keys,  
membership.json,  
genesis.json   
groupKey.json.  

- In case any port is till binded and you get "port is already in use" error you can terminate previous executions with:
```
taskkill /F /IM java.exe /T
```
- Something else

# Compile & Run


Compile project with: 

```
mvn clean install -DskipTests
```

## Run Automated Script:
```
.\launch.ps1 -c <Nº of Clients> -r <Nº of Nodes>
```
If your config folder doesnt have any keys, this script will generate them for you, as well as a genesis block, where the addresses are generated based on the Node/Client public keys.

## Run Client: 
```
mvn exec:java "-Dexec.mainClass=pt.depchain.client.ClientMain" "-Dexec.args=<clientid> <PrivKey_Path> <PubKey_Path>"

Example:
mvn exec:java "-Dexec.mainClass=pt.depchain.client.ClientMain" "-Dexec.args=client1 ../config/client1.priv ../config/client1.pub"
```

## Run Safe Node: 
```
mvn exec:java "-Dexec.mainClass=pt.depchain.service.Node" "-Dexec.args=<Nodeid> <PrivKey_Path> <PubKey_Path>"  

Example:
mvn exec:java "-Dexec.mainClass=pt.depchain.service.Node" "-Dexec.args=1 ../config/node1.priv ../config/node1.pub" 
```

## Run Byzantine Node: 
```
mvn exec:java "-Dexec.mainClass=pt.depchain.service.ByzantineNode" "-Dexec.args=<id> [behavior]"  
```
### Byzantine Node behaviors: (If not provided, the default is bad-hash)  

**bad-hash** - When Node is leader will give a corrupted command.  
**duplicate-msg** - Node sends 2 identical votes.  
**bad-share** - This node will sign with an invalide share.  
**wrong-sender** - Node spoofs its ID as the next one in line, triggers warning.  

# Full-scope tests: 

They initialize 4 nodes, one or more of them might be byzantine considering the different examples.

Run Command: 
```
mvn clean test -pl test -Dtest=<TestName>  
```

### Test List:  

**CorrectAppendTest** - 4 safe nodes, 2 clients, consensus reached.

**LeaderCrashAppendTest** - 4 safe nodes, leader crash happens in view 2, consensus reached.  

**ReplicaCrashAppendTest** - 4 safe nodes, replica crashes happens in view 2, consensus reached.  

**WrongSenderTest** - 3 Safe nodes, 1 Byzantine Node that fakes its ID as the next node, consensus reached.  

**WrongHashTest** - 3 Safe nodes, 1 Byzantine Leader Node that proposes a block with a corrupted hash, consensus reached.  

**DuplicateMessageTest** - 3 Safe nodes, 1 Byzantine Node that sends two votes, consensus reached.  

**OneBadShareTest** -  3 Safe nodes, 1 Byzantine Node that signs with an invalid share, consensus reached.  

**TwoBadSharesTest** - 2 Safe nodes, 2 Byzantine Node that signs with an invalid share each, consensus not reached.  

**AprovalFrontrunningTest** - 1st block: client1 calls "increase allowance client2 100", 
2nd block: client1 calls "decrease allowance client2 50", but client2 calls "transferfrom client1 client2 100" before this is executed 
3rd block: cliente2 calls "transferfrom  client1 client2 50"


# Unit tests: 
They test specific small parts of our code.  

Run Command: 
```
mvn clean test -pl service -Dtest=BFTConsensusTest.<TestName>  
```

### Test List: 

**testLeaderElection** - Test that everytime the view advances, the leader changes to the next in line.  

**testMultipleLeaderChanges** - Test that after many view advances, the nodes share the same view. 

**testBlockchainConsistency** - Test that all nodes have GENESIS block at the begging and test appending a node.   

**testLeaderRejectsByzantineMessages** - Test verify threshold voting, one of the nodes signs a different message".  

**testBlockchainCommitHistory** - Test that the blockchain commits the appended commands.  