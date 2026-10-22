package com.algoforge.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SandboxParseTest {
    @Test
    void parsesFramedResult() {
        byte[] frame = "0 0.25 9088 3 4\nerr!out".getBytes(StandardCharsets.UTF_8);
        Sandbox.Exec e = Sandbox.parse(new Proc.Result(0, frame, ""));
        assertThat(e.exitCode()).isZero();
        assertThat(e.wallSeconds()).isEqualTo(0.25);
        assertThat(e.maxRssKb()).isEqualTo(9088);
        assertThat(e.stderr()).isEqualTo("err!");
        assertThat(e.stdout()).isEqualTo("out");
    }

    @Test
    void dockerFailureWithoutFrameIsAnInfrastructureError() {
        assertThatThrownBy(() -> Sandbox.parse(new Proc.Result(1, new byte[0], "Error response from daemon: container is not running")))
                .isInstanceOf(SandboxException.class).hasMessageContaining("not running");
    }

    @Test
    void malformedHeaderIsRejected() {
        assertThatThrownBy(() -> Sandbox.parse(new Proc.Result(0, "a b c d e\n".getBytes(StandardCharsets.UTF_8), "")))
                .isInstanceOf(SandboxException.class);
    }
}
