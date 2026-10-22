import sys
t = sys.stdin.read().split(); p = 0
def nxt(lo, hi):
    global p
    v = int(t[p]); p += 1
    assert lo <= v <= hi, f"{v} not in [{lo}, {hi}]"
    return v
n, q = nxt(1, 10**5), nxt(1, 10**5)
a = [nxt(-10**9, 10**9) for _ in range(n)]
assert all(a[i] <= a[i + 1] for i in range(n - 1)), "array not sorted"
for _ in range(q): nxt(-10**9, 10**9)
assert p == len(t), "unexpected trailing tokens"
