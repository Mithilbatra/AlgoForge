package com.algoforge.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.algoforge.judge.Language;
import org.junit.jupiter.api.Test;

class StaticAnalyzerTest {
    static StaticAnalyzer.Result py(String code) { return StaticAnalyzer.analyze(code, Language.PYTHON, "1 ≤ n ≤ 10^5"); }
    static StaticAnalyzer.Result cpp(String code) { return StaticAnalyzer.analyze(code, Language.CPP, "1 ≤ n ≤ 10^5"); }
    static StaticAnalyzer.Result java(String code) { return StaticAnalyzer.analyze(code, Language.JAVA, "1 ≤ n ≤ 10^5"); }

    @Test
    void bisectPerQueryIsNLogN() {
        var r = py("""
                import sys
                from bisect import bisect_left
                d = sys.stdin.buffer.read().split()
                n, q = int(d[0]), int(d[1])
                a = list(map(int, d[2:2 + n]))
                for x in d[2 + n:]:
                    print(bisect_left(a, int(x)))  # comment with for x in y: ignored
                """);
        assertThat(r.timeComplexity()).isEqualTo("O(n log n)");
        assertThat(r.spaceComplexity()).isEqualTo("O(n)");
    }

    @Test
    void nestedScanIsQuadratic() {
        var r = py("""
                n = int(input())
                a = list(map(int, input().split()))
                for x in a:
                    for i in range(n):
                        if a[i] >= x:
                            break
                """);
        assertThat(r.timeComplexity()).isEqualTo("O(n²)");
    }

    @Test
    void slidingWindowInnerWhileIsAmortised() {
        var r = cpp("""
                #include <bits/stdc++.h>
                int main(){ std::ios::sync_with_stdio(false); int n; long long b; std::cin >> n >> b;
                  std::vector<long long> c(n); for (auto &x : c) std::cin >> x;
                  long long s = 0; int l = 0, best = 0;
                  for (int r = 0; r < n; r++) { s += c[r]; while (s > b) { s -= c[l]; l++; } best = std::max(best, r - l + 1); }
                  std::cout << best << "\\n"; }
                """);
        assertThat(r.timeComplexity()).isEqualTo("O(n)");
        assertThat(r.findings()).noneMatch(f -> f.category().equals("language"));
    }

    @Test
    void binarySearchOnAnswerAddsALogFactor() {
        var r = java("""
                import java.io.*; import java.util.*;
                public class Main { public static void main(String[] args) throws IOException {
                  BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                  StringTokenizer st = new StringTokenizer(br.readLine()); int n = Integer.parseInt(st.nextToken()); long d = Long.parseLong(st.nextToken());
                  long[] w = new long[n]; st = new StringTokenizer(br.readLine()); for (int i = 0; i < n; i++) w[i] = Long.parseLong(st.nextToken());
                  long lo = 1, hi = 1_000_000_000L;
                  while (lo < hi) { long mid = (lo + hi) / 2; long days = 1, load = 0;
                    for (long x : w) { if (load + x > mid) { days++; load = 0; } load += x; }
                    if (days <= d) hi = mid; else lo = mid + 1; }
                  System.out.println(lo); } }
                """);
        assertThat(r.timeComplexity()).isEqualTo("O(n log n)");
    }

    @Test
    void bfsWithConstantDirectionLoopIsLinear() {
        var r = cpp("""
                #include <bits/stdc++.h>
                using namespace std;
                int dist[1000]; vector<int> adj[1000];
                int main(){ ios::sync_with_stdio(false); queue<int> q; q.push(0);
                  while (!q.empty()) { int u = q.front(); q.pop();
                    for (int d = 0; d < 4; d++) { int v = u + d; if (v < 1000 && dist[v] == 0) { dist[v] = dist[u] + 1; q.push(v); } } }
                  cout << dist[999] << '\\n'; }
                """);
        assertThat(r.timeComplexity()).isEqualTo("O(n)");
    }

    @Test
    void branchingRecursionWithoutMemoIsExponential() {
        var r = py("""
                import sys
                def best(i, c):
                    if i >= len(c):
                        return 0
                    return c[i] + min(best(i + 1, c), best(i + 2, c))
                c = list(map(int, sys.stdin.read().split()[1:]))
                print(min(best(0, c), best(1, c)))
                """);
        assertThat(r.exponential()).isTrue();
        assertThat(r.timeComplexity()).isEqualTo("O(2^n)");
        assertThat(r.findings()).anyMatch(f -> f.message().contains("memoisation"));
        assertThat(r.findings()).anyMatch(f -> f.message().contains("recursion limit"));
    }

    @Test
    void memoisedRecursionIsNotExponential() {
        var r = py("""
                import sys
                from functools import lru_cache
                sys.setrecursionlimit(10**6)
                @lru_cache(maxsize=None)
                def best(i):
                    if i >= n:
                        return 0
                    return c[i] + min(best(i + 1), best(i + 2))
                """);
        assertThat(r.exponential()).isFalse();
    }

    @Test
    void flagsSlowIoPatterns() {
        var j = java("""
                import java.util.*;
                public class Main { public static void main(String[] a) { Scanner s = new Scanner(System.in); int n = s.nextInt();
                  for (int i = 0; i < n; i++) System.out.println(s.nextInt()); } }
                """);
        assertThat(j.findings()).anyMatch(f -> f.message().contains("Scanner"));
        assertThat(j.findings()).anyMatch(f -> f.message().contains("println"));
        assertThat(j.languageScore()).isEqualTo(50);

        var c = cpp("#include <iostream>\nint main(){int n;std::cin>>n;for(int i=0;i<n;i++){int x;std::cin>>x;std::cout<<x<<std::endl;}}\n");
        assertThat(c.findings()).anyMatch(f -> f.message().contains("sync_with_stdio"));
        assertThat(c.findings()).anyMatch(f -> f.message().contains("endl"));

        var p = py("n = int(input())\nfor _ in range(n):\n    print(int(input()) * 2)\n");
        assertThat(p.findings()).anyMatch(f -> f.message().contains("input()"));
    }

    @Test
    void warnsAboutThirtyTwoBitOverflowWhenConstraintsAreLarge() {
        String code = "#include <bits/stdc++.h>\nint main(){int n;std::cin>>n;int s=0;for(int i=0;i<n;i++){int x;std::cin>>x;s+=x;}std::cout<<s;}\n";
        var r = StaticAnalyzer.analyze(code, Language.CPP, "|a_i| ≤ 10^9");
        assertThat(r.findings()).anyMatch(f -> f.category().equals("robustness") && f.message().contains("64-bit"));
        var safe = StaticAnalyzer.analyze(code.replace("int s=0", "long long s=0"), Language.CPP, "|a_i| ≤ 10^9");
        assertThat(safe.findings()).noneMatch(f -> f.message().contains("64-bit"));
    }

    @Test
    void commentsAndStringsDoNotCreateLoops() {
        var r = cpp("int main(){ /* for (int i = 0; i < n; i++) */ const char* s = \"while (true)\"; // for(;;)\n return 0; }");
        assertThat(r.timeComplexity()).isEqualTo("O(n)");
        assertThat(r.explanation()).contains("no explicit loops");
    }
}
