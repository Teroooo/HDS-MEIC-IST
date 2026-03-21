package pt.depchain.tests;

import org.junit.jupiter.api.*;
import java.util.Map;

public class DuplicateMessageTest extends ByzantineTestBase {

    @Test
    @DisplayName("6 - Mensagem duplicada: nó bizantino envia voto duplicado (f=1 tolerado)")
    void testDuplicateMessage() throws Exception {
        Map<Integer, String> byzantine = Map.of(4, "duplicate-msg");
        runByzantineScenario(byzantine, "test_duplicate_msg_xyz789", true);
    }
}
