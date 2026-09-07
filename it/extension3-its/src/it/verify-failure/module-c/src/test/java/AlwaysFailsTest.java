import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class AlwaysFailsTest {
    @Test
    void alwaysFails() {
        fail("planned failure: verifyFullBuild must fail the build on this would-be-skipped module");
    }
}
