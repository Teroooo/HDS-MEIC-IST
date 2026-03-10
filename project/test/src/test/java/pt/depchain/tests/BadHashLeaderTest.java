package pt.depchain.tests;

import org.junit.jupiter.api.*;
import java.util.Map;

public class BadHashLeaderTest extends ByzantineTestBase {

    @Test
    @DisplayName("5 - Hash errada: líder bizantino propõe bloco com hash/comando corrompido (f=1 tolerado, view change)")
    void testBadHashLeader() throws Exception {
        Map<Integer, String> byzantine = Map.of(1, "bad-hash");
        runByzantineScenario(byzantine, "test_bad_hash_leader_abc123", true);
    }
}
