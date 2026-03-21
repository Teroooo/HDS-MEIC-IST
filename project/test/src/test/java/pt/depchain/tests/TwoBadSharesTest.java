package pt.depchain.tests;

import org.junit.jupiter.api.*;
import java.util.Map;

public class TwoBadSharesTest extends ByzantineTestBase {

    @Test
    @DisplayName("8 - 2 shares erradas: 2 nós com shares inválidas (f>1, consenso falha)")
    void testTwoBadShares() throws Exception {
        Map<Integer, String> byzantine = Map.of(3, "bad-share", 4, "bad-share");
        runByzantineScenario(byzantine, "test_two_bad_shares_def000", false);
    }
}
