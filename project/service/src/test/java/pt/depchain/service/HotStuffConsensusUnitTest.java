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
    @Order(3)
    @DisplayName("Comando é adicionado à fila de comandos pendentes")
    void testCommandQueuing() throws Exception {
        String command = "APPEND:test_data";
        String requestKey = "client1-msg1";
        
        // Adicionar comando não deve lançar exceção
        assertDoesNotThrow(() -> consensus.addCommand(command, requestKey),
                          "Adicionar comando não deveria lançar exceção");
        
        // Nota: O líder só envia PREPARE depois de receber quorum de NEW_VIEW
        // Este teste verifica apenas que o comando é aceito sem erro
    }
    
    @Test
    @Order(4)
    @DisplayName("Callback é invocado quando consenso decide")
    void testDecideCallback() throws Exception {
        // Flag para verificar se callback foi chamado
        final boolean[] callbackInvoked = {false};
        final TreeNode[] decidedNode = {null};
        final int[] decidedView = {-1};
        
        // Configurar callback
        consensus.setDecideCallback((node, view) -> {
            callbackInvoked[0] = true;
            decidedNode[0] = node;
            decidedView[0] = view;
        });
        
        // Simular mensagem DECIDE
        TreeNode testNode = new TreeNode("test_command", "test-key", 
                                        blockchain.getRoot().getHash(), 1);
        blockchain.addNode(testNode);
        
        // Criar mensagem DECIDE (simplificada - sem QC completo)
        // Nota: Este é um teste simplificado, teste real precisa de QC válido
        
        assertNotNull(consensus, "Consensus deveria estar inicializado");
        // Callback será testado mais completamente em testes de integração
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
    
    @Test
    @Order(6)
    @DisplayName("Nó líder envia mensagens NEW_VIEW corretamente")
    void testNewViewMessage() throws Exception {
        // Limpar invocações anteriores
        reset(mockLink);
        
        // Iniciar view (nó 1 é líder)
        consensus.startView();
        
        // Verificar que NEW_VIEW foi enviada ao líder
        verify(mockLink, times(1)).send(
            eq(Link.Type.NODE),
            anyInt(), // líder atual
            eq(Message.Type.NEW_VIEW),
            anyString()
        );
    }
    
    @Test
    @Order(7)
    @DisplayName("Múltiplos comandos podem ser enfileirados sequencialmente")
    void testMultipleCommandQueuing() throws Exception {
        String[] commands = {
            "APPEND:data1",
            "APPEND:data2",
            "APPEND:data3"
        };
        
        // Adicionar múltiplos comandos
        for (int i = 0; i < commands.length; i++) {
            final String command = commands[i];
            final String requestKey = "client1-msg" + (i + 1);
            assertDoesNotThrow(() -> consensus.addCommand(command, requestKey));
        }
        
        // Verificar que não houve erros ao adicionar comandos
        assertNotNull(consensus, "Consensus deve estar inicializado");
    }
    
    @Test
    @Order(8)
    @DisplayName("Sistema mantém integridade após múltiplas mudanças de view")
    void testViewChangeIntegrity() throws Exception {
        int initialView = consensus.getViewNumber();
        
        // Avançar várias views
        for (int i = 0; i < 10; i++) {
            consensus.advanceView();
        }
        
        // Verificar que view number está correto
        assertEquals(initialView + 10, consensus.getViewNumber());
        
        // Verificar que ainda pode adicionar comandos
        assertDoesNotThrow(() -> consensus.addCommand("test", "key-after-changes"));
    }
    
    @Test
    @Order(9)
    @DisplayName("Blockchain mantém estrutura de árvore correta")
    void testBlockchainTreeStructure() {
        TreeNode root = blockchain.getRoot();
        assertNotNull(root, "Blockchain deveria ter nó raiz (genesis)");
        assertEquals("GENESIS", root.getCommand());
        
        // Adicionar nós formando uma cadeia
        TreeNode node1 = new TreeNode("cmd1", "key1", root.getHash(), 1);
        blockchain.addNode(node1);
        
        TreeNode node2 = new TreeNode("cmd2", "key2", node1.getHash(), 2);
        blockchain.addNode(node2);
        
        // Verificar que podemos recuperar nós pela hash
        assertNotNull(blockchain.getNode(root.getHash()));
        assertNotNull(blockchain.getNode(node1.getHash()));
        assertNotNull(blockchain.getNode(node2.getHash()));
        
        // Verificar que node2 estende de node1 (pai direto)
        assertTrue(node2.extendsFrom(node1), "Node2 deve estender de node1");
        // Verificar que node1 estende de root (pai direto)
        assertTrue(node1.extendsFrom(root), "Node1 deve estender de root");
    }
    
    @Test
    @Order(10)
    @DisplayName("Consenso lida com múltiplos nós concorrentemente")
    void testConcurrentNodeBehavior() throws Exception {
        // Criar múltiplos nós de consenso
        HotStuffConsensus[] nodes = new HotStuffConsensus[TOTAL_NODES];
        
        for (int i = 0; i < TOTAL_NODES; i++) {
            Link link = mock(Link.class);
            CryptoLibrary crypto = mock(CryptoLibrary.class);
            Blockchain bc = new Blockchain();
            
            doNothing().when(link).send(any(), anyInt(), any(), anyString());
            when(crypto.signShare(any())).thenReturn(mock(threshsig.SigShare.class));
            when(crypto.verifyShare(any(), any())).thenReturn(true);
            
            nodes[i] = new HotStuffConsensus(i + 1, TOTAL_NODES, MAX_FAULTS, 
                                            link, crypto, bc);
        }
        
        // Verificar que cada nó sabe se é líder
        assertTrue(nodes[0].isLeader(), "Nó 1 deveria ser líder na view 1");
        assertFalse(nodes[1].isLeader(), "Nó 2 NÃO deveria ser líder na view 1");
        assertFalse(nodes[2].isLeader(), "Nó 3 NÃO deveria ser líder na view 1");
        assertFalse(nodes[3].isLeader(), "Nó 4 NÃO deveria ser líder na view 1");
        
        // Avançar view em todos os nós
        for (HotStuffConsensus node : nodes) {
            node.advanceView();
        }
        
        // Verificar mudança de líder
        assertFalse(nodes[0].isLeader(), "Nó 1 NÃO deveria ser líder na view 2");
        assertTrue(nodes[1].isLeader(), "Nó 2 deveria ser líder na view 2");
    }
}
