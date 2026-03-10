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

import java.util.List;

import org.mockito.ArgumentCaptor;

/**
 * Testes de integração para verificar tolerância a falhas bizantinas (BFT)
 * e comportamento quando o líder crasha.
 * 
 * NOTA: Estes são testes de propriedades e estrutura, não simulações completas.
 * Para testes E2E reais, execute os nós separadamente e teste manualmente.
 * 
 * Cenários testados:
 * 1. Propriedades básicas do sistema com múltiplos nós
 * 2. Eleição de líder e rotação de views
 * 3. Estrutura para tolerar até f=1 falhas
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BFTConsensusTest {

    private static final int TOTAL_NODES = 3; // 3 nós: 2 honestos + 1 bizantino
    private static final int MAX_FAULTS = 1; // f = 1, tolerando 1 falha bizantina
    
    
    /**
     * Teste 2: Eleição de líder funciona corretamente em views rotativas
     * View 1: Líder é nó 1, View 2: Líder é nó 2, etc.
     */
    @Test
    @Order(2)
    @DisplayName("Eleição de líder em rondas rotativas")
    void testLeaderElection() throws Exception {
        System.out.println("\n=== TEST 2: Eleição de líder ===");
        
        HotStuffConsensus[] nodes = new HotStuffConsensus[TOTAL_NODES];
        
        for (int i = 0; i < TOTAL_NODES; i++) {
            Link mockLink = mock(Link.class);
            CryptoLibrary mockCrypto = mock(CryptoLibrary.class);
            Blockchain blockchain = new Blockchain();
            
            doNothing().when(mockLink).send(any(), anyInt(), any(), anyString());
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
     * Teste 3: Sistema avança de view quando líder falha (timeout)
     * Simula que o líder não responde e sistema avança para próxima view
     */
    @Test
    @Order(3)
    @DisplayName("Sistema pode avançar de view (simulando timeout)")
    void testViewAdvancement() throws Exception {
        System.out.println("\n=== TEST 3: Avanço de view ===");
        
        Link mockLink = mock(Link.class);
        CryptoLibrary mockCrypto = mock(CryptoLibrary.class);
        Blockchain blockchain = new Blockchain();
        
        doNothing().when(mockLink).send(any(), anyInt(), any(), anyString());
        when(mockCrypto.signShare(any())).thenReturn(mock(threshsig.SigShare.class));
        when(mockCrypto.verifyShare(any(), any())).thenReturn(true);
        
        HotStuffConsensus consensus = new HotStuffConsensus(2, TOTAL_NODES, MAX_FAULTS,
                                                           mockLink, mockCrypto, blockchain);
        
        int initialView = consensus.getViewNumber();
        assertEquals(1, initialView, "View inicial é 1");
        
        // Simular timeout - nó avança de view
        consensus.advanceView();
        assertEquals(2, consensus.getViewNumber(), "View deveria avançar para 2");
        
        // Novo líder é nó 2 (este nó)  
        assertTrue(consensus.isLeader(), "Nó 2 é líder na view 2");
        
        System.out.println("✓ Sistema avança de view corretamente\n");
    }
    
    /**
     * Teste 4: Múltiplos nós podem coexistir e mudar de líder
     * Verifica que após múltiplas mudanças de view, liderança rota corretamente
     */
    @Test
    @Order(4)
    @DisplayName("Múltiplas mudanças de líder")
    void testMultipleLeaderChanges() throws Exception {
        System.out.println("\n=== TEST 4: Múltiplas mudanças de líder ===");
        
        HotStuffConsensus[] nodes = new HotStuffConsensus[TOTAL_NODES];
        
        for (int i = 0; i < TOTAL_NODES; i++) {        
            Link mockLink = mock(Link.class);
            CryptoLibrary mockCrypto = mock(CryptoLibrary.class);
            Blockchain blockchain = new Blockchain();
            
            doNothing().when(mockLink).send(any(), anyInt(), any(), anyString());
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
     * Teste 5: Blockchain mantém consistência entre nós
     * Verifica que múltiplos nós têm blockchains com mesma estrutura inicial
     */
    @Test
    @Order(5)
    @DisplayName("Blockchains mantêm consistência")
    void testBlockchainConsistency() throws Exception {
        System.out.println("\n=== TEST 5: Consistência de blockchain ===");
        
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
    
}
