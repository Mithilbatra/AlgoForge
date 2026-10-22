import sys
from bisect import bisect_left
data = sys.stdin.buffer.read().split()
n, q = int(data[0]), int(data[1])
a = list(map(int, data[2:2 + n]))
out = []
for x in data[2 + n:2 + n + q]:
    i = bisect_left(a, int(x))
    out.append(str(i + 1) if i < n else "-1")
print("\n".join(out))
