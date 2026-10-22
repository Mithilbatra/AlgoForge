package com.algoforge.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.algoforge.eval.EvaluationService;
import com.algoforge.judge.JobQueue;
import com.algoforge.judge.JudgeService;
import com.algoforge.judge.WorkerHarness;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.Cookie;

/**
 * Submission -> queue -> worker -> sandbox -> verdict -> evaluation, using the real Docker sandbox images.
 * Skipped when the images are not built (run sandbox/build.sh).
 */
class JudgeIntegrationTest extends IntegrationTest {
    @Autowired JobQueue queue;
    @Autowired JudgeService judge;
    @Autowired EvaluationService evaluations;

    @BeforeAll
    static void sandboxImagesPresent() throws Exception {
        for (String lang : new String[] {"python", "cpp", "java"}) {
            Process p = new ProcessBuilder("docker", "image", "inspect", "algoforge-sandbox-" + lang + ":1").start();
            boolean done = p.waitFor(20, TimeUnit.SECONDS);
            assumeTrue(done && p.exitValue() == 0, "sandbox image for " + lang + " is not built");
        }
    }

    JsonNode submitAndJudge(Cookie s, String title, String lang, String code) throws Exception {
        JsonNode q = body(mvc.perform(jsonPost("/api/submissions", s, Map.of("problemId", problemId(title), "language", lang, "code", code))).andReturn());
        runQueuedJobs();
        return body(mvc.perform(get("/api/submissions/" + q.get("submissionId").asLong()).cookie(s)).andReturn());
    }

    void runQueuedJobs() {
        WorkerHarness worker = new WorkerHarness(queue, judge, evaluations);
        while (worker.runOne()) { /* drain */ }
    }

    @Test
    void verdictsComeFromRealExecution() throws Exception {
        Cookie s = register("judge");
        String ok = "import sys\nd=sys.stdin.buffer.read().split()\nn,q=int(d[0]),int(d[1])\np=[0]\nfor x in d[2:2+n]: p.append(p[-1]+int(x))\n"
                + "o=[]\ni=2+n\nfor _ in range(q):\n    l,r=int(d[i]),int(d[i+1]); i+=2\n    o.append(str(p[r]-p[l-1]))\nprint('\\n'.join(o))\n";
        JsonNode ac = submitAndJudge(s, "Range Totals", "python", ok);
        assertThat(ac.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(ac.get("passed").asInt()).isEqualTo(ac.get("total").asInt()).isGreaterThan(3);
        assertThat(ac.get("maxMemoryKb").asInt()).isPositive();
        assertThat(ac.get("runtimeVersion").asText()).startsWith("Python 3.12");
        JsonNode report = body(mvc.perform(get("/api/evaluations/" + ac.get("evaluationId").asLong()).cookie(s)).andReturn());
        assertThat(report.get("correctness").asText()).isEqualTo("VERIFIED");
        assertThat(report.get("complexity").get(0).get("timeComplexity").asText()).isEqualTo("O(n)");

        String overflow = "#include <bits/stdc++.h>\nint main(){int n,q;std::cin>>n>>q;std::vector<int>p(n+1);for(int i=1;i<=n;i++){int x;std::cin>>x;p[i]=p[i-1]+x;}"
                + "while(q--){int l,r;std::cin>>l>>r;std::cout<<p[r]-p[l-1]<<'\\n';}}\n";
        JsonNode wa = submitAndJudge(s, "Range Totals", "cpp", overflow);
        assertThat(wa.get("status").asText()).isEqualTo("WRONG_ANSWER");
        assertThat(wa.get("tests").toString()).contains("\"input\"");  // practice mode shows the failing test

        JsonNode ce = submitAndJudge(s, "Range Totals", "java", "public class Main { void f() { int x = ; } }");
        assertThat(ce.get("status").asText()).isEqualTo("COMPILATION_ERROR");
        assertThat(ce.get("compileOutput").asText()).contains("error");

        JsonNode tle = submitAndJudge(s, "Stepping Stones", "cpp", "int main(){ volatile long long x = 0; for(;;) x++; }");
        assertThat(tle.get("status").asText()).isEqualTo("TIME_LIMIT_EXCEEDED");

        JsonNode rte = submitAndJudge(s, "Stepping Stones", "python", "raise SystemExit(3)\n");
        assertThat(rte.get("status").asText()).isEqualTo("RUNTIME_ERROR");
    }

    @Test
    void deepEvaluationFindsASmallVerifiedCounterexample() throws Exception {
        Cookie s = register("deep");
        String bug = "import sys\nd=sys.stdin.read().split()\nn=int(d[0]);c=list(map(int,d[1:1+n]))\na,b=0,0\nfor x in c:\n    a,b=b,x+min(a,b)\nprint(b)\n";
        JsonNode wa = submitAndJudge(s, "Stepping Stones", "python", bug);
        assertThat(wa.get("status").asText()).isEqualTo("WRONG_ANSWER");
        mvc.perform(jsonPost("/api/submissions/" + wa.get("id").asLong() + "/evaluate", s, Map.of("useAi", true)));
        runQueuedJobs();
        JsonNode e = body(mvc.perform(get("/api/evaluations/" + wa.get("evaluationId").asLong()).cookie(s)).andReturn());
        assertThat(e.get("status").asText()).isEqualTo("DEEP_READY");
        assertThat(e.get("aiStatus").asText()).isEqualTo("NOT_CONFIGURED");  // no API keys in tests: deterministic path only
        JsonNode g = e.get("generatedTests");
        assertThat(g.get("verifiedTotal").asInt()).isGreaterThan(10);
        JsonNode ce = g.get("counterexample");
        assertThat(ce.get("category").asText()).isEqualTo("random n=1");
        assertThat(ce.get("expected").asText().strip()).isEqualTo("0");
        long stored = jdbc.sql("select count(*) from test_cases where kind = 'GENERATED' and owner_id = ? and verification = 'REFERENCE'")
                .param(userId(s)).query(Long.class).single();
        assertThat(stored).isPositive();
    }
}
