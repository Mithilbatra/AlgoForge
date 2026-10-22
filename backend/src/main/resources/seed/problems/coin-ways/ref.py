import sys
data = sys.stdin.buffer.read().split()
n, s = int(data[0]), int(data[1])
MOD = 10**9 + 7
ways = [0] * (s + 1); ways[0] = 1
for c in map(int, data[2:2 + n]):
    for v in range(c, s + 1):
        ways[v] = (ways[v] + ways[v - c]) % MOD
print(ways[s])
