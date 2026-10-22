import sys
t = sys.stdin.read().split(); p = 0
def nxt(lo, hi):
    global p
    v = int(t[p]); p += 1
    assert lo <= v <= hi, f"{v} not in [{lo}, {hi}]"
    return v
r, c = nxt(1, 500), nxt(1, 500)
rows = t[p:p + r]; p += r
assert len(rows) == r and all(len(x) == c and set(x) <= set(".#SE") for x in rows), "bad grid row"
joined = "".join(rows)
assert joined.count("S") == 1 and joined.count("E") == 1, "need exactly one S and one E"
assert p == len(t), "unexpected trailing tokens"
