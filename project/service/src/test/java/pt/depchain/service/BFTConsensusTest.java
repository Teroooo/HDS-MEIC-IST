package pt.depchain.service;

import org.junit.jupiter.api.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import pt.depchain.communication.Link;
import pt.depchain.communication.Message;
import pt.depchain.crypto.CryptoLibrary;
import pt.depchain.hotstuff.Blockchain;
import pt.depchain.hotstuff.HotStuffConsensus;
import pt.depchain.hotstuff.TreeNode;
import threshsig.SigShare;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;

/**
 * NOTA: Estes são testes modulares para testar pequenas partes do código, não simulações completas.
 * Para testes completos ver readme.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BFTConsensusTest {

    private static final int TOTAL_NODES = 3; // 3 nós: 2 honestos + 1 bizantino
    private static final int MAX_FAULTS = 1; // f = 1, tolerando 1 falha bizantina
    
    
    /**
     * Teste 1: Eleição de líder funciona corretamente em views rotativas
     * View 1: Líder é nó 1, View 2: Líder é nó 2, etc.
     */
    @Test
    @Order(1)
    @DisplayName("Eleição de líder em rondas rotativas")
    void testLeaderElection() throws Exception {
        System.out.println("\n=== TEST 1: Eleição de líder ===");
        
        HotStuffConsensus[] nodes = new HotStuffConsensus[TOTAL_NODES];
        
        for (int i = 0; i < TOTAL_NODES; i++) {
            Link mockLink = mock(Link.class);
            CryptoLibrary mockCrypto = mock(CryptoLibrary.class);
            Blockchain blockchain = new Blockchain();
            
            doNothing().when(mockLink).send(any(), anyString(), any(), anyString());
            when(mockCrypto.signShare(any())).thenReturn(mock(threshsig.SigShare.class));
            when(mockCrypto.verifyShare(any(), any())).thenReturn(true);
            
            nodes[i] = new HotStuffConsensus(i + 1, TOTAL_NODES, MAX_FAULTS, 
                                            mockLink, mockCrypto, blockchain);
        }
        
        // View 1: Nó 1 é líder
        assertTrue(nodes[0].isLeader(), "Nó 1 deveria ser líder na view 1");
        assertFalse(nodes[1].isLeader(), "Nó 2 NÃO deveria ser líder na view 1");
        
        // Avançar para view 2
        for (HotStuffConsensus node : nodes) {
            node.advanceView();
        }
        
        // View 2: Nó 2 é líder
        assertFalse(nodes[0].isLeader(), "Nó 1 NÃO deveria ser líder na view 2");
        assertTrue(nodes[1].isLeader(), "Nó 2 deveria ser líder na view 2");

        // Avançar para view 3
        for (HotStuffConsensus node : nodes) {
            node.advanceView();
        }
        
        // View 3: Nó 3 é líder
        assertFalse(nodes[1].isLeader(), "Nó 2 NÃO deveria ser líder na view 3");
        assertTrue(nodes[2].isLeader(), "Nó 3 deveria ser líder na view 3");
        
        System.out.println("✓ Eleição de líder funciona em rondas rotativas\n");
    }
    
    
    /**
     * Teste 2: Múltiplos nós podem coexistir e mudar de líder
     * Verifica que após múltiplas mudanças de view, liderança rota corretamente
     */
    @Test
    @Order(2)
    @DisplayName("Múltiplas mudanças de líder")
    void testMultipleLeaderChanges() throws Exception {
        System.out.println("\n=== TEST 2: Múltiplas mudanças de líder ===");
        
        HotStuffConsensus[] nodes = new HotStuffConsensus[TOTAL_NODES];
        
        for (int i = 0; i < TOTAL_NODES; i++) {        
            Link mockLink = mock(Link.class);
            CryptoLibrary mockCrypto = mock(CryptoLibrary.class);
            Blockchain blockchain = new Blockchain();
            
            doNothing().when(mockLink).send(any(), anyString(), any(), anyString());
            when(mockCrypto.signShare(any())).thenReturn(mock(threshsig.SigShare.class));
            when(mockCrypto.verifyShare(any(), any())).thenReturn(true);
            
            nodes[i] = new HotStuffConsensus(i + 1, TOTAL_NODES, MAX_FAULTS,
                                            mockLink, mockCrypto, blockchain);
        }
        
        // Avançar múltiplas views
        for (int view = 0; view < 10; view++) {
            for (HotStuffConsensus node : nodes) {
                node.advanceView();
            }
        }
        
        // Verificar que todos estão na mesma view
        int targetView = nodes[0].getViewNumber();
        for (HotStuffConsensus node : nodes) {
            assertEquals(targetView, node.getViewNumber(), 
                        "Todos os nós devem estar na mesma view");
        }
        
        System.out.println("✓ Sistema sobrevive a múltiplas mudanças de view\n");
    }
    
    /**
     * Teste 3: Blockchain mantém consistência entre nós
     * Verifica que múltiplos nós têm blockchains com mesma estrutura inicial
     */
    @Test
    @Order(3)
    @DisplayName("Blockchains mantêm consistência")
    void testBlockchainConsistency() throws Exception {
        System.out.println("\n=== TEST 3: Consistência de blockchain ===");
        
        Blockchain[] blockchains = new Blockchain[TOTAL_NODES];
        
        for (int i = 0; i < TOTAL_NODES; i++) {
            blockchains[i] = new Blockchain();
        }
        
        // Todos devem ter apenas GENESIS
        for (Blockchain bc : blockchains) {
            assertEquals(1, bc.getCommittedCommands().size(),
                        "Blockchain deve ter apenas GENESIS inicialmente");
            assertEquals("GENESIS", bc.getCommittedCommands().get(0));
        }
        
        // Adicionar mesmo nó a todos os blockchains
        TreeNode root = blockchains[0].getRoot();
        TreeNode node1 = new TreeNode("cmd1", "key1", root.getHash(), 1);
        
        for (Blockchain bc : blockchains) {
            bc.addNode(node1);
        }
        
        // Todos devem ter o nó
        for (Blockchain bc : blockchains) {
            assertNotNull(bc.getNode(node1.getHash()), 
                         "Todos devem ter o mesmo nó adicionado");
        }
        
        System.out.println("✓ Blockchains mantêm estrutura consistente\n");
    }
    

        /**
     * Teste 4: Líder rejeita votos quando threshold signature verification falha
     * - Imprimir "Threshold signature verification FAILED"
     */
    @Test
    @Order(4)
    @DisplayName("Líder rejeita votos com threshold signature inválido")
    void testLeaderRejectsByzantineMessages() throws Exception {
        System.out.println("\n=== TEST 4: Líder rejeita votos bizantinos ===");
        
        // Criar líder com CryptoLibrary que REJEITA threshold verification
        Link leaderLink = mock(Link.class);
        CryptoLibrary leaderCrypto = mock(CryptoLibrary.class);
        Blockchain leaderBlockchain = new Blockchain();
        
        doNothing().when(leaderLink).send(any(), anyString(), any(), anyString());
        
        // Mock signShare para o líder poder assinar suas próprias mensagens
        when(leaderCrypto.signShare(any())).thenReturn(mock(threshsig.SigShare.class));

        HotStuffConsensus leader = new HotStuffConsensus(1, TOTAL_NODES, MAX_FAULTS,
                                                         leaderLink, leaderCrypto, leaderBlockchain);
        
        assertTrue(leader.isLeader(), "Nó 1 deve ser líder na view 1");
        
        // Iniciar view e fazer líder propor
        leader.startView();
        leader.addCommand("arroz", "test-msg-1");
        
        Map<String, SigShare> votesMap = new HashMap<String, SigShare>();

        for (int i = 1; i <= TOTAL_NODES; i++) {
            String iStr = String.valueOf(i);
            CryptoLibrary cript = new CryptoLibrary("../config/node"+ i +".priv", "../config/node"+i+".pub", iStr);
            SigShare s;
            if(i<3){
                votesMap.put(iStr, cript.signShare("arroz".getBytes()));
            }
            else{
                votesMap.put(iStr, cript.signShare("esparguete".getBytes()));
            }
        }

        assertFalse(leader.verifyThresholdVote( votesMap, "arroz".getBytes()));
    }

    /**
     * Teste 5: Blockchain mantém registro de comandos commitados"
     */
    @Test
    @Order(5)
    @DisplayName("Blockchain mantém registro de comandos commitados")
    void testBlockchainCommitHistory() throws Exception {
        System.out.println("\n=== TEST 5: Blockchain mantém registro de comandos commitados ===");

        Link mockLink;
        CryptoLibrary mockCrypto;
        Blockchain blockchain;
        HotStuffConsensus consensus;

        int NODE_ID = 1;

                // Criar mocks
        mockLink = mock(Link.class);
        mockCrypto = mock(CryptoLibrary.class);
        
        // Configurar comportamento padrão dos mocks
        doNothing().when(mockLink).send(any(), anyString(), any(), anyString());
        when(mockCrypto.signShare(any())).thenReturn(mock(threshsig.SigShare.class));
        when(mockCrypto.verifyShare(any(), any())).thenReturn(true);
        
        // Criar instâncias reais
        blockchain = new Blockchain();
        consensus = new HotStuffConsensus(NODE_ID, TOTAL_NODES, MAX_FAULTS, 
                                         mockLink, mockCrypto, blockchain);
        // Verificar estado inicial
        assertEquals(1, blockchain.getCommittedCommands().size(), 
                    "Blockchain deveria ter apenas GENESIS inicialmente");
        assertEquals("GENESIS", blockchain.getCommittedCommands().get(0));
        
        // Adicionar nós ao blockchain
        TreeNode node1 = new TreeNode("command1", "key1", 
                                     blockchain.getRoot().getHash(), 1);
        blockchain.addNode(node1);
        
        TreeNode node2 = new TreeNode("command2", "key2", 
                                     node1.getHash(), 2);
        blockchain.addNode(node2);
        
        // Executar branch commitada
        blockchain.executeCommittedBranch(node2);
        
        // Verificar que comandos foram commitados
        assertEquals(3, blockchain.getCommittedCommands().size(), 
                    "Deveria ter 3 comandos (GENESIS + 2 novos)");
        assertTrue(blockchain.getCommittedCommands().contains("command1"));
        assertTrue(blockchain.getCommittedCommands().contains("command2"));
    }
}
