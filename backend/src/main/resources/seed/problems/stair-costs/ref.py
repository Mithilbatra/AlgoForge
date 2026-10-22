import sys
data = sys.stdin.buffer.read().split()
n = int(data[0]); c = list(map(int, data[1:1 + n]))
prev2, prev1 = 0, 0  # best cost to stand at positions i-2 and i-1 (position 0 is the bank)
for x in c:
    prev2, prev1 = prev1, x + min(prev1, prev2)
print(min(prev1, prev2))
