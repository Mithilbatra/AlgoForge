import sys
data = sys.stdin.buffer.read().split()
n, q = int(data[0]), int(data[1])
pre = [0]
for x in data[2:2 + n]: pre.append(pre[-1] + int(x))
out, p = [], 2 + n
for _ in range(q):
    l, r = int(data[p]), int(data[p + 1]); p += 2
    out.append(str(pre[r] - pre[l - 1]))
print("\n".join(out))
