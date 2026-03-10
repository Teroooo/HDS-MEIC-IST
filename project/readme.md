Requirements:
Java above version 21, Apache Maven above version 3.8.

Compile project with: mvn clean install -DskipTests

Run Client: mvn exec:java "-Dexec.mainClass=pt.depchain.client.ClientMain" "-Dexec.args=<id>"
Run Safe Node: mvn exec:java "-Dexec.mainClass=pt.depchain.service.Node" "-Dexec.args=<id>"
Run Byzantine Node: mvn exec:java "-Dexec.mainClass=pt.depchain.service.ByzantineNode" "-Dexec.args=<id> [behavior]"

Byzantine Node behaviors: (If not provided, the default is bad-hash)

bad-hash - When Node is leader will give a corrupted command.
duplicate-msg - Node sends 2 identical votes.
bad-share - This node will sign with an invalide share.
wrong-sender - Node spoofs its ID as the next one in line, triggers warning.

Full-scope tests: they initialize 4 nodes, one or more of them might be byzantine considering the different examples.

Run Command: mvn clean test -pl test -Dtest=<TestName>

Test List:
CorrectAppendTest - 4 safe nodes, consensus reached.
LeaderCrashAppendTest - 4 safe nodes, leader crash happens in view 2, consensus reached.
ReplicaCrashAppendTest - 4 safe nodes, replica crashes happens in view 2, consensus reached.
WrongSenderTest - 3 Safe nodes, 1 Byzantine Node that fakes its ID as the next node, consensus reached.
WrongHashTest - 3 Safe nodes, 1 Byzantine Leader Node that proposes a block with a corrupted hash, consensus reached.
DuplicateMessageTest - 3 Safe nodes, 1 Byzantine Node that sends two votes, consensus reached.
OneBadShareTest -  3 Safe nodes, 1 Byzantine Node that signs with an invalid share, consensus reached.
TwoBadSharesTest - 2 Safe nodes, 2 Byzantine Node that signs with an invalid share each, consensus not reached.


Modular tests: They test specific small parts of our code.

Run Command: mvn clean test -pl service -Dtest=BFTConsensusTest.<TestName>

Test List:
testLeaderElection - Test that everytime the view advances, the leader changes to the next in line.
testMultipleLeaderChanges - Test that after many view advances, the nodes share the same view.
testBlockchainConsistency - Test that all nodes have GENESIS block at the begging and test appending a node. 
testLeaderRejectsByzantineMessages - Test verify threshold voting, one of the nodes signs a different message".
testBlockchainCommitHistory - Test that the blockchain commits the appended commands.