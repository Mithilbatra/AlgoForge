import sys
data = sys.stdin.buffer.read().split()
n, k = int(data[0]), int(data[1])
t = list(map(int, data[2:2 + n]))
lo, hi = 1, min(t) * k
while lo < hi:
    mid = (lo + hi) // 2
    if sum(mid // x for x in t) >= k: hi = mid
    else: lo = mid + 1
print(lo)
