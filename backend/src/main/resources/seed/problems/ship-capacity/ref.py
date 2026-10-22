import sys
data = sys.stdin.buffer.read().split()
n, d = int(data[0]), int(data[1])
w = list(map(int, data[2:2 + n]))
def days(c):
    used, load = 1, 0
    for x in w:
        if load + x > c:
            used += 1; load = 0
        load += x
    return used
lo, hi = max(w), sum(w)
while lo < hi:
    mid = (lo + hi) // 2
    if days(mid) <= d: hi = mid
    else: lo = mid + 1
print(lo)
