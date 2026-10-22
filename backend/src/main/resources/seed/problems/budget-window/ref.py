import sys
data = sys.stdin.buffer.read().split()
n, b = int(data[0]), int(data[1])
c = list(map(int, data[2:2 + n]))
best = left = s = 0
for right, x in enumerate(c):
    s += x
    while s > b:
        s -= c[left]; left += 1
    best = max(best, right - left + 1)
print(best)
