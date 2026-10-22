import sys
from collections import defaultdict
data = sys.stdin.buffer.read().split()
n, k = int(data[0]), int(data[1])
seen = defaultdict(int); seen[0] = 1
s = ans = 0
for x in data[2:2 + n]:
    s += int(x)
    ans += seen[s - k]
    seen[s] += 1
print(ans)
