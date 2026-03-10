package pt.depchain.tests;

import org.junit.jupiter.api.*;
import java.util.Map;

public class BadHashLeaderTest extends ByzantineTestBase {

    @Test
    @DisplayName("5 - Hash errada: líder bizantino propõe bloco com hash/comando corrompido (f=1 tolerado, view change)")
    void testBadHashLeader() throws Exception {
        // Node 1 is leader in view 1. It sends corrupted command+hash.
        // Honest nodes (2,3,4) detect mismatch and don't vote.
        // Pacemaker triggers view change -> node 2 becomes leader -> consensus reached.
        Map<Integer, String> byzantine = Map.of(1, "bad-hash");
        runByzantineScenario(byzantine, "test_bad_hash_leader_abc123", true);
    }
}
