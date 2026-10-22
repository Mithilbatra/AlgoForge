package com.algoforge.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.AppProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxPreflightTest {
    @Test
    void reportsImagesTheDaemonDoesNotHave() {
        // Holds whether or not a Docker daemon is reachable: a missing daemon reports every image as missing.
        var cfg = new AppProperties.Sandbox("docker", "", 128, 8192, 30, Map.of("python", "algoforge-sandbox-does-not-exist:0"));
        assertThat(Sandbox.missingImages(cfg)).containsExactly("algoforge-sandbox-does-not-exist:0");
    }
}
