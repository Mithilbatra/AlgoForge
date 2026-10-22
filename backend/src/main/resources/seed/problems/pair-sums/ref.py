import sys
from collections import defaultdict
data = sys.stdin.buffer.read().split()
n, k = int(data[0]), int(data[1])
seen = defaultdict(int); ans = 0
for x in map(int, data[2:2 + n]):
    ans += seen[k - x]
    seen[x] += 1
print(ans)
