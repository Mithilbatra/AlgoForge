import sys
t = sys.stdin.read().split(); p = 0
def nxt(lo, hi):
    global p
    v = int(t[p]); p += 1
    assert lo <= v <= hi, f"{v} not in [{lo}, {hi}]"
    return v
n, s = nxt(1, 100), nxt(1, 5 * 10**4)
cs = [nxt(1, 5 * 10**4) for _ in range(n)]
assert len(set(cs)) == n, "denominations must be distinct"
assert p == len(t), "unexpected trailing tokens"
