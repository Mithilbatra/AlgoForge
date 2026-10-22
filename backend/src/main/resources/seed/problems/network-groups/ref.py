import sys
data = sys.stdin.buffer.read().split()
n, m = int(data[0]), int(data[1])
parent = list(range(n + 1)); size = [1] * (n + 1)
def find(x):
    while parent[x] != x:
        parent[x] = parent[parent[x]]; x = parent[x]
    return x
groups, out, p = n, [], 2
for _ in range(m):
    a, b = find(int(data[p])), find(int(data[p + 1])); p += 2
    if a != b:
        if size[a] < size[b]: a, b = b, a
        parent[b] = a; size[a] += size[b]; groups -= 1
    out.append(str(groups))
print("\n".join(out))
