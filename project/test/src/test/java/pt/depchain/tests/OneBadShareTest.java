package pt.depchain.tests;

import org.junit.jupiter.api.*;
import java.util.Map;

public class OneBadShareTest extends ByzantineTestBase {

    @Test
    @DisplayName("7 - 1 share errada: 1 nó assina com share inválida (f=1 tolerado)")
    void testOneBadShare() throws Exception {
        Map<Integer, String> byzantine = Map.of(4, "bad-share");
        runByzantineScenario(byzantine, "test_one_bad_share_qrs456", true);
    }
}
