import sys
from collections import deque
data = sys.stdin.read().split()
r, c = int(data[0]), int(data[1])
g = data[2:2 + r]
for i in range(r):
    for j in range(c):
        if g[i][j] == 'S': s = (i, j)
dist = [[-1] * c for _ in range(r)]
dist[s[0]][s[1]] = 0
dq = deque([s]); ans = -1
while dq:
    i, j = dq.popleft()
    if g[i][j] == 'E':
        ans = dist[i][j]; break
    for ni, nj in ((i + 1, j), (i - 1, j), (i, j + 1), (i, j - 1)):
        if 0 <= ni < r and 0 <= nj < c and g[ni][nj] != '#' and dist[ni][nj] < 0:
            dist[ni][nj] = dist[i][j] + 1; dq.append((ni, nj))
print(ans)
