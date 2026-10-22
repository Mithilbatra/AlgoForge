import sys
t = sys.stdin.read().split(); p = 0
def nxt(lo, hi):
    global p
    v = int(t[p]); p += 1
    assert lo <= v <= hi, f"{v} not in [{lo}, {hi}]"
    return v
n = nxt(1, 10**5)
for _ in range(n): nxt(0, 10**4)
assert p == len(t), "unexpected trailing tokens"
