package pt.depchain.service;

import org.junit.jupiter.api.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import pt.depchain.communication.Link;
import pt.depchain.communication.Message;
import pt.depchain.crypto.CryptoLibrary;
import pt.depchain.hotstuff.Blockchain;
import pt.depchain.hotstuff.HotStuffConsensus;
import pt.depchain.hotstuff.TreeNode;

/**
 * Testes unitários para o componente HotStuffConsensus.
 * Testa comportamentos específicos e propriedades do protocolo.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HotStuffConsensusUnitTest {

    private Link mockLink;
    private CryptoLibrary mockCrypto;
    private Blockchain blockchain;
    private HotStuffConsensus consensus;
    
    private static final int NODE_ID = 1;
    private static final int TOTAL_NODES = 4;
    private static final int MAX_FAULTS = 1;

    @BeforeEach
    void setUp() throws Exception {
        // Criar mocks
        mockLink = mock(Link.class);
        mockCrypto = mock(CryptoLibrary.class);
        
        // Configurar comportamento padrão dos mocks
        doNothing().when(mockLink).send(any(), anyInt(), any(), anyString());
        when(mockCrypto.signShare(any())).thenReturn(mock(threshsig.SigShare.class));
        when(mockCrypto.verifyShare(any(), any())).thenReturn(true);
        
        // Criar instâncias reais
        blockchain = new Blockchain();
        consensus = new HotStuffConsensus(NODE_ID, TOTAL_NODES, MAX_FAULTS, 
                                         mockLink, mockCrypto, blockchain);
    }

    @Test
    @Order(1)
    @DisplayName("Nó corretamente identifica se é líder baseado na view")
    void testLeaderElection() {
        // View 1: Líder é nó 1
        assertEquals(1, consensus.getViewNumber());
        assertTrue(consensus.isLeader(), "Nó 1 deveria ser líder na view 1");
        
        // Avançar para view 2: Líder é nó 2
        try {
            consensus.advanceView();
            assertEquals(2, consensus.getViewNumber());
            assertFalse(consensus.isLeader(), "Nó 1 NÃO deveria ser líder na view 2");
        } catch (Exception e) {
            fail("Erro ao avançar view: " + e.getMessage());
        }
    }
    
    @Test
    @Order(2)
    @DisplayName("View number avança corretamente em rotação round-robin")
    void testViewRotation() throws Exception {
        int initialView = consensus.getViewNumber();
        assertEquals(1, initialView, "View inicial deveria ser 1");
        
        // Avançar múltiplas views
        for (int i = 0; i < 3; i++) {
            consensus.advanceView();
        }
        
        assertEquals(4, consensus.getViewNumber(), "View deveria estar em 4 após 3 avanços");
    }
    
    
    @Test
    @Order(5)
    @DisplayName("Blockchain mantém registro de comandos commitados")
    void testBlockchainCommitHistory() {
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
